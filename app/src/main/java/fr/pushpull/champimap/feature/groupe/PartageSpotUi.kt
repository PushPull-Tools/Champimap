package fr.pushpull.champimap.feature.groupe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.Stack
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Écran « Partager le spot » :
 *  - Quoi : « Lieu seul » ou « Lieu + mon historique » (récoltes, visites, positions, photos) ;
 *  - « Garder la position GPS dans les photos ? » : Non par défaut (photos réencodées sans EXIF) ;
 *  - Comment : QR code (lieu seul, sans photos) ou fichier .champi (chiffré par un code court
 *    à donner de vive voix, ou non chiffré) envoyé par le partage Android.
 */

/** Ce qui est affiché en bas de l'écran après un choix d'envoi. */
private sealed class PartageResultat {
    object Rien : PartageResultat()
    class Qr(val text: String) : PartageResultat()
    object Preparation : PartageResultat()
    class Fichier(val code: String?) : PartageResultat()
    class Erreur(val message: String) : PartageResultat()
}

@Composable
internal fun PartageSpotContent(nav: NavController, spotId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var spotName by remember { mutableStateOf<String?>(null) }
    var quoi by remember { mutableIntStateOf(0) }        // 0 = lieu seul, 1 = lieu + historique
    var gps by remember { mutableIntStateOf(1) }         // 0 = Oui, 1 = Non (par défaut)
    var chiffre by remember { mutableIntStateOf(0) }     // 0 = chiffré, 1 = non chiffré
    var nbPhotos by remember { mutableIntStateOf(0) }
    var resultat by remember { mutableStateOf<PartageResultat>(PartageResultat.Rien) }

    LaunchedEffect(spotId) { spotName = AppGraph.db.syncDao().spot(spotId)?.name ?: "" }
    LaunchedEffect(spotId, quoi) { nbPhotos = SpotShare.photoCount(spotId, quoi == 1) }

    /** Fabrique le fichier .champi puis ouvre le partage Android. */
    fun envoyerFichier() {
        resultat = PartageResultat.Preparation
        scope.launch {
            try {
                val code = if (chiffre == 0) ChampiCrypto.newCode() else null
                val file = SpotShare.buildFile(context, spotId, withHistory = quoi == 1, keepGps = gps == 0, code = code)
                resultat = PartageResultat.Fichier(code)
                SpotShare.shareFile(context, file)
            } catch (e: Exception) {
                ErrorLogger.log("Partage", "Préparation du fichier de spot impossible", e)
                resultat = PartageResultat.Erreur("Le fichier n'a pas pu être préparé.")
            }
        }
    }

    ChampiScreen(title = "Partager le spot", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Stack(Modifier.fillMaxWidth()) {
                val nom = spotName
                if (nom == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else if (nom.isEmpty()) {
                    MessageCard("Ce spot n'existe plus.", error = true)
                } else {
                    Text(champiText(nom), style = MaterialTheme.typography.titleMedium)

                    // Quoi partager.
                    ChampiCard(Modifier.fillMaxWidth()) {
                        SectionTitle("Quoi partager ?")
                        ChoiceButtons(listOf("Lieu seul", "Lieu + mon historique"), quoi) { quoi = it; resultat = PartageResultat.Rien }
                        BodyText(
                            if (quoi == 0) "La position, le nom, les notes et la photo du spot."
                            else "En plus : tes récoltes, visites, positions successives et leurs photos.",
                            Modifier.padding(top = 8.dp),
                        )
                    }

                    // Position GPS dans les photos.
                    ChampiCard(Modifier.fillMaxWidth()) {
                        SectionTitle("Garder la position GPS dans les photos ?")
                        ChoiceButtons(listOf("Oui", "Non"), gps) { gps = it; resultat = PartageResultat.Rien }
                        BodyText(
                            if (gps == 1) "Les photos sont envoyées sans position ni autres informations cachées."
                            else "Les photos gardent toutes leurs informations, position GPS comprise.",
                            Modifier.padding(top = 8.dp),
                        )
                    }

                    // Par QR code.
                    ChampiCard(Modifier.fillMaxWidth()) {
                        SectionTitle("Par QR code")
                        BodyText("Le lieu seul, sans photos. L'autre le scanne avec Champimap (Démarrer → Rejoindre, ou Spot reçu).")
                        ChampiOutlinedButton("Afficher le QR code", modifier = Modifier.padding(top = 8.dp), onClick = {
                            scope.launch {
                                val t = SpotShare.qrText(spotId)
                                resultat = if (t != null) PartageResultat.Qr(t) else PartageResultat.Erreur("Ce spot n'existe plus.")
                            }
                        })
                    }

                    // Par fichier.
                    ChampiCard(Modifier.fillMaxWidth()) {
                        SectionTitle("Par fichier .champi")
                        BodyText(
                            if (nbPhotos > 0) "Avec $nbPhotos photo(s). Envoi par Quick Share, Bluetooth, messagerie…"
                            else "Envoi par Quick Share, Bluetooth, messagerie…",
                        )
                        ChoiceButtons(listOf("Chiffré", "Non chiffré"), chiffre) { chiffre = it; resultat = PartageResultat.Rien }
                        BodyText(
                            if (chiffre == 0) "Un code s'affiche : donne-le de vive voix, jamais dans le même message que le fichier."
                            else "Toute personne qui reçoit le fichier pourra voir le spot.",
                            Modifier.padding(top = 8.dp),
                        )
                        ChampiButton("Partager le fichier", modifier = Modifier.padding(top = 8.dp), onClick = { envoyerFichier() })
                    }

                    // Résultat.
                    when (val r = resultat) {
                        PartageResultat.Rien -> {}
                        PartageResultat.Preparation -> {
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                            BodyText("Préparation du fichier…", Modifier.fillMaxWidth(), center = true)
                        }
                        is PartageResultat.Qr -> {
                            QrImage(r.text, Modifier.widthIn(max = 340.dp).align(Alignment.CenterHorizontally))
                        }
                        is PartageResultat.Fichier -> {
                            val code = r.code
                            if (code != null) CodeCard(code)
                            else MessageCard("Fichier prêt (non chiffré).")
                        }
                        is PartageResultat.Erreur -> MessageCard(r.message, error = true)
                    }
                }
            }
        }
    }
}

/** Grand affichage du code à donner de vive voix. */
@Composable
private fun CodeCard(code: String) {
    ChampiCard(Modifier.fillMaxWidth()) {
        SectionTitle("Code à donner de vive voix")
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        BodyText("Sans ce code, le fichier est illisible. Il n'est pas enregistré : note-le si besoin avant de quitter.")
    }
}
