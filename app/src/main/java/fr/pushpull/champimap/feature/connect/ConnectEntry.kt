package fr.pushpull.champimap.feature.connect

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « connect ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun IntoxScreen(nav: NavController, sortieId: String?) {
    ChampiScreen(title = "En cas d'intoxication", onBack = { nav.popBackStack() }) {}
}

@Composable
fun IntoxListScreen(nav: NavController) {
    ChampiScreen(title = "Intoxications", onBack = { nav.popBackStack() }) {}
}

@Composable
fun ConnectScreen(nav: NavController) {
    ChampiScreen(title = "Champimap Connect", onBack = { nav.popBackStack() }) {}
}
