package fr.pushpull.champimap.feature.reglages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.settings.AppSettings
import fr.pushpull.champimap.core.settings.MessageTone
import fr.pushpull.champimap.core.settings.ThemeMode
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.carnet.CarnetTextes
import fr.pushpull.champimap.feature.connect.ConnectClient
import fr.pushpull.champimap.feature.connect.rememberConnectSync
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Paramètres (onglet « Plus ») : sections repliables + / − mémorisées
 * (Profil, Affichage, Sécurité et alertes, Données), chaque option avec son explication,
 * et le crédit en bas de page. Enregistrement immédiat (prénom : ~300 ms après la frappe).
 */

/** Clés des sections repliables (stockées dans `collapsedSections`). */
private object Sections {
    const val PROFIL = "profil"
    const val AFFICHAGE = "affichage"
    const val SECURITE = "securite"
    const val DONNEES = "donnees"
}

/** Paramètres. Signature appelée par AppNav (onglet du bas, pas de bouton Retour). */
@Composable
fun ParametresScreen(nav: NavController) {
    val settingsState by AppGraph.settings.flow.collectAsState(initial = null)
    val s = settingsState ?: return
    val scope = AppGraph.appScope

    /** Replie / déplie une section et mémorise l'état. */
    fun toggle(key: String) {
        val next = if (key in s.collapsedSections) s.collapsedSections - key else s.collapsedSections + key
        scope.launch { AppGraph.settings.setCollapsedSections(next) }
    }

    ChampiScreen(title = "Paramètres") {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollapsibleSection("Profil", Sections.PROFIL in s.collapsedSections, { toggle(Sections.PROFIL) }) {
                ProfilSection(s, onOpenConnect = { nav.navigate(Routes.CONNECT) })
                // Carnet : lien vers « Mon carnet » (niveau, titre, succès).
                LinkRow(CarnetTextes.LIEN_PARAMETRES_TITRE, CarnetTextes.LIEN_PARAMETRES_EXPLICATION) { nav.navigate(Routes.CARNET) }
            }
            CollapsibleSection("Affichage", Sections.AFFICHAGE in s.collapsedSections, { toggle(Sections.AFFICHAGE) }) {
                AffichageSection(s)
            }
            CollapsibleSection("Sécurité et alertes", Sections.SECURITE in s.collapsedSections, { toggle(Sections.SECURITE) }) {
                SecuriteSection(s)
                BattueEditor()
            }
            CollapsibleSection("Données", Sections.DONNEES in s.collapsedSections, { toggle(Sections.DONNEES) }) {
                LinkRow("Champimap Connect", "Cartes, météo, urgences et modèle d'identification : état du module et synchronisation.") { nav.navigate(Routes.CONNECT) }
                LinkRow("Sauvegarde et export", "Copie complète (chiffrée ou non), restauration, export CSV et corbeille.") { nav.navigate(Routes.SAUVEGARDE) }
                LinkRow("Journal", "Uniquement ce qui n'a pas fonctionné, sans aucune position ni nom de spot.") { nav.navigate(Routes.JOURNAL) }
                LinkRow("Preuves « hors ligne »", "Les autorisations réellement accordées, lues en direct sur le téléphone.") { nav.navigate(Routes.PREUVES) }
                LinkRow("Crédits des photos et du modèle", "Modèle d'identification, photos iNaturalist et liste complète de leurs auteurs.") { nav.navigate(Routes.CREDITS_IA) }
                // Mode vendeur (expérimental) : saisie des ventes (fin de sortie, Stats → Mes ventes).
                SwitchRow(
                    "Mode vendeur (expérimental)",
                    "Fonction en test : elle pourra être modifiée ou supprimée à la sortie officielle de l'appli.",
                    s.sellerMode,
                ) { on -> scope.launch { AppGraph.settings.setSellerMode(on) } }
            }
            Credit()
        }
    }
}

