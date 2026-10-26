package fr.pushpull.champimap.feature.stats

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « stats ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun StatsScreen(nav: NavController) {
    ChampiScreen(title = "Statistiques", onBack = { nav.popBackStack() }) {}
}

@Composable
fun CalendrierScreen(nav: NavController) {
    ChampiScreen(title = "Calendrier", onBack = { nav.popBackStack() }) {}
}

@Composable
fun TresorsScreen(nav: NavController) {
    ChampiScreen(title = "Carte aux trésors", onBack = { nav.popBackStack() }) {}
}
