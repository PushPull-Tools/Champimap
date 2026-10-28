package fr.pushpull.champimap.feature.reglages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.newId
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.connect.ConnectDownloadStep
import fr.pushpull.champimap.feature.connect.ConnectOnboardingStep
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/*
 * Premier lancement, en 4 étapes :
 *  1. Bienvenue + prénom (crée la personne « moi ») ;
 *  2. Départements (au moins un) ;
 *  3. Champimap Connect (écran fourni par le module connect ; « Plus tard » → fenêtre explicative) ;
 *  4. Téléchargement (écran fourni par le module connect).
 * À la fin : onboardingDone = true, puis Accueil en vidant la pile.
 */

/** Premier lancement. Signature appelée par AppNav. */
@Composable
fun OnboardingScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(1) }
    var firstName by rememberSaveable { mutableStateOf("") }
    // ArrayList : type sauvegardable tel quel par rememberSaveable.
    var departements by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showLaterDialog by rememberSaveable { mutableStateOf(false) }

    // Retour système : revient à l'étape précédente plutôt que de quitter.
    BackHandler(enabled = step > 1) { step -= 1 }

    /** Termine le premier lancement puis va à l'Accueil (qui ouvrira Connect si demandé). */
    fun finish(openConnect: Boolean) {
        if (openConnect) HomeLogic.requestOpenConnect(context)
        scope.launch {
            runCatching { AppGraph.settings.setOnboardingDone(true) }
                .onFailure { ErrorLogger.log("Premier lancement", "Fin du premier lancement non enregistrée", it) }
            nav.navigate(Routes.HOME) {
                popUpTo(Routes.ONBOARDING) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val back: (() -> Unit)? = if (step > 1) ({ step -= 1 }) else null
    ChampiScreen(onBack = back) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (back == null) VSpace(16.dp)
            // Indicateur « Étape x sur 4 » + barre en 4 segments.
            Text(champiText("Étape $step sur 4"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StepBar(step)
            VSpace(16.dp)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    1 -> WelcomeStep(
                        firstName = firstName,
                        onNameChange = { firstName = it },
                        onContinue = {
                            scope.launch {
                                saveMe(firstName)
                                step = 2
                            }
                        },
                    )
                    2 -> DepartementsStep(
                        selected = departements.toSet(),
                        onChange = { departements = ArrayList(it.sorted()) },
                        onContinue = {
                            scope.launch {
                                runCatching { AppGraph.settings.setDepartements(departements) }
                                    .onFailure { ErrorLogger.log("Premier lancement", "Départements non enregistrés", it) }
                                step = 3
                            }
                        },
                    )
                    3 -> ConnectOnboardingStep(
                        onInstalled = { step = 4 },
                        onLater = { showLaterDialog = true },
                    )
                    else -> ConnectDownloadStep(onContinue = { finish(openConnect = false) })
                }
            }
        }
    }

    if (showLaterDialog) {
        LaterDialog(
            onOpenSettings = { showLaterDialog = false; finish(openConnect = true) },
            onContinue = { showLaterDialog = false; finish(openConnect = false) },
            onDismiss = { showLaterDialog = false },
        )
    }
}

/** Enregistre le prénom dans les réglages et crée (ou renomme) la personne « moi ». */
private suspend fun saveMe(name: String) {
    val clean = name.trim()
    runCatching {
        AppGraph.settings.setFirstName(clean)
        withContext(Dispatchers.IO) {
            val dao = AppGraph.db.personDao()
            val existing = dao.me()
            dao.upsert(
                existing?.copy(name = clean, updatedAt = System.currentTimeMillis())
                    ?: Person(id = newId(), name = clean, isMe = true),
            )
        }
    }.onFailure { ErrorLogger.log("Premier lancement", "Prénom non enregistré", it) }
}

/** Barre de progression en 4 segments. */
@Composable
private fun StepBar(step: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(
                        if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

/** Étape 1 : logo, textes validés et prénom. */
@Composable
private fun WelcomeStep(firstName: String, onNameChange: (String) -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VSpace(24.dp)
        ChampiLogo(96.dp)
        VSpace(16.dp)
        Text(champiText("Bienvenue dans Champimap"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        VSpace(12.dp)
        Text(
            champiText("Tes coins à champignons, tes récoltes, tes stats. Tout reste sur ton téléphone."),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        VSpace(8.dp)
        Text(
            champiText("Champimap n'a pas accès à Internet."),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        VSpace(28.dp)
        OutlinedTextField(
            value = firstName,
            onValueChange = { onNameChange(it.take(40)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text("Entre ton prénom") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        VSpace(20.dp)
        ChampiButton("Continuer", onClick = onContinue, enabled = firstName.isNotBlank())
        VSpace(16.dp)
    }
}

/** Étape 2 : départements à cocher (au moins un). */
@Composable
private fun DepartementsStep(selected: Set<String>, onChange: (Set<String>) -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(champiText("Où vas-tu aux champignons ?"), style = MaterialTheme.typography.headlineSmall)
        Text(
            champiText("Coche tes départements : ils servent aux cartes hors ligne, à la météo et aux urgences."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(12.dp)
        DepartementPicker(selected, onChange, Modifier.weight(1f))
        VSpace(8.dp)
        ChampiButton("Continuer", onClick = onContinue, enabled = selected.isNotEmpty())
        VSpace(12.dp)
    }
}

/** Fenêtre « Plus tard » : ce qui manque sans Champimap Connect, ce qui marche quand même. */
@Composable
private fun LaterDialog(onOpenSettings: () -> Unit, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(champiText("Sans Champimap Connect"), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(champiText("Ce qui manque"), style = MaterialTheme.typography.titleMedium)
                Text(champiText("• Pas de fond de carte : une grille avec tes spots, ta trace et la flèche vers la voiture."))
                Text(champiText("• Pas de météo (ni corrélations météo et récoltes)."))
                Text(champiText("• Urgences moins à jour (hôpitaux et centres antipoison intégrés à l'appli)."))
                VSpace(4.dp)
                Text(champiText("Ce qui marche"), style = MaterialTheme.typography.titleMedium)
                Text(champiText("• Spots, récoltes, photos, sorties avec trace GPS et retour à la voiture."))
                Text(champiText("• Statistiques, calendrier, sortie à plusieurs et partage."))
                Text(champiText("• Coucher du soleil, lune et période de chasse."))
                VSpace(4.dp)
                Text(champiText("Tu pourras installer Champimap Connect plus tard dans les paramètres."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(champiText("Continuer")) } },
        dismissButton = { TextButton(onClick = onOpenSettings) { Text(champiText("Ouvrir les paramètres")) } },
    )
}
