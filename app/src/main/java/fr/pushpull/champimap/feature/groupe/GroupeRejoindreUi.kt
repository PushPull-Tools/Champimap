package fr.pushpull.champimap.feature.groupe

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.Stack
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.launch

/*
 * Écran « Rejoindre » : scan du QR code du créateur, puis connexion au réseau Wi-Fi Direct
 * et création locale de la sortie partagée (même identifiant, même groupe).
 * Accepte aussi un QR code de SPOT partagé : il est alors transmis à l'écran d'import.
 */

/** Étapes de l'écran « Rejoindre ». */
private sealed class RejoindreEtat {
    object Scan : RejoindreEtat()
    class Connexion(val invite: GroupInvite) : RejoindreEtat()
    class Erreur(val message: String, val invite: GroupInvite?) : RejoindreEtat()
}

@Composable
internal fun GroupeRejoindreContent(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var etat by remember { mutableStateOf<RejoindreEtat>(RejoindreEtat.Scan) }
    val sync by GroupSync.state.collectAsState()

    /** Rejoint la sortie décrite par l'invitation (autorisation Wi-Fi Direct déjà accordée). */
    fun rejoindre(invite: GroupInvite) {
        etat = RejoindreEtat.Connexion(invite)
        scope.launch {
            try {
                GroupRepository.joinSortie(context, invite)
                val erreur = WifiDirect.connect(context, invite)
                if (erreur != null) {
                    etat = RejoindreEtat.Erreur(erreur, invite)
                } else {
                    GroupSync.start(context, invite.groupId)
                }
            } catch (e: Exception) {
                ErrorLogger.log("Groupe", "Connexion au groupe impossible", e)
                etat = RejoindreEtat.Erreur("La connexion n'a pas fonctionné. Réessaie près du téléphone qui a créé la sortie.", invite)
            }
        }
    }

    // Invitation scannée en attente de l'autorisation Wi-Fi Direct.
    var enAttente by remember { mutableStateOf<GroupInvite?>(null) }
    val wifiGate = rememberPermissionGate(wifiDirectPermissions()) { ok ->
        val inv = enAttente
        if (ok && inv != null) {
            rejoindre(inv)
        } else if (!ok) {
            etat = RejoindreEtat.Erreur(
                "Sans l'autorisation « appareils à proximité », les téléphones ne peuvent pas se trouver.", inv,
            )
        }
    }

    // Résultat du scan : invitation de groupe, ou spot partagé.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult // scan annulé
        val invite = GroupInvite.parse(text)
        when {
            invite != null -> {
                enAttente = invite
                wifiGate.request()
            }
            SpotShare.parseQr(text) != null -> {
                PendingSpotQr.text = text
                nav.navigate(Routes.IMPORT_PARTAGE) { popUpTo(Routes.GROUPE_REJOINDRE) { inclusive = true } }
            }
            else -> {
                etat = RejoindreEtat.Erreur("Ce QR code ne vient pas de Champimap.", null)
            }
        }
    }
    val lancerScan = { scanner.launch(qrScanOptions("Scanne le QR code de la sortie")) }
    val cameraGate = rememberPermissionGate(arrayOf(Manifest.permission.CAMERA)) { ok ->
        if (ok) {
            lancerScan()
        } else {
            etat = RejoindreEtat.Erreur("Il faut autoriser l'appareil photo pour scanner le QR code.", null)
        }
    }

    // Connexion établie et premier échange réussi : on part dans la sortie.
    val connexion = etat as? RejoindreEtat.Connexion
    LaunchedEffect(connexion, sync.lastSyncAt, sync.status) {
        val inv = connexion?.invite ?: return@LaunchedEffect
        if (sync.groupId == inv.groupId && sync.status == GroupState.Status.CONNECTE && sync.lastSyncAt > 0) {
            nav.navigate(Routes.sortie(inv.sortieId)) {
                popUpTo(Routes.GROUPE_REJOINDRE) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    ChampiScreen(title = "Rejoindre", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Stack(Modifier.fillMaxWidth()) {
                when (val e = etat) {
                    RejoindreEtat.Scan -> {
                        BodyText(
                            "Scanne le QR code affiché sur le téléphone qui a lancé la « Sortie à plusieurs ». " +
                                "Les téléphones se connectent directement entre eux, sans Internet.",
                        )
                        ChampiButton("Scanner le QR code", onClick = { cameraGate.request() })
                    }
                    is RejoindreEtat.Connexion -> {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        BodyText("Connexion à la sortie de ${e.invite.name}…", Modifier.fillMaxWidth(), center = true)
                        ParticipantsCard(sync)
                        // Sur Android 8-9, l'autre téléphone doit parfois accepter l'invitation.
                        BodyText("Si une demande de connexion apparaît sur l'autre téléphone, elle doit être acceptée.")
                        ChampiOutlinedButton("Aller à la sortie sans attendre", onClick = {
                            nav.navigate(Routes.sortie(e.invite.sortieId)) {
                                popUpTo(Routes.GROUPE_REJOINDRE) { inclusive = true }
                                launchSingleTop = true
                            }
                        })
                    }
                    is RejoindreEtat.Erreur -> {
                        MessageCard(e.message, error = true)
                        val inv = e.invite
                        if (inv != null) ChampiButton("Réessayer", onClick = { enAttente = inv; wifiGate.request() })
                        ChampiOutlinedButton("Scanner un autre QR code", onClick = { etat = RejoindreEtat.Scan; cameraGate.request() })
                    }
                }
            }
        }
    }
}
