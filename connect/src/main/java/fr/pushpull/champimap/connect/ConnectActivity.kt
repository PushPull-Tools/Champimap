package fr.pushpull.champimap.connect

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.pushpull.champimap.connect.download.IaModelDownloader
import fr.pushpull.champimap.connect.sync.ParsedRequest
import fr.pushpull.champimap.connect.sync.RequestParser
import fr.pushpull.champimap.connect.sync.SyncEngine
import fr.pushpull.champimap.connect.ui.ConnectTheme
import fr.pushpull.champimap.connect.ui.IaVersionDialog
import fr.pushpull.champimap.connect.ui.MeteredDialog
import fr.pushpull.champimap.connect.ui.SyncScreen
import fr.pushpull.champimap.connect.util.Network
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Porte d'entrée de Champimap Connect (action SYNC, réservée à la permission « signature » CONNECT).
 *  1. lit et valide la demande (départements, zones météo de 0,25°, centres antipoison, modèle) ;
 *     - si la demande le prévoit : choix de la version du modèle d'identification (liste GitHub) ;
 *     - sur données mobiles : fenêtre « Tu es sur les données mobiles » (Attendre le Wi-Fi / Continuer) ;
 *  2. lance la synchronisation (SyncEngine, coroutine + service de premier plan) ;
 *  3. affiche l'avancement : étape, pourcentage global et par département, détail, erreurs ;
 *  4. à la fin, renvoie le résultat (instantané JSON + URI des fichiers en ClipData, lecture autorisée)
 *     et se ferme (après une courte pause sur « Terminé ✓ » ; en cas d'échec, l'écran reste affiché
 *     avec la raison jusqu'à « Fermer »).
 * Mode arrière-plan (EXTRA_BACKGROUND) ou bouton Retour : on rend la main tout de suite,
 * la suite arrive par diffusion ACTION_PROGRESS et dans state.json.
 */
class ConnectActivity : ComponentActivity() {
    private companion object {
        const val KEY_SYNC_ID = "syncId"
        const val KEY_STAGE = "stage"
        const val KEY_IA_WANTED = "iaWanted"
        const val KEY_IA_SIZE = "iaSize"
        /** Marque « la plus récente » choisie dans la liste. */
        const val LATEST = "latest"
        /** Taille moyenne d'une carte de département au zoom 15 (estimation affichée). */
        const val MAP_BYTES_ESTIMATE = 35L * 1024 * 1024
        /** Temps d'affichage de « Terminé ✓ » avant la fermeture automatique. */
        const val CLOSE_DELAY_MS = 1_500L
    }

    /** Identifiant de la synchro suivie par cet écran (pour ne pas renvoyer un vieux résultat). */
    private var syncId: String? = null

    /** Étape avant lancement : aucune, choix de la version du modèle, ou avertissement « données mobiles ». */
    private enum class Stage { NONE, CHOOSE_IA, METERED }

    /** Demande en attente d'un choix de l'utilisateur (null = rien en attente). */
    private var pending by mutableStateOf<ParsedRequest?>(null)
    private var stage by mutableStateOf(Stage.NONE)
    /** Version choisie dans la liste (LATEST, « ia-vN » ou null si pas de choix) et sa taille. */
    private var iaWanted: String? = null
    private var iaSize: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncId = savedInstanceState?.getString(KEY_SYNC_ID)
        val savedStage = savedInstanceState?.getString(KEY_STAGE)?.let { n -> Stage.entries.firstOrNull { it.name == n } }

        // Nouvelle demande : uniquement à la première création (pas après une rotation).
        if (savedInstanceState == null && intent?.action == SyncProtocol.ACTION_SYNC) {
            if (!startFrom(intent)) return
        } else if (savedStage != null && savedStage != Stage.NONE && !SyncEngine.isRunning) {
            // Rotation pendant un choix : la demande est relue, le choix déjà fait est conservé.
            iaWanted = savedInstanceState?.getString(KEY_IA_WANTED)
            iaSize = savedInstanceState?.getLong(KEY_IA_SIZE) ?: 0L
            pending = applyIaChoice(RequestParser.parse(intent))
            stage = savedStage
        } else if (syncId == null) {
            // Ouvert depuis la notification : on suit la synchro en cours (ou la dernière).
            syncId = SyncEngine.state.value.syncId.ifEmpty { null }
        }

        setContent {
            ConnectTheme {
                val snap by SyncEngine.state.collectAsState()
                // Fin de la synchro suivie : résultat puis fermeture automatique si tout va bien.
                LaunchedEffect(snap.finished, snap.syncId) {
                    if (snap.finished && snap.syncId == syncId) {
                        deliverResult(snap)
                        if (snap.hasResult && snap.errors.isEmpty()) {
                            delay(CLOSE_DELAY_MS)
                            finish()
                        }
                    }
                }
                // Retour pendant la synchro : on rend la main, la synchro continue.
                BackHandler(enabled = snap.running) { leaveRunning() }
                val waiting = pending
                SyncScreen(
                    // Pendant un choix, l'ancienne synchro n'est pas affichée (elle prêterait à confusion).
                    snapshot = if (waiting != null) SyncSnapshot(step = "En attente de ton choix…") else snap,
                    onCancel = { SyncEngine.cancel() },
                    onClose = {
                        deliverResult(SyncEngine.state.value)
                        finish()
                    },
                )
                if (waiting != null) {
                    when (stage) {
                        Stage.CHOOSE_IA -> IaVersionDialog(
                            installed = waiting.request.ia?.installed,
                            onPick = { release, latest ->
                                iaWanted = if (latest) LATEST else release.tag
                                iaSize = release.size
                                pending = applyIaChoice(waiting)
                                afterChoice()
                            },
                            onCancel = { abandon(waitWifi = false) },
                        )
                        Stage.METERED -> MeteredDialog(
                            estimate = estimate(waiting),
                            onWait = { abandon(waitWifi = true) },
                            onContinue = { launch(waiting) },
                        )
                        Stage.NONE -> Unit
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Nouvelle demande alors que l'écran est déjà ouvert (singleTop) : lancée si rien ne tourne.
        if (intent.action == SyncProtocol.ACTION_SYNC && !SyncEngine.isRunning) {
            setIntent(intent) // relue après une rotation pendant un choix
            iaWanted = null
            iaSize = 0
            startFrom(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SYNC_ID, syncId)
        outState.putString(KEY_STAGE, if (pending != null) stage.name else Stage.NONE.name)
        outState.putString(KEY_IA_WANTED, iaWanted)
        outState.putLong(KEY_IA_SIZE, iaSize)
    }

    /**
     * Démarre la synchro décrite par l'Intent (ou rejoint celle déjà en cours), après les choix éventuels
     * (version du modèle, données mobiles). Renvoie false si l'activité s'est déjà fermée (mode arrière-plan).
     */
    private fun startFrom(intent: Intent): Boolean {
        val parsed = RequestParser.parse(intent)
        if (SyncEngine.isRunning || parsed.isEmpty) return launch(parsed)
        pending = parsed
        if (parsed.request.ia?.choose == true) {
            stage = Stage.CHOOSE_IA
            return true
        }
        return afterChoice()
    }

    /** Après le choix de version : avertissement si données mobiles, sinon lancement. */
    private fun afterChoice(): Boolean {
        val p = pending ?: return true
        // Liste des versions seule : quelques Ko, pas d'avertissement « données mobiles ».
        val listOnly = p.request.ia?.listOnly == true
        if (!listOnly && Network.current(this) == Network.Kind.METERED) {
            stage = Stage.METERED
            return true
        }
        return launch(p)
    }

    /** Lance la synchro (ou suit celle en cours). Renvoie false si l'activité s'est fermée (arrière-plan). */
    private fun launch(parsed: ParsedRequest): Boolean {
        pending = null
        stage = Stage.NONE
        SyncEngine.start(this, parsed) // false = une synchro tourne déjà : on la suit
        syncId = SyncEngine.state.value.syncId
        if (intent?.getBooleanExtra(SyncProtocol.EXTRA_BACKGROUND, false) == true) {
            leaveRunning()
            return false
        }
        return true
    }

    /** Applique la version choisie dans la liste à la demande (le choix vaut actualisation). */
    private fun applyIaChoice(parsed: ParsedRequest): ParsedRequest {
        val ia = parsed.request.ia ?: return parsed
        val wanted = iaWanted ?: return parsed
        val newIa = ia.copy(choose = false, force = true, wanted = if (wanted == LATEST) null else wanted)
        return parsed.copy(request = parsed.request.copy(ia = newIa))
    }

    /** Rien n'est lancé : résultat « annulé » (avec le motif « attendre le Wi-Fi » le cas échéant). */
    private fun abandon(waitWifi: Boolean) {
        pending = null
        stage = Stage.NONE
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(SyncProtocol.EXTRA_WAIT_WIFI, waitWifi))
        finish()
    }

    /** Taille estimée de la synchro : cartes (≈ 35 Mo par département) + modèle (≈ 140 Mo). */
    private fun estimate(p: ParsedRequest): String {
        val parts = ArrayList<String>()
        val maps = p.request.mapDepartements.size
        if (maps > 0) parts += "cartes ≈ ${mo(maps * MAP_BYTES_ESTIMATE)} ($maps département${if (maps > 1) "s" else ""})"
        p.request.ia?.let { ia ->
            val size = if (iaSize > 0) iaSize else IaModelDownloader.APPROX_BYTES
            parts += if (ia.installed == null || ia.force) "modèle d'identification ≈ ${mo(size)}"
            else "modèle d'identification ≈ ${mo(size)} s'il y a une nouvelle version"
        }
        if (parts.isEmpty()) parts += "quelques Mo (urgences, météo)"
        return parts.joinToString(" + ")
    }

    /** « 140 Mo ». */
    private fun mo(bytes: Long): String = String.format(Locale.FRANCE, "%d Mo", (bytes + 524_288) / 1_048_576)

    /** Rend la main pendant la synchro : RESULT_OK + instantané « en cours », puis fermeture. */
    private fun leaveRunning() {
        val snap = SyncEngine.state.value
        setResult(Activity.RESULT_OK, resultIntent(snap))
        finish()
    }

    /** Fixe le résultat final : OK si au moins un fichier est prêt, sinon CANCELED. */
    private fun deliverResult(snap: SyncSnapshot) {
        val ok = snap.hasResult && !SyncEngine.wasCancelled
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, resultIntent(snap))
    }

    /**
     * Intent de résultat : instantané JSON + tous les URI (fichiers et state.json) en ClipData,
     * avec FLAG_GRANT_READ_URI_PERMISSION pour que Champimap puisse les lire.
     */
    private fun resultIntent(snap: SyncSnapshot): Intent {
        val data = Intent().putExtra(SyncProtocol.EXTRA_SNAPSHOT, snap.toJson())
        val uris = snap.files.map { Uri.parse(it.uri) } + Uri.parse(SyncProtocol.STATE_URI)
        val clip = ClipData.newRawUri("champimap-connect", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        data.clipData = clip
        data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return data
    }
}
