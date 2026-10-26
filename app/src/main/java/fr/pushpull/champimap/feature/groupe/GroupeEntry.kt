package fr.pushpull.champimap.feature.groupe

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « groupe ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun GroupeCreerScreen(nav: NavController, sortieId: String) {
    ChampiScreen(title = "Sortie à plusieurs", onBack = { nav.popBackStack() }) {}
}

@Composable
fun GroupeRejoindreScreen(nav: NavController) {
    ChampiScreen(title = "Rejoindre", onBack = { nav.popBackStack() }) {}
}

@Composable
fun PartageSpotScreen(nav: NavController, spotId: String) {
    ChampiScreen(title = "Partager le spot", onBack = { nav.popBackStack() }) {}
}

@Composable
fun ImportPartageScreen(nav: NavController) {
    ChampiScreen(title = "Spot reçu", onBack = { nav.popBackStack() }) {}
}
