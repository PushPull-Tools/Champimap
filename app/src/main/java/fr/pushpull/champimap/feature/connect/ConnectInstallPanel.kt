package fr.pushpull.champimap.feature.connect

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Parcours d'installation du module Champimap Connect, partagé par l'étape 3 du premier
 * lancement et l'écran Connect des Paramètres.
 *
 * Règles :
 *  - rien ne démarre tout seul à l'affichage : l'installation ne part QUE sur « Installer le module »
 *    (ou « Réessayer ») ;
 *  - autorisation « applis inconnues » manquante → explication + bouton « Ouvrir le réglage Android » ;
 *    au retour dans l'appli (ON_RESUME), si l'autorisation est accordée, l'installation s'enchaîne ;
 *  - à chaque état, il y a toujours un bouton pour avancer (« Réessayer ») et un pour sortir
 *    (« Plus tard ») : l'utilisateur n'est jamais bloqué.
 */

/**
 * Bloc d'installation (messages + boutons).
 * @param onInstalled appelé quand le module est installé après une demande faite ici, ou sur « Continuer ».
 * @param onLater « Plus tard » (la tentative en cours est abandonnée avant).
 * @param laterLabel texte du bouton « Plus tard ».
 * @param showLaterWhenIdle afficher « Plus tard » avant même la première tentative.
 * @param continueLabel bouton affiché si le module est déjà installé (null = aucun).
 */
