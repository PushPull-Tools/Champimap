package fr.pushpull.champimap.feature.groupe

import fr.pushpull.champimap.core.util.motPluriel
import fr.pushpull.champimap.core.util.pluriel
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import fr.pushpull.champimap.PendingImport
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.Stack
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Écran « Spot reçu » : import d'un fichier .champi (ouvert depuis une autre appli,
 * mémorisé dans PendingImport.uri par MainActivity) ou d'un QR code de spot (scanné ici
 * ou depuis l'écran Rejoindre, via PendingSpotQr).
 * Étapes : lecture → code si chiffré → aperçu → « Ajouter à mes spots » → fiche du spot.
 */

/** Étapes de l'import. */
private sealed class ImportEtat {
    object Lecture : ImportEtat()
    object Rien : ImportEtat()
    class Code(val file: File, val erreur: String? = null) : ImportEtat()
    class Apercu(val pkg: SharedPackage) : ImportEtat()
    object Ajout : ImportEtat()
    class Erreur(val message: String) : ImportEtat()
}

@Composable
internal fun ImportPartageContent(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var etat by remember { mutableStateOf<ImportEtat>(ImportEtat.Lecture) }

    // Au démarrage : fichier reçu, ou QR code transmis par l'écran Rejoindre (lus une seule fois).
    LaunchedEffect(Unit) {
        val qr = PendingSpotQr.text.also { PendingSpotQr.text = null }
        val uri = PendingImport.uri.also { PendingImport.uri = null }
        etat = when {
            qr != null -> SpotShare.parseQr(qr)?.let { ImportEtat.Apercu(it) } ?: ImportEtat.Erreur("QR code illisible.")
            uri != null -> try {
                when (val r = SpotShare.receive(context, uri)) {
                    is SpotShare.Received.NeedsCode -> ImportEtat.Code(r.file)
                    is SpotShare.Received.Ready -> ImportEtat.Apercu(r.pkg)
                }
            } catch (e: Exception) {
                ErrorLogger.log("Import", "Lecture du fichier reçu impossible", e)
                ImportEtat.Erreur("Ce fichier n'est pas un spot Champimap, ou il est abîmé.")
            }
            else -> ImportEtat.Rien
        }
    }

    // Scan d'un QR code de spot directement depuis cet écran.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        etat = SpotShare.parseQr(text)?.let { ImportEtat.Apercu(it) }
            ?: ImportEtat.Erreur(
                if (GroupInvite.parse(text) != null) "C'est un QR code de sortie à plusieurs : utilise « Rejoindre »."
                else "Ce QR code n'est pas un spot Champimap.",
            )
    }
    val cameraGate = rememberPermissionGate(arrayOf(Manifest.permission.CAMERA)) { ok ->
        if (ok) {
            scanner.launch(qrScanOptions("Scanne le QR code du spot"))
        } else {
            etat = ImportEtat.Erreur("Il faut autoriser l'appareil photo pour scanner le QR code.")
        }
    }

    /** Retour : vers l'accueil si l'appli a été ouverte directement sur cet écran. */
    val retour: () -> Unit = {
        SpotShare.clearImport(context)
        if (nav.previousBackStackEntry != null) nav.popBackStack()
        else nav.navigate(Routes.HOME) { popUpTo(Routes.IMPORT_PARTAGE) { inclusive = true } }
    }

    /** Ajoute le spot et ouvre sa fiche (l'écran d'import est retiré de la pile). */
    fun ajouter(pkg: SharedPackage) {
        etat = ImportEtat.Ajout
        scope.launch {
            try {
                val spotId = SpotShare.importPackage(context, pkg)
                // Ouverte directement sur l'import : on place l'accueil dessous (Retour y mène).
                if (nav.previousBackStackEntry == null) {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.IMPORT_PARTAGE) { inclusive = true } }
                }
                nav.navigate(Routes.spot(spotId)) {
                    if (nav.currentDestination?.route == Routes.IMPORT_PARTAGE) popUpTo(Routes.IMPORT_PARTAGE) { inclusive = true }
                    launchSingleTop = true
                }
            } catch (e: Exception) {
                ErrorLogger.log("Import", "Ajout du spot reçu impossible", e)
                etat = ImportEtat.Erreur("Le spot n'a pas pu être ajouté.")
            }
        }
    }

    ChampiScreen(title = "Spot reçu", onBack = retour) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Stack(Modifier.fillMaxWidth()) {
                when (val e = etat) {
                    ImportEtat.Lecture, ImportEtat.Ajout -> {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        BodyText(if (e == ImportEtat.Ajout) "Ajout du spot…" else "Lecture…", Modifier.fillMaxWidth(), center = true)
                    }
                    ImportEtat.Rien -> {
                        BodyText("Ouvre un fichier .champi reçu, ou scanne le QR code d'un spot.")
                        ChampiButton("Scanner un QR code", onClick = { cameraGate.request() })
                    }
                    is ImportEtat.Code -> CodeInput(e) { code ->
                        etat = ImportEtat.Lecture
                        scope.launch {
                            etat = try {
                                ImportEtat.Apercu(SpotShare.decrypt(context, e.file, code))
                            } catch (x: ChampiCrypto.WrongCodeException) {
                                ImportEtat.Code(e.file, "Code incorrect. Vérifie-le avec la personne qui t'a envoyé le spot.")
                            } catch (x: Exception) {
                                ErrorLogger.log("Import", "Déchiffrement du fichier reçu impossible", x)
                                ImportEtat.Erreur("Ce fichier est abîmé.")
                            }
                        }
                    }
                    is ImportEtat.Apercu -> {
                        Apercu(e.pkg)
                        ChampiButton("Ajouter à mes spots", onClick = { ajouter(e.pkg) })
                        ChampiOutlinedButton("Annuler", onClick = retour)
                    }
                    is ImportEtat.Erreur -> {
                        MessageCard(e.message, error = true)
                        ChampiOutlinedButton("Scanner un QR code", onClick = { cameraGate.request() })
                        ChampiOutlinedButton("Retour", onClick = retour)
                    }
                }
            }
        }
    }
}

