package fr.pushpull.champimap.feature.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import fr.pushpull.champimap.feature.reglages.SwitchRow
import fr.pushpull.champimap.feature.spot.IaAssets
import fr.pushpull.champimap.feature.spot.IaModel
import fr.pushpull.champimap.feature.spot.IaModelInfo
import fr.pushpull.champimap.feature.spot.IaStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Écrans Champimap Connect :
 *  - ConnectOnboardingStep : étape 3 du premier lancement (explication + installation du module) ;
 *  - ConnectDownloadStep   : étape 4 (préparation des cartes, avancement) ;
 *  - ConnectScreen         : écran des Paramètres (état, synchro, installation, modèle d'identification).
 * Le parcours d'installation (boutons, autorisation Android, réessayer / plus tard) est dans
 * ConnectInstallPanel.kt ; les écrans d'intoxication sont dans IntoxScreens.kt.
 * (« Étape x sur 4 » et la barre en 4 segments sont déjà dessinés par OnboardingScreen.)
 */

// ---------------------------------------------------------------------------------------------
// Étape 3 : explication et installation
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectOnboardingStep(onInstalled: () -> Unit, onLater: () -> Unit) {
    // Met à jour l'état du module au retour au premier plan (ne lance jamais d'installation).
    ConnectResumeEffect()

    // Texte lisible sur le fond dans tous les thèmes (sinon noir par défaut, illisible en sombre).
    ConnectContentColor {
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
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(16.dp)
            Text(
                champiText("Cette appli n'a pas accès à Internet, et c'est voulu !"),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            VSpace(6.dp)
            Text(
                champiText("Rien ne peut en sortir, tes données t'appartiennent."),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            VSpace(6.dp)
            Text(
                champiText(
                    "Les cartes, la météo et les mises à jour passent par un petit module séparé, " +
                        "qui ne connaît que des zones larges, jamais tes spots.",
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            VSpace(20.dp)
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText("Comment ça s'installe"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                VSpace(8.dp)
                NumberedStep(
                    1,
                    "Android va te demander d'autoriser Champimap à installer des applis. " +
                        "C'est seulement pour poser le module, sans donner Internet à Champimap.",
                )
                NumberedStep(2, "Tu valides l'installation du module.")
                NumberedStep(3, "C'est fini. Tu peux retirer l'autorisation ou désinstaller le module quand tu veux.")
            }
            VSpace(16.dp)
            // Parcours d'installation : ne démarre que sur « Installer le module ».
            ConnectInstallPanel(
                onInstalled = onInstalled,
                onLater = onLater,
                laterLabel = "Plus tard (sans carte ni météo)",
                showLaterWhenIdle = true,
                continueLabel = "Continuer",
            )
            VSpace(16.dp)
        }
    }
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
    ConnectContentColor {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                champiText("Préparation de tes cartes"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            VSpace(6.dp)
            Text(
                champiText(
                    "Champimap Connect télécharge les cartes de tes départements, les urgences, la météo des zones larges " +
                        "et le modèle d'identification par photo (≈ ${IaModel.APPROX_SIZE_MO} Mo).",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(4.dp)
            Text(
                champiText(WIFI_ADVICE),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
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
}

// ---------------------------------------------------------------------------------------------
// Écran des Paramètres
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectScreen(nav: NavController) {
    val context = LocalContext.current
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
    // Connect trop ancien pour le modèle d'identification (relu à chaque changement d'installation).
    val needsUpdate = remember(install, ui.phase) { installed && ConnectClient.needsUpdate(context) }
    val running = ui.phase == SyncPhase.RUNNING

    ChampiScreen(title = "Champimap Connect", onBack = { nav.popBackStack() }) {
        ConnectContentColor {
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
                        color = MaterialTheme.colorScheme.onSurface,
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
                }

                // Module trop ancien (avant le modèle d'identification) : mise à jour depuis l'APK incluse.
                if (needsUpdate) {
                    VSpace(12.dp)
                    ConnectUpdateCard(install)
                }

                // Actions.
                VSpace(12.dp)
                if (installed) {
                    ChampiButton(if (running) "Synchro en cours…" else "Lancer la synchro", onClick = startSync, enabled = !running)
                    VSpace(8.dp)
                    ChampiOutlinedButton("Actualiser les cartes", onClick = refreshAll, enabled = !running)
                    VSpace(4.dp)
                    Text(
                        champiText(
                            "« Lancer la synchro » ne télécharge que ce qui manque (nouveaux départements, météo, " +
                                "nouvelle version du modèle). « Actualiser les cartes » retélécharge tout, cartes et modèle compris.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(4.dp)
                    Text(
                        champiText(WIFI_ADVICE),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Même parcours qu'au premier lancement : rien ne démarre sans « Installer le module »,
                    // « Plus tard » abandonne la tentative et laisse l'utilisateur sur cet écran.
                    ConnectInstallPanel(
                        onInstalled = {},
                        onLater = {},
                        laterLabel = "Plus tard",
                        showLaterWhenIdle = false,
                        continueLabel = null,
                    )
                }

                // Modèle d'identification par photo.
                SectionTitle("Identification par photo")
                IaModelCard(installed = installed, running = running)

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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    VSpace(6.dp)
                    Text(
                        champiText(
                            "Champimap Connect est un petit module séparé. Il reçoit seulement la liste de tes départements " +
                                "et des zones météo larges (carrés d'environ 25 km), jamais la position de tes spots. " +
                                "Il télécharge les cartes, les urgences, la météo, la liste des centres antipoison " +
                                "et le modèle d'identification par photo, puis les remet à Champimap.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
}

// ---------------------------------------------------------------------------------------------
// Mise à jour du module (écran des Paramètres)
// ---------------------------------------------------------------------------------------------

/** Carte « Mettre à jour le module » : l'APK incluse dans Champimap remplace l'ancien Connect. */
@Composable
private fun ConnectUpdateCard(install: InstallState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(
            champiText("Mise à jour du module nécessaire"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            champiText(
                "Ton module Champimap Connect est ancien : il ne gère pas encore tout le modèle d'identification " +
                    "(téléchargement, liste des versions).",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(8.dp)
        when (install) {
            is InstallState.Installing -> Text(champiText("Mise à jour en cours…"), color = MaterialTheme.colorScheme.onSurface)
            is InstallState.NeedsPermission -> {
                Text(
                    champiText("Autorise d'abord Champimap à installer des applis (uniquement pour poser le module)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                VSpace(6.dp)
                ChampiOutlinedButton("Ouvrir le réglage Android", onClick = {
                    runCatching { context.startActivity(ConnectClient.unknownSourcesIntent(context)) }
                })
            }
            is InstallState.Failed -> Text(champiText(install.message), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        VSpace(8.dp)
        ChampiButton(
            "Mettre à jour le module",
            onClick = { scope.launch { ConnectClient.install(context) } },
            enabled = install !is InstallState.Installing,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Modèle d'identification (écran des Paramètres)
// ---------------------------------------------------------------------------------------------

/** Statistiques affichées : modèle actif et son origine. */
private class IaScreenStats(val current: IaStats?, val fromConnect: Boolean, val fromApk: Boolean)

/** Entrée « La plus récente (automatique) » du menu des versions. */
private const val LATEST_LABEL = "La plus récente (automatique)"

/**
 * Carte « Identification par photo » : interrupteur (inclus dans la synchro), état du modèle installé avec ses
 * statistiques, message si le dernier modèle reçu a été refusé, menu déroulant des versions (la plus récente ou
 * une version précise, liste reçue de Connect) et « Télécharger cette version ».
 * Pas de version précédente durable : l'ancien modèle ne sert de secours que pendant la bascule.
 * @param installed vrai si Champimap Connect est installé
 * @param running vrai pendant une synchro (boutons de synchro désactivés)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IaModelCard(installed: Boolean, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by remember { IaModel.flow(context) }.collectAsState(initial = IaModelInfo())
    val revision by IaAssets.revision.collectAsState()
    val versions by IaVersions.list.collectAsState()
    // Modèle seul (version fixée, sinon la plus récente) et liste des versions seule.
    val downloadVersion = rememberConnectSync(iaOnly = true)
    val refreshList = rememberConnectSync(iaListOnly = true)
    var menuOpen by remember { mutableStateOf(false) }

    // Liste des versions gardée sur le téléphone (lue une fois).
    LaunchedEffect(Unit) { IaVersions.load(context) }

    // Statistiques lues hors du fil principal, relues à chaque changement de modèle.
    val stats by produceState<IaScreenStats?>(initialValue = null, revision, info.version) {
        value = withContext(Dispatchers.IO) {
            IaScreenStats(
                current = IaAssets.stats(context, info.version),
                fromConnect = IaAssets.isFromConnect(context),
                fromApk = IaAssets.modelAvailable(context) && !IaAssets.isFromConnect(context),
            )
        }
    }

    ChampiCard(Modifier.fillMaxWidth()) {
        SwitchRow(
            title = "Identification par photo (≈ ${IaModel.APPROX_SIZE_MO} Mo, Wi-Fi conseillé)",
            explanation = "Incluse dans la synchro : Champimap Connect télécharge le modèle, puis ses nouvelles versions.",
            checked = info.enabled,
            onChange = { on -> scope.launch { IaModel.setEnabled(context, on) } },
        )
        VSpace(8.dp)

        // État du modèle actif.
        val s = stats
        val species = info.speciesCount.takeIf { it > 0 } ?: s?.current?.especes ?: 0
        val status = when {
            s == null -> "Lecture du modèle…"
            s.fromConnect && info.version != null -> "Modèle ${info.version} installé · ${formatCount(species)} espèces"
            s.fromConnect -> "Modèle installé · ${formatCount(species)} espèces"
            s.fromApk -> "Modèle intégré à l'appli (ancienne version) · ${formatCount(species)} espèces"
            else -> "Pas encore téléchargé : lance la synchro pour l'installer."
        }
        Text(
            champiText(status),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (s?.current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        s?.current?.summary()?.takeIf { it.isNotBlank() }?.let {
            Text(champiText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Dernier modèle reçu refusé (test de chargement en échec) : message clair, effaçable.
        info.failedVersion?.let { failed ->
            VSpace(8.dp)
            Text(
                champiText(
                    if (info.failedKeptOld) "Le nouveau modèle n'a pas pu être chargé : l'ancien est conservé ($failed refusé)."
                    else "Le nouveau modèle n'a pas pu être chargé ($failed refusé) : relance la synchro plus tard.",
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { scope.launch { IaModel.clearFailure(context) } }) { Text(champiText("OK")) }
        }

        // Choix de la version : menu déroulant (liste reçue de Connect), téléchargée par Connect.
        if (installed) {
            VSpace(12.dp)
            val known = versions.orEmpty()
            val pinned = info.pinnedVersion
            // Texte de l'entrée choisie : statistiques si la version est connue, sinon son nom seul.
            val selectedLabel = pinned?.let { p -> known.firstOrNull { it.version == p }?.summary() ?: p } ?: LATEST_LABEL
            ExposedDropdownMenuBox(
                expanded = menuOpen,
                onExpandedChange = { if (!running) menuOpen = it },
            ) {
                OutlinedTextField(
                    value = champiText(selectedLabel),
                    onValueChange = {},
                    readOnly = true,
                    enabled = !running,
                    singleLine = true,
                    label = { Text(champiText("Version du modèle")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Suivi automatique de la plus récente.
                    DropdownMenuItem(
                        text = { Text(champiText(LATEST_LABEL), fontWeight = if (pinned == null) FontWeight.Bold else null) },
                        onClick = {
                            menuOpen = false
                            scope.launch { IaModel.unpin(context) }
                        },
                    )
                    // Versions publiées connues (plus récente d'abord), avec leurs statistiques.
                    known.forEach { v ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    champiText(v.summary() + if (v.version == info.version) " · installée" else ""),
                                    fontWeight = if (v.version == pinned) FontWeight.Bold else null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                scope.launch { IaModel.pin(context, v.version) }
                            },
                        )
                    }
                    // Version fixée absente de la liste (liste pas encore reçue) : affichée quand même.
                    if (pinned != null && known.none { it.version == pinned }) {
                        DropdownMenuItem(
                            text = { Text(champiText(pinned), fontWeight = FontWeight.Bold) },
                            onClick = { menuOpen = false },
                        )
                    }
                    // Récupère (ou met à jour) la liste des versions auprès de Connect.
                    DropdownMenuItem(
                        text = { Text(champiText("Actualiser la liste (via Connect)"), color = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            menuOpen = false
                            refreshList()
                        },
                    )
                }
            }
            VSpace(4.dp)
            Text(
                champiText(
                    when {
                        known.isEmpty() -> "Liste des versions pas encore reçue : choisis « Actualiser la liste (via Connect) »."
                        pinned != null && pinned != info.version -> "$pinned sera téléchargée à la prochaine synchro (version fixée)."
                        pinned != null -> "Version fixée : la synchro ne la remplace pas par une plus récente."
                        else -> "La synchro installe automatiquement chaque nouvelle version."
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            VSpace(8.dp)
            ChampiOutlinedButton("Télécharger cette version", onClick = downloadVersion, enabled = !running)
            VSpace(4.dp)
            Text(
                champiText("Les versions se téléchargent avec Champimap Connect ; l'appli reste hors ligne."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** « 1 032 ». */
private fun formatCount(n: Int): String =
    String.format(Locale.FRANCE, "%,d", n).replace('\u202F', ' ').replace('\u00A0', ' ')

