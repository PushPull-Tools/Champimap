package fr.pushpull.champimap.feature.bonus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/*
 * Les 8 arbres débloqués par l'easter egg. Repère 100 × 100, sol vers y = 92.
 * Chaque essence a une silhouette distincte pour rester reconnaissable en petit.
 */

private val TRONC = Color(0xFF6B4A2E)

/** Chêne : tronc trapu, houppier large en nuages. */
internal fun DrawScope.drawChene() {
    poly(TRONC, 43f, 92f, 57f, 92f, 54f, 55f, 46f, 55f)
    stroke(TRONC, 50f, 62f, 36f, 48f, 4f) // branches maîtresses
    stroke(TRONC, 50f, 62f, 64f, 48f, 4f)
    val vert = Color(0xFF4F7A3A)
    disc(vert, 32f, 48f, 16f)
    disc(vert, 68f, 48f, 16f)
    disc(vert, 50f, 38f, 22f)
    disc(vert, 37f, 27f, 13f)
    disc(vert, 63f, 27f, 13f)
    val clair = Color(0xFF6A9A4A)
    disc(clair, 42f, 29f, 6f)
    disc(clair, 62f, 40f, 5f)
    disc(clair, 30f, 45f, 4f)
    // Quelques glands
    disc(Color(0xFFA8782E), 40f, 55f, 2.2f)
    disc(Color(0xFFA8782E), 61f, 54f, 2.2f)
}

/** Châtaignier : tronc vrillé, houppier dense, bogues jaune-vert. */
internal fun DrawScope.drawChataignier() {
    poly(TRONC, 44f, 92f, 56f, 92f, 54f, 58f, 46f, 58f)
    val vrille = Color(0xFF543820)
    stroke(vrille, 45f, 86f, 54f, 76f, 1.5f)
    stroke(vrille, 45f, 76f, 54f, 66f, 1.5f)
    stroke(vrille, 46f, 67f, 53f, 60f, 1.5f)
    val vert = Color(0xFF5E8A2E)
    disc(vert, 36f, 48f, 15f)
    disc(vert, 64f, 48f, 15f)
    disc(vert, 50f, 33f, 22f)
    disc(Color(0xFF4C7424), 50f, 50f, 14f)
    // Bogues
    val bogue = Color(0xFFC4D04A)
    disc(bogue, 38f, 36f, 3.5f)
    disc(bogue, 60f, 28f, 3.5f)
    disc(bogue, 64f, 48f, 3.5f)
    disc(bogue, 44f, 52f, 3f)
}

/** Hêtre (cuivré) : tronc lisse gris, grand houppier ovale pourpre-cuivré. */
internal fun DrawScope.drawHetre() {
    poly(Color(0xFF9A9A92), 46f, 92f, 54f, 92f, 53f, 45f, 47f, 45f)
    oval(Color(0xFFA0522D), 50f, 36f, 32f, 26f)
    oval(Color(0xFF7E3B22), 50f, 48f, 26f, 12f)
    oval(Color(0xFFC0703F), 40f, 26f, 10f, 6f) // reflet
}

/** Bouleau : tronc blanc marqué de noir, houppier léger vert tendre. */
internal fun DrawScope.drawBouleau() {
    val vert = Color(0xFF9CC45A)
    oval(vert, 50f, 32f, 18f, 26f)
    oval(vert, 36f, 46f, 10f, 12f)
    oval(vert, 64f, 44f, 10f, 12f)
    oval(Color(0xFF86AE48), 50f, 44f, 12f, 12f)
    poly(Color(0xFFF2F0EA), 47f, 92f, 53f, 92f, 52f, 40f, 48f, 40f)
    val noir = Color(0xFF2A2A2A)
    stroke(noir, 47.5f, 84f, 50f, 84f, 1.6f)
    stroke(noir, 50.5f, 74f, 52.5f, 74f, 1.6f)
    stroke(noir, 47.5f, 64f, 50f, 64f, 1.6f)
    stroke(noir, 50.5f, 54f, 52f, 54f, 1.6f)
}

