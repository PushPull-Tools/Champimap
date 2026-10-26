package fr.pushpull.champimap.feature.bonus

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiScreen

/*
 * SOCLE : points d'entrée des écrans du module « bonus ».
 * Le module remplace ces écrans provisoires en gardant EXACTEMENT les mêmes signatures
 * (elles sont appelées par nav/AppNav.kt).
 */

@Composable
fun AvatarScreen(nav: NavController) {
    ChampiScreen(title = "Oh, tu m'as trouvé !", onBack = { nav.popBackStack() }) {}
}

/** Superposition qui détecte les appuis répétés dans le vide (6, 12, 24) et lance les pluies cachées. */
@Composable
fun HiddenRainsOverlay(content: @Composable () -> Unit) {
    content()
}

/** Émoji placé devant un libellé quand l'affichage avec émojis est actif (vide sinon). */
@Composable
fun emojiFor(label: String): String = ""
