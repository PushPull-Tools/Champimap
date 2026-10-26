package fr.pushpull.champimap.feature.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Écrans Champimap Connect :
 *  - ConnectOnboardingStep : étape 3 du premier lancement (explication + installation du module) ;
 *  - ConnectDownloadStep   : étape 4 (préparation des cartes, avancement) ;
 *  - ConnectScreen         : écran des Paramètres (état, synchro, installation).
 * Les écrans d'intoxication sont dans IntoxScreens.kt.
 * (« Étape x sur 4 » et la barre en 4 segments sont déjà dessinés par OnboardingScreen.)
 */

// ---------------------------------------------------------------------------------------------
// Étape 3 : explication et installation
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectOnboardingStep(onInstalled: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val install by ConnectClient.installState.collectAsState()
    // Vrai quand l'utilisateur a demandé l'installation sur cet écran.
    var requested by rememberSaveable { mutableStateOf(false) }
    ConnectResumeEffect()

    // Installation terminée → étape suivante.
    LaunchedEffect(install, requested) {
        if (requested && install is InstallState.Installed) {
            requested = false
            onInstalled()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VSpace(8.dp)
        Text(
            champiText("Champimap ne donnera jamais tes coins à champignons !"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VSpace(16.dp)
        Text(
            champiText("Cette appli n'a pas accès à Internet, et c'est voulu !"),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        VSpace(6.dp)
        Text(
            champiText("Rien ne peut en sortir, tes données t'appartiennent."),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        VSpace(6.dp)
        Text(
            champiText(
                "Les cartes, la météo et les mises à jour passent par un petit module séparé, " +
                    "qui ne connaît que des zones larges, jamais tes spots.",
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        VSpace(20.dp)
        ChampiCard(Modifier.fillMaxWidth()) {
            Text(champiText("Comment ça s'installe"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            VSpace(8.dp)
            NumberedStep(
                1,
                "Android va te demander d'autoriser Champimap à installer des applis. " +
                    "C'est seulement pour poser le module, sans donner Internet à Champimap.",
            )
            NumberedStep(2, "Tu valides l'installation du module.")
            NumberedStep(3, "C'est fini. Tu peux retirer l'autorisation ou désinstaller le module quand tu veux.")
        }
        VSpace(12.dp)
        InstallMessage(install)
        VSpace(12.dp)

        val busy = install is InstallState.Installing
        ChampiButton(
            text = when {
                ConnectClient.isInstalled(context) -> "Continuer"
                busy -> "Installation…"
                else -> "Installer le module"
            },
            enabled = !busy,
            onClick = {
                if (ConnectClient.isInstalled(context)) {
                    onInstalled()
                } else {
                    requested = true
                    scope.launch { ConnectClient.install(context) }
                }
            },
        )
        VSpace(8.dp)
        ChampiOutlinedButton("Plus tard (sans carte ni météo)", onClick = onLater)
        VSpace(16.dp)
    }
}

/** Message d'état de l'installation (autorisation, en cours, échec). */
@Composable
private fun InstallMessage(state: InstallState) {
    val text = when (state) {
        InstallState.NeedsPermission -> "Active « Autoriser depuis cette source », puis reviens ici : l'installation continue toute seule."
        InstallState.Installing -> "Installation du module en cours…"
        InstallState.Installed -> "Module installé."
        is InstallState.Failed -> state.message
        InstallState.Idle -> null
    } ?: return
    Text(
        champiText(text),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = if (state is InstallState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Étape 4 : préparation des cartes
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectDownloadStep(onContinue: () -> Unit) {
    val ui by ConnectClient.syncState.collectAsState()
    val startSync = rememberConnectSync()
    // La synchro n'est lancée qu'une fois (même après rotation).
    var started by rememberSaveable { mutableStateOf(false) }
    ConnectResumeEffect()

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            startSync()
        }
    }

    val finished = ui.phase == SyncPhase.DONE || ui.phase == SyncPhase.FAILED || ui.phase == SyncPhase.CANCELLED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(champiText("Préparation de tes cartes"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        VSpace(6.dp)
        Text(
            champiText("Champimap Connect télécharge les cartes de tes départements, les urgences et la météo des zones larges."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(16.dp)
        ChampiCard(Modifier.fillMaxWidth()) { SyncProgressBlock(ui) }
        VSpace(16.dp)
        if (ui.phase == SyncPhase.FAILED || ui.phase == SyncPhase.CANCELLED) {
            ChampiOutlinedButton("Relancer la synchro", onClick = startSync)
            VSpace(8.dp)
        }
        ChampiButton("C'est parti !", onClick = onContinue, enabled = finished)
        VSpace(8.dp)
        if (!finished) ChampiOutlinedButton("Continuer, ça finit en arrière-plan", onClick = onContinue)
        VSpace(16.dp)
    }
}

// ---------------------------------------------------------------------------------------------
// Écran des Paramètres
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val install by ConnectClient.installState.collectAsState()
    val ui by ConnectClient.syncState.collectAsState()
    val lastSync by ConnectClient.lastSync.collectAsState()
    val settings by AppGraph.settings.flow.collectAsState(initial = null)
    val weatherDays by remember { AppGraph.db.weatherDao().countFlow() }.collectAsState(initial = 0)
    val hospitals by remember { AppGraph.db.hospitalDao().countFlow() }.collectAsState(initial = 0)
    val startSync = rememberConnectSync()
    val refreshAll = rememberConnectSync(refreshMaps = true)
    ConnectResumeEffect()

    // L'état « installé » est relu à chaque passage (install / désinstallation hors de l'appli).
    val version = remember(install, ui.phase) { ConnectClient.installedVersion(context) }
    val installed = version != null
    val running = ui.phase == SyncPhase.RUNNING

    ChampiScreen(title = "Champimap Connect", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            // État du module.
            SectionTitle("Module")
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText(if (installed) "Installé · version $version" else "Pas installé"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                VSpace(4.dp)
                Text(
                    champiText("Dernière synchro : ${formatSyncDate(lastSync)}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val depts = settings?.departements ?: emptyList()
                Text(
                    champiText(
                        "Départements : ${if (depts.isEmpty()) "aucun" else depts.joinToString(", ")} · " +
                            "cartes : ${mapCount(context)} · urgences : $hospitals · jours de météo : $weatherDays",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!installed) {
                    VSpace(8.dp)
                    InstallMessage(install)
                }
            }

            // Actions.
            VSpace(12.dp)
            if (installed) {
                ChampiButton(if (running) "Synchro en cours…" else "Lancer la synchro", onClick = startSync, enabled = !running)
                VSpace(8.dp)
                ChampiOutlinedButton("Retélécharger toutes les cartes", onClick = refreshAll, enabled = !running)
            } else {
                ChampiButton(
                    if (install is InstallState.Installing) "Installation…" else "Installer le module",
                    enabled = install !is InstallState.Installing,
                    onClick = { scope.launch { ConnectClient.install(context) } },
                )
            }

            // Avancement.
            if (ui.phase != SyncPhase.IDLE) {
                SectionTitle("Avancement")
                ChampiCard(Modifier.fillMaxWidth()) { SyncProgressBlock(ui) }
            }

            // Explications.
            SectionTitle("Comment ça marche")
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText("Champimap n'a pas accès à Internet, et c'est voulu : rien ne peut en sortir."),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                VSpace(6.dp)
                Text(
                    champiText(
                        "Champimap Connect est un petit module séparé. Il reçoit seulement la liste de tes départements " +
                            "et des zones météo larges (carrés d'environ 25 km), jamais la position de tes spots. " +
                            "Il télécharge les cartes, les urgences, la météo et la liste des centres antipoison, " +
                            "puis les remet à Champimap.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                VSpace(10.dp)
                NumberedStep(1, "Android te demande d'autoriser Champimap à installer des applis (uniquement pour poser le module).")
                NumberedStep(2, "Tu valides l'installation du module.")
                NumberedStep(3, "C'est fini. Tu peux retirer l'autorisation ou désinstaller le module quand tu veux.")
            }
            VSpace(24.dp)
        }
    }
}
