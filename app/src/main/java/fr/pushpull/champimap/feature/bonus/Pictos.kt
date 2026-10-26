package fr.pushpull.champimap.feature.bonus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Pictos du module bonus (débloqués par l'easter egg) : 6 champignons, 8 arbres, 8 animaux.
 * Style plat, couleurs franches, même ombre au sol : cohérent avec ForestIllustration.
 * Chaque picto est dessiné dans un carré de 100 × 100 unités (fichiers PictosChampignons,
 * PictosArbres, PictosAnimaux) et reste lisible de 36 à 64 dp.
 */

/** Catalogue des clés de pictos et de leurs noms affichés. */
object Pictos {
    val MUSHROOMS: List<String> = listOf("cepe", "amanite", "girolle", "morille", "trompette", "coulemelle")
    val TREES: List<String> = listOf("chene", "chataignier", "hetre", "bouleau", "pin", "douglas", "charme", "frene")
    val ANIMALS: List<String> = listOf("ecureuil", "herisson", "rougegorge", "chevreuil", "renard", "sanglier", "chouette", "lapin")

    private val LABELS = mapOf(
        "cepe" to "Cèpe", "amanite" to "Amanite", "girolle" to "Girolle",
        "morille" to "Morille", "trompette" to "Trompette", "coulemelle" to "Coulemelle",
        "chene" to "Chêne", "chataignier" to "Châtaignier", "hetre" to "Hêtre", "bouleau" to "Bouleau",
        "pin" to "Pin", "douglas" to "Douglas", "charme" to "Charme", "frene" to "Frêne",
        "ecureuil" to "Écureuil", "herisson" to "Hérisson", "rougegorge" to "Rouge-gorge",
        "chevreuil" to "Chevreuil", "renard" to "Renard", "sanglier" to "Sanglier",
        "chouette" to "Chouette", "lapin" to "Lapin",
    )

    /** Nom affiché d'un picto (la clé elle-même si elle est inconnue). */
    fun label(key: String): String = LABELS[key] ?: key
}

/**
 * Dessine le picto [key]. Taille par défaut 48 dp si l'appelant n'en donne pas
 * (un `Modifier.size(...)` passé par l'appelant reste prioritaire).
 */
@Composable
fun Picto(key: String, modifier: Modifier = Modifier) {
    val label = Pictos.label(key)
    Canvas(modifier.size(48.dp).semantics { contentDescription = label }) {
        val side = size.minDimension
        inBox((size.width - side) / 2f, (size.height - side) / 2f, side) { drawPicto(key) }
    }
}

/** Dessine un picto dans le repère courant 100 × 100, avec ou sans ombre au sol. */
internal fun DrawScope.drawPicto(key: String, shadow: Boolean = true) {
    if (shadow) groundShadow()
    when (key) {
        // Champignons
        "cepe" -> drawCepe()
        "amanite" -> drawAmanite()
        "girolle" -> drawGirolle()
        "morille" -> drawMorille()
        "trompette" -> drawTrompette()
        "coulemelle" -> drawCoulemelle()
        // Arbres
        "chene" -> drawChene()
        "chataignier" -> drawChataignier()
        "hetre" -> drawHetre()
        "bouleau" -> drawBouleau()
        "pin" -> drawPin()
        "douglas" -> drawDouglas()
        "charme" -> drawCharme()
        "frene" -> drawFrene()
        // Animaux
        "ecureuil" -> drawEcureuil()
        "herisson" -> drawHerisson()
        "rougegorge" -> drawRougeGorge()
        "chevreuil" -> drawChevreuil()
        "renard" -> drawRenard()
        "sanglier" -> drawSanglier()
        "chouette" -> drawChouette()
        "lapin" -> drawLapin()
        // Clé inconnue : simple pastille neutre plutôt qu'un vide.
        else -> disc(Color(0xFF9AA39C), 50f, 50f, 22f)
    }
}

/**
 * Dessine un picto centré horizontalement sur [cx], posé sur la ligne [baseY],
 * dans un carré de [side] pixels (utilisé par l'illustration de forêt).
 */
internal fun DrawScope.drawPictoAt(key: String, cx: Float, baseY: Float, side: Float, shadow: Boolean = true) {
    inBox(cx - side / 2f, baseY - side * 0.95f, side) { drawPicto(key, shadow) }
}
