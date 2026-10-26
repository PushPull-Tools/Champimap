package fr.pushpull.champimap.feature.sortie

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « sortie ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun DepartScreen(nav: NavController) {
    ChampiScreen(title = "Démarrer une sortie", onBack = { nav.popBackStack() }) {}
}

@Composable
fun SortieScreen(nav: NavController, sortieId: String) {
    ChampiScreen(title = "Sortie en cours", onBack = { nav.popBackStack() }) {}
}

@Composable
fun FinSortieScreen(nav: NavController, sortieId: String) {
    ChampiScreen(title = "Sortie terminée", onBack = { nav.popBackStack() }) {}
}

@Composable
fun PerduScreen(nav: NavController, sortieId: String) {
    ChampiScreen(title = "Je suis perdu", onBack = { nav.popBackStack() }) {}
}

@Composable
fun CarteScreen(nav: NavController) {
    ChampiScreen(title = "Carte", onBack = { nav.popBackStack() }) {}
}