@Composable
fun ConnectInstallPanel(
    onInstalled: () -> Unit,
    onLater: () -> Unit,
    laterLabel: String = "Plus tard",
    showLaterWhenIdle: Boolean = true,
    continueLabel: String? = "Continuer",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val install by ConnectClient.installState.collectAsState()

    // Vrai quand l'utilisateur a demandé l'installation sur cet écran (survit à la rotation
    // et à l'arrêt du processus pendant le passage dans les réglages Android).
    var requested by rememberSaveable { mutableStateOf(false) }
    // Vrai entre l'appui sur « Ouvrir le réglage Android » et le retour dans l'appli.
    var awaitingPermission by rememberSaveable { mutableStateOf(false) }
    // Revenu des réglages sans avoir activé l'autorisation.
    var permissionStillMissing by rememberSaveable { mutableStateOf(false) }
    // Le réglage Android n'a pas pu être ouvert (ROM exotique).
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }

    /** Lance (ou relance) l'installation : uniquement sur action de l'utilisateur. */
    fun startInstall() {
        requested = true
        // « Réessayer » alors que l'autorisation manque toujours : on le dit au lieu de ne rien changer.
        permissionStillMissing = install is InstallState.NeedsPermission && !ConnectClient.canInstallPackages(context)
        settingsUnavailable = false
        scope.launch { ConnectClient.install(context) }
    }

    /** « Plus tard » : abandonne la tentative en cours puis rend la main à l'écran. */
    fun later() {
        requested = false
        awaitingPermission = false
        ConnectClient.cancelInstall(context)
        onLater()
    }

    /** Ouvre le réglage Android « Installer des applis inconnues » (sur action de l'utilisateur). */
    fun openUnknownSources() {
        permissionStillMissing = false
        if (openUnknownSourcesSettings(context)) {
            awaitingPermission = true
            settingsUnavailable = false
        } else {
            settingsUnavailable = true
        }
    }

    // Retour au premier plan : si l'utilisateur revient des réglages avec l'autorisation,
    // on enchaîne l'installation qu'il avait demandée ; sinon on le lui signale.
    OnResumeEffect {
        if (awaitingPermission && !ConnectClient.isInstalled(context)) {
            awaitingPermission = false
            if (ConnectClient.canInstallPackages(context)) {
                startInstall()
            } else {
                permissionStillMissing = true
            }
        }
    }

    // Module installé (statut PackageInstaller ou vérification au retour) → étape suivante.
    LaunchedEffect(install, requested) {
        if (requested && install is InstallState.Installed) {
            requested = false
            onInstalled()
        }
    }

    // Relu à chaque changement d'état (installation / désinstallation faite hors de l'appli).
    val installed = remember(install) { ConnectClient.isInstalled(context) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            // Déjà installé : on continue.
            installed || install is InstallState.Installed -> {
                PanelText("Le module est installé.")
                VSpace(12.dp)
                if (continueLabel != null) ChampiButton(continueLabel, onClick = onInstalled)
            }

            // Autorisation Android manquante : explication + bouton vers le réglage.
            install is InstallState.NeedsPermission -> {
                PanelText(
                    "Android doit d'abord autoriser Champimap à installer Champimap Connect.",
                    bold = true,
                )
                VSpace(6.dp)
                PanelText(
                    "Touche « Ouvrir le réglage Android », active « Autoriser depuis cette source », " +
                        "puis reviens dans Champimap avec la flèche Retour d'Android : l'installation démarre toute seule.",
                    muted = true,
                )
                if (permissionStillMissing) {
                    VSpace(6.dp)
                    PanelText("L'autorisation n'est pas encore activée.", error = true)
                }
                if (settingsUnavailable) {
                    VSpace(6.dp)
                    PanelText(
                        "Impossible d'ouvrir le réglage. Va dans Paramètres Android › Applis › Champimap › " +
                            "Installer des applis inconnues, puis touche « Réessayer ».",
                        error = true,
                    )
                }
                VSpace(12.dp)
                ChampiButton("Ouvrir le réglage Android", onClick = { openUnknownSources() })
                VSpace(8.dp)
                ChampiOutlinedButton("Réessayer", onClick = { startInstall() })
                VSpace(8.dp)
                ChampiOutlinedButton(laterLabel, onClick = { later() })
            }

            // Installation en cours : la fenêtre d'Android doit être validée.
            install is InstallState.Installing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        champiText("Installation en cours…"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                VSpace(6.dp)
                PanelText(
                    "Valide la fenêtre d'Android « Installer ». Si elle n'apparaît pas ou a été fermée, touche « Réessayer ».",
                    muted = true,
                )
                VSpace(12.dp)
                ChampiOutlinedButton("Réessayer", onClick = { startInstall() })
                VSpace(8.dp)
                ChampiOutlinedButton(laterLabel, onClick = { later() })
            }

            // Échec ou annulation : message + réessayer / plus tard.
            install is InstallState.Failed -> {
                PanelText((install as InstallState.Failed).message, error = true)
                VSpace(12.dp)
                ChampiButton("Réessayer", onClick = { startInstall() })
                VSpace(8.dp)
                ChampiOutlinedButton(laterLabel, onClick = { later() })
            }

            // Départ : rien n'a été lancé.
            else -> {
                ChampiButton("Installer Champimap Connect", onClick = { startInstall() })
                if (showLaterWhenIdle) {
                    VSpace(8.dp)
                    ChampiOutlinedButton(laterLabel, onClick = { later() })
                }
            }
        }
    }
}

/** Texte centré du bloc, lisible dans tous les thèmes (couleurs du thème uniquement). */
@Composable
private fun PanelText(text: String, bold: Boolean = false, muted: Boolean = false, error: Boolean = false) {
    Text(
        champiText(text),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        textAlign = TextAlign.Center,
        color = when {
            error -> MaterialTheme.colorScheme.error
            muted -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onBackground
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Ouvre le réglage « Installer des applis inconnues » de Champimap.
 * Essaie d'abord l'écran de Champimap, puis la liste générale. Faux si rien n'a pu s'ouvrir.
 */
private fun openUnknownSourcesSettings(context: Context): Boolean {
    val candidates = listOf(
        ConnectClient.unknownSourcesIntent(context),
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
    )
    for (intent in candidates) {
        // Depuis une activité : pas de nouvelle tâche (le retour revient directement ici).
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
        // Contexte sans activité : nouvelle tâche obligatoire.
        if (runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    return false
}
