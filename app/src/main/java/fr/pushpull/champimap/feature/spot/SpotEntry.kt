package fr.pushpull.champimap.feature.spot

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « spot ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun SpotScreen(nav: NavController, spotId: String, sortieId: String?) {
    ChampiScreen(title = "Spot", onBack = { nav.popBackStack() }) {}
}

@Composable
fun EspecesPickerScreen(nav: NavController, recolteId: String) {
    ChampiScreen(title = "Complète ta cueillette", onBack = { nav.popBackStack() }) {}
}

@Composable
fun CatalogueScreen(nav: NavController) {
    ChampiScreen(title = "Espèces", onBack = { nav.popBackStack() }) {}
}

@Composable
fun FicheEspeceScreen(nav: NavController, speciesId: String) {
    ChampiScreen(title = "Espèce", onBack = { nav.popBackStack() }) {}
}

/** Charge la liste des espèces (assets/species.json) dans la base au premier lancement. */
object SpeciesLoader {
    suspend fun ensureLoaded(context: android.content.Context) { /* implémenté par le module spot */ }
}