/**
 * Profil : prénom (enregistré après la frappe) et départements.
 * @param onOpenConnect ouvre l'écran Champimap Connect (module pas encore installé).
 */
@Composable
private fun ProfilSection(s: AppSettings, onOpenConnect: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(s.firstName) }
    var editDeps by remember { mutableStateOf(false) }
    // Départements nouvellement cochés dont la carte manque → fenêtre « Nouvelle carte à télécharger ».
    var newMaps by remember { mutableStateOf(emptyList<String>()) }
    // Synchro normale : Connect ne télécharge que les nouveautés (cartes existantes conservées).
    val syncNow = rememberConnectSync()
    var saved by remember { mutableStateOf(false) }

    // Enregistrement automatique ~300 ms après la dernière frappe (prénom non vide uniquement).
    LaunchedEffect(name) {
        if (name.isBlank() || name.trim() == s.firstName) return@LaunchedEffect
        delay(300)
        runCatching {
            AppGraph.settings.setFirstName(name)
            val dao = AppGraph.db.personDao()
            dao.me()?.let { dao.upsert(it.copy(name = name.trim(), updatedAt = System.currentTimeMillis())) }
        }.onFailure { ErrorLogger.log("Paramètres", "Prénom non enregistré", it) }
        saved = true
        delay(1500)
        saved = false
    }

    Text(champiText("Prénom"), style = MaterialTheme.typography.titleMedium)
    Text(champiText("Affiché sur l'accueil et partagé lors des sorties à plusieurs."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        isError = name.isBlank(),
        supportingText = {
            when {
                name.isBlank() -> Text("Le prénom ne peut pas être vide")
                saved -> Text("✓ Enregistré")
                else -> Unit
            }
        },
    )
    LinkRow(
        "Départements",
        if (s.departements.isEmpty()) "Aucun département coché."
        else s.departements.mapNotNull { Departements.get(it)?.libelle }.joinToString(", ") +
            " · cartes ≈ ${Departements.tailleTotaleMo(s.departements)} Mo",
    ) { editDeps = true }

    if (editDeps) {
        DepartementsDialog(
            initial = s.departements.toSet(),
            onValidated = { added -> newMaps = added.filter { !ConnectClient.hasMap(context, it) } },
            onClose = { editDeps = false },
        )
    }
    if (newMaps.isNotEmpty()) {
        NouvelleCarteDialog(
            codes = newMaps,
            onSyncNow = {
                newMaps = emptyList()
                // Module absent : on ouvre l'écran Connect, qui propose de l'installer.
                if (ConnectClient.isInstalled(context)) syncNow() else onOpenConnect()
            },
            onLater = { newMaps = emptyList() },
        )
    }
}

/**
 * Fenêtre plein écran de choix des départements (au moins un).
 * @param onValidated départements ajoutés par rapport à [initial] (appelé à la validation).
 */
@Composable
private fun DepartementsDialog(initial: Set<String>, onValidated: (List<String>) -> Unit, onClose: () -> Unit) {
    var sel by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ChampiCard(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(12.dp)) {
            Text(champiText("Mes départements"), style = MaterialTheme.typography.headlineSmall)
            Text(
                champiText("Après un changement, lance la synchro Champimap Connect pour télécharger les nouvelles cartes."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8.dp)
            DepartementPicker(sel, { sel = it }, Modifier.weight(1f))
            VSpace(8.dp)
            ChampiButton("Valider", enabled = sel.isNotEmpty(), onClick = {
                AppGraph.appScope.launch { AppGraph.settings.setDepartements(sel.sorted()) }
                onValidated((sel - initial).sorted())
                onClose()
            })
        }
    }
}