/** Pin sylvestre : haut tronc orangé, houppier en ombrelle au sommet. */
internal fun DrawScope.drawPin() {
    poly(Color(0xFFB0663A), 47f, 92f, 53f, 92f, 51f, 24f, 49f, 24f)
    stroke(Color(0xFFB0663A), 50f, 38f, 38f, 32f, 2f)
    stroke(Color(0xFFB0663A), 50f, 34f, 64f, 30f, 2f)
    val vert = Color(0xFF2F5A3A)
    oval(vert, 38f, 32f, 14f, 7f)
    oval(vert, 64f, 30f, 14f, 7f)
    oval(vert, 50f, 22f, 24f, 10f)
    oval(Color(0xFF3F7050), 46f, 19f, 12f, 4f)
}

/** Douglas : sapin conique sombre en étages. */
internal fun DrawScope.drawDouglas() {
    poly(TRONC, 47f, 92f, 53f, 92f, 52f, 80f, 48f, 80f)
    val sombre = Color(0xFF2E5240)
    val clair = Color(0xFF3E6A52)
    poly(sombre, 50f, 40f, 18f, 82f, 82f, 82f)
    poly(clair, 50f, 40f, 50f, 82f, 82f, 82f)
    poly(sombre, 50f, 22f, 24f, 60f, 76f, 60f)
    poly(clair, 50f, 22f, 50f, 60f, 76f, 60f)
    poly(sombre, 50f, 7f, 30f, 40f, 70f, 40f)
    poly(clair, 50f, 7f, 50f, 40f, 70f, 40f)
}

/** Charme : tronc gris cannelé, houppier ovale, grappes de fruits ailés. */
internal fun DrawScope.drawCharme() {
    poly(Color(0xFF8E8F86), 46f, 92f, 54f, 92f, 53f, 52f, 47f, 52f)
    stroke(Color(0xFF74756D), 49f, 90f, 49.5f, 56f, 1.2f) // cannelures
    stroke(Color(0xFF74756D), 51.5f, 90f, 51f, 56f, 1.2f)
    oval(Color(0xFF6E9A3E), 50f, 36f, 24f, 30f)
    oval(Color(0xFF5A8430), 50f, 50f, 18f, 12f)
    // Grappes de samares
    val samare = Color(0xFFD6DB8A)
    poly(samare, 36f, 40f, 40f, 46f, 34f, 48f)
    poly(samare, 62f, 30f, 66f, 36f, 60f, 38f)
    poly(samare, 58f, 52f, 62f, 58f, 56f, 59f)
}

/** Frêne : tronc élancé, houppier aéré de feuilles composées. */
internal fun DrawScope.drawFrene() {
    val gris = Color(0xFF8A8576)
    poly(gris, 47f, 92f, 53f, 92f, 52f, 58f, 48f, 58f)
    stroke(gris, 50f, 60f, 28f, 30f, 2.5f)
    stroke(gris, 50f, 60f, 72f, 28f, 2.5f)
    stroke(gris, 50f, 60f, 50f, 14f, 2.5f)
    val feuille = Color(0xFF7FAE4A)
    val fonce = Color(0xFF5E8E36)
    // Folioles le long des trois branches
    for (t in 1..4) {
        val f = t / 4.5f
        oval(if (t % 2 == 0) feuille else fonce, 50f - 22f * f - 5f, 60f - 30f * f, 6f, 4f)
        oval(feuille, 50f - 22f * f + 4f, 60f - 30f * f - 4f, 6f, 4f)
        oval(if (t % 2 == 0) fonce else feuille, 50f + 22f * f + 5f, 60f - 32f * f, 6f, 4f)
        oval(feuille, 50f + 22f * f - 4f, 60f - 32f * f - 4f, 6f, 4f)
        oval(fonce, 45f, 60f - 46f * f, 5f, 3.5f)
        oval(feuille, 55f, 60f - 46f * f - 2f, 5f, 3.5f)
    }
    oval(feuille, 50f, 13f, 5f, 4f)
}