/** Saisie du code de déchiffrement. */
@Composable
private fun CodeInput(etat: ImportEtat.Code, onValider: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    ChampiCard(Modifier.fillMaxWidth()) {
        SectionTitle("Fichier protégé")
        BodyText("Entre le code que la personne t'a donné (8 caractères, ex. K7MP-3XQH).")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(12) },
            singleLine = true,
            label = { Text("Code") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            isError = etat.erreur != null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        etat.erreur?.let { BodyText(it, Modifier.padding(top = 6.dp)) }
    }
    ChampiButton(
        "Ouvrir",
        enabled = code.count { it.isLetterOrDigit() } >= 8,
        onClick = { onValider(code) },
    )
}

/** Aperçu du spot reçu avant import : nom, qui l'envoie, contenu, et s'il existe déjà. */
@Composable
private fun Apercu(pkg: SharedPackage) {
    val spot = pkg.spot ?: return
    val b = pkg.shared.bundle
    var dejaConnu by remember { mutableStateOf(false) }
    LaunchedEffect(spot.id) { dejaConnu = AppGraph.db.syncDao().spot(spot.id) != null }
    val date = remember(pkg.shared.createdAt) {
        if (pkg.shared.createdAt > 0) SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(pkg.shared.createdAt)) else null
    }
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(champiText(spot.name), style = MaterialTheme.typography.titleLarge)
        pkg.shared.from?.let { BodyText("Envoyé par ${it.name}" + (date?.let { d -> " le $d" } ?: "")) }
        spot.note?.takeIf { it.isNotBlank() }?.let { BodyText(it, Modifier.padding(top = 6.dp)) }
        spot.repere?.takeIf { it.isNotBlank() }?.let { BodyText("Repère : $it", Modifier.padding(top = 4.dp)) }
        SectionTitle("Contenu")
        val lignes = buildList {
            add(if (pkg.shared.withHistory) "Lieu + historique" else "Lieu seul")
            if (b.recoltes.isNotEmpty()) add("${pluriel(b.recoltes.size, "récolte")}, ${pluriel(b.items.size, "espèce")} ${motPluriel(b.items.size, "notée")}")
            if (b.visits.isNotEmpty()) add(pluriel(b.visits.size, "visite"))
            if (pkg.photoFiles.isNotEmpty()) add(pluriel(pkg.photoFiles.size, "photo"))
        }
        lignes.forEach { Text(champiText("• $it"), style = MaterialTheme.typography.bodyMedium) }
        if (dejaConnu) BodyText("Tu as déjà ce spot : il sera mis à jour (la version la plus récente gagne).", Modifier.padding(top = 6.dp))
    }
}