/** Affichage : thème, émojis, « champignong », lien intoxication. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AffichageSection(s: AppSettings) {
    val scope = AppGraph.appScope
    Text(champiText("Thème"), style = MaterialTheme.typography.titleMedium)
    Text(
        champiText("Auto suit le réglage du téléphone. Le thème sombre est plus doux le soir en forêt."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // « Psyché » n'apparaît qu'une fois découvert (le mode party lui-même n'est jamais listé).
    val modes = buildList {
        add(ThemeMode.AUTO to "Auto"); add(ThemeMode.CLAIR to "Clair"); add(ThemeMode.SOMBRE to "Sombre")
        if (s.psycheUnlocked) add(ThemeMode.PSYCHE to "Psyché")
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, label) ->
            FilterChip(
                selected = s.themeMode == mode,
                onClick = { scope.launch { AppGraph.settings.setThemeMode(mode) } },
                label = { Text(champiText(label)) },
            )
        }
    }
    SwitchRow("Affichage avec émojis", "Ajoute un petit émoji à côté des textes (ex. « Trouvé ! 🍄 »).", s.emojis) {
        scope.launch { AppGraph.settings.setEmojis(it) }
    }
    SwitchRow("Tu dis le g toi ?", "Pour celles et ceux qui disent « champignong ».", s.champignong) {
        scope.launch { AppGraph.settings.setChampignong(it) }
    }
    // Carnet : ton des messages du carnet (Normal / Sarcastique).
    Text(champiText(CarnetTextes.REGLAGE_TON_TITRE), style = MaterialTheme.typography.titleMedium)
    Text(
        champiText(CarnetTextes.REGLAGE_TON_EXPLICATION),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(MessageTone.NORMAL to CarnetTextes.TON_NORMAL, MessageTone.SARCASTIQUE to CarnetTextes.TON_SARCASTIQUE).forEach { (tone, label) ->
            FilterChip(
                selected = s.messageTone == tone,
                onClick = { scope.launch { AppGraph.settings.setMessageTone(tone) } },
                label = { Text(champiText(label)) },
            )
        }
    }
    SwitchRow("Lien « En cas d'intoxication »", "Affiche le lien discret en fin de sortie. La fiche reste accessible ici.", s.intoxLinkVisible) {
        scope.launch { AppGraph.settings.setIntoxLinkVisible(it) }
    }
}

/** Sécurité et alertes : verrouillage, mode discret, alertes de saison. */
@Composable
private fun SecuriteSection(s: AppSettings) {
    val context = LocalContext.current
    val scope = AppGraph.appScope
    var lockError by remember { mutableStateOf<String?>(null) }

    SwitchRow(
        "Verrouillage code ou empreinte",
        "Demande ton empreinte ou le code du téléphone à l'ouverture et après 1 min en arrière-plan.",
        s.lockEnabled,
    ) { on ->
        // On n'active que si le téléphone sait vraiment déverrouiller (sinon on resterait bloqué dehors).
        val problem = if (on) LockHelper.unavailableReason(context) else null
        if (problem != null) {
            lockError = problem
        } else {
            scope.launch { AppGraph.settings.setLockEnabled(on) }
        }
    }
    SwitchRow("Mode discret", "Cache l'appli dans l'aperçu des applis récentes et bloque les captures d'écran.", s.discreetMode) {
        scope.launch { AppGraph.settings.setDiscreetMode(it) }
    }
    SwitchRow("Alertes de saison", "Une vérification par jour : « Ton spot donnait bien à cette période ».", s.seasonAlerts) {
        scope.launch { AppGraph.settings.setSeasonAlerts(it) }
    }
    lockError?.let { msg ->
        AlertDialog(
            onDismissRequest = { lockError = null },
            title = { Text("Verrouillage impossible", textAlign = TextAlign.Center) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { lockError = null }) { Text("OK") } },
        )
    }
}

/** Bas de page : logo + crédit. */
@Composable
private fun Credit() {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ChampiLogo(44.dp)
        VSpace(6.dp)
        Text(
            champiText("Développé en Limousin par PushPull-Tools"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
