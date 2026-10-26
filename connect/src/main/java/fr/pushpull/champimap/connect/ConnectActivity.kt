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
import fr.pushpull.champimap.connect.sync.RequestParser
import fr.pushpull.champimap.connect.sync.SyncEngine
import fr.pushpull.champimap.connect.ui.ConnectTheme
import fr.pushpull.champimap.connect.ui.SyncScreen
import kotlinx.coroutines.delay

/*
 * Porte d'entrée de Champimap Connect (action SYNC, réservée à la permission « signature » CONNECT).
 *  1. lit et valide la demande (départements, zones météo de 0,25°, centres antipoison) ;
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
        /** Temps d'affichage de « Terminé ✓ » avant la fermeture automatique. */
        const val CLOSE_DELAY_MS = 1_500L
    }

    /** Identifiant de la synchro suivie par cet écran (pour ne pas renvoyer un vieux résultat). */
    private var syncId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncId = savedInstanceState?.getString(KEY_SYNC_ID)

        // Nouvelle demande : uniquement à la première création (pas après une rotation).
        if (savedInstanceState == null && intent?.action == SyncProtocol.ACTION_SYNC) {
            if (!startFrom(intent)) return
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
                        if (snap.files.isNotEmpty() && snap.errors.isEmpty()) {
                            delay(CLOSE_DELAY_MS)
                            finish()
                        }
                    }
                }
                // Retour pendant la synchro : on rend la main, la synchro continue.
                BackHandler(enabled = snap.running) { leaveRunning() }
                SyncScreen(
                    snapshot = snap,
                    onCancel = { SyncEngine.cancel() },
                    onClose = {
                        deliverResult(SyncEngine.state.value)
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Nouvelle demande alors que l'écran est déjà ouvert (singleTop) : lancée si rien ne tourne.
        if (intent.action == SyncProtocol.ACTION_SYNC && !SyncEngine.isRunning) startFrom(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SYNC_ID, syncId)
    }

    /**
     * Démarre la synchro décrite par l'Intent (ou rejoint celle déjà en cours).
     * Renvoie false si l'activité s'est déjà fermée (mode arrière-plan).
     */
    private fun startFrom(intent: Intent): Boolean {
        val parsed = RequestParser.parse(intent)
        SyncEngine.start(this, parsed) // false = une synchro tourne déjà : on la suit
        syncId = SyncEngine.state.value.syncId
        if (intent.getBooleanExtra(SyncProtocol.EXTRA_BACKGROUND, false)) {
            leaveRunning()
            return false
        }
        return true
    }

    /** Rend la main pendant la synchro : RESULT_OK + instantané « en cours », puis fermeture. */
    private fun leaveRunning() {
        val snap = SyncEngine.state.value
        setResult(Activity.RESULT_OK, resultIntent(snap))
        finish()
    }

    /** Fixe le résultat final : OK si au moins un fichier est prêt, sinon CANCELED. */
    private fun deliverResult(snap: SyncSnapshot) {
        val ok = snap.files.isNotEmpty() && !SyncEngine.wasCancelled
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
