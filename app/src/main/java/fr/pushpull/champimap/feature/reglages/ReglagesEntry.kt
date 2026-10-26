package fr.pushpull.champimap.feature.reglages

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « reglages ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun OnboardingScreen(nav: NavController) {
    ChampiScreen(title = "Bienvenue", onBack = { nav.popBackStack() }) {}
}

@Composable
fun HomeScreen(nav: NavController) {
    ChampiScreen(title = "Accueil", onBack = { nav.popBackStack() }) {}
}

@Composable
fun ParametresScreen(nav: NavController) {
    ChampiScreen(title = "Paramètres", onBack = { nav.popBackStack() }) {}
}

@Composable
fun PreuvesScreen(nav: NavController) {
    ChampiScreen(title = "Preuves « hors ligne »", onBack = { nav.popBackStack() }) {}
}

@Composable
fun JournalScreen(nav: NavController) {
    ChampiScreen(title = "Journal", onBack = { nav.popBackStack() }) {}
}

@Composable
fun SauvegardeScreen(nav: NavController) {
    ChampiScreen(title = "Sauvegarde et export", onBack = { nav.popBackStack() }) {}
}

@Composable
fun CorbeilleScreen(nav: NavController) {
    ChampiScreen(title = "Corbeille", onBack = { nav.popBackStack() }) {}
}

/** Écran de verrouillage (code ou empreinte) affiché avant l'appli si l'option est active. */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    content()
}
