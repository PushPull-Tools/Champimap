package fr.pushpull.champimap.feature.bonus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/*
 * Les 6 champignons illustrés de l'easter egg (cèpe, amanite, girolle, morille,
 * trompette, coulemelle). Repère 100 × 100, sol vers y = 92. Style plat.
 */

/** Cèpe : pied ventru crème, chapeau brun bombé, pores jaunâtres. */
internal fun DrawScope.drawCepe() {
    val pied = path {
        moveTo(40f, 90f)
        cubicTo(28f, 82f, 36f, 60f, 42f, 54f)
        lineTo(58f, 54f)
        cubicTo(64f, 60f, 72f, 82f, 60f, 90f)
        close()
    }
    drawPath(pied, Color(0xFFEADFC8))
    // Réseau du pied (léger) : deux reflets plus foncés
    stroke(Color(0xFFD8C8A8), 44f, 64f, 42f, 82f, 2f)
    stroke(Color(0xFFD8C8A8), 56f, 64f, 58f, 82f, 2f)
    oval(Color(0xFFD9C27A), 50f, 56f, 33f, 5f) // dessous du chapeau (pores)
    drawPath(dome(16f, 84f, 56f, 18f), Color(0xFF8A4B22))
    oval(Color(0x33FFFFFF), 38f, 34f, 10f, 5f) // reflet
}

/** Amanite tue-mouches : chapeau rouge à points blancs, pied blanc avec anneau. */
internal fun DrawScope.drawAmanite() {
    poly(Color(0xFFF2EEE4), 44f, 90f, 56f, 90f, 55f, 50f, 45f, 50f)
    oval(Color(0xFFE6E0D2), 50f, 88f, 10f, 5f) // bulbe
    oval(Color(0xFFDCD5C4), 50f, 62f, 9f, 3f) // anneau
    drawPath(dome(18f, 82f, 52f, 20f), Color(0xFFD83A2E))
    oval(Color(0xFFB82C22), 50f, 52f, 32f, 2.5f) // bord du chapeau
    // Verrues blanches
    disc(Color.White, 36f, 40f, 4f)
    disc(Color.White, 52f, 31f, 4.5f)
    disc(Color.White, 66f, 41f, 3.5f)
    disc(Color.White, 46f, 45f, 3f)
    disc(Color.White, 27f, 47f, 2.5f)
    disc(Color.White, 60f, 48f, 2.5f)
    disc(Color.White, 74f, 48f, 2f)
}

/** Girolle : entonnoir jaune orangé, plis qui descendent le long du pied. */
internal fun DrawScope.drawGirolle() {
    val corps = path {
        moveTo(44f, 90f)
        lineTo(56f, 90f)
        cubicTo(57f, 72f, 66f, 52f, 82f, 40f)
        cubicTo(76f, 34f, 24f, 34f, 18f, 40f)
        cubicTo(34f, 52f, 43f, 72f, 44f, 90f)
        close()
    }
    drawPath(corps, Color(0xFFF2A93B))
    // Plis (fausses lames)
    val pli = Color(0x66B86E12)
    stroke(pli, 50f, 84f, 30f, 44f, 1.6f)
    stroke(pli, 50f, 84f, 42f, 44f, 1.6f)
    stroke(pli, 50f, 84f, 58f, 44f, 1.6f)
    stroke(pli, 50f, 84f, 70f, 44f, 1.6f)
    oval(Color(0xFFF7C35A), 50f, 39f, 32f, 6f) // dessus du chapeau
    oval(Color(0xFFE59A2A), 50f, 38f, 22f, 3f) // creux central
}

/** Morille : chapeau conique alvéolé brun sur pied crème. */
internal fun DrawScope.drawMorille() {
    poly(Color(0xFFEFE6D2), 42f, 90f, 58f, 90f, 57f, 60f, 43f, 60f)
    oval(Color(0xFFE2D6BC), 50f, 89f, 9f, 3f)
    val chapeau = path {
        moveTo(33f, 64f)
        cubicTo(29f, 40f, 42f, 10f, 50f, 9f)
        cubicTo(58f, 10f, 71f, 40f, 67f, 64f)
        close()
    }
    drawPath(chapeau, Color(0xFF8A6642))
    // Alvéoles
    val alveole = Color(0xFF4E3824)
    val cellules = floatArrayOf(
        47f, 21f, 53f, 21f,
        44f, 30f, 50f, 30f, 56f, 30f,
        41f, 39f, 47f, 39f, 53f, 39f, 59f, 39f,
        39f, 48f, 45f, 48f, 51f, 48f, 57f, 48f, 62f, 48f,
        42f, 57f, 48f, 57f, 54f, 57f, 60f, 57f,
    )
    var i = 0
    while (i + 1 < cellules.size) {
        oval(alveole, cellules[i], cellules[i + 1], 2.3f, 3.2f)
        i += 2
    }
}

/** Trompette des morts : cornet gris-noir évasé. */
internal fun DrawScope.drawTrompette() {
    val corps = path {
        moveTo(46f, 90f)
        lineTo(54f, 90f)
        cubicTo(55f, 70f, 62f, 40f, 76f, 22f)
        cubicTo(70f, 16f, 30f, 16f, 24f, 22f)
        cubicTo(38f, 40f, 45f, 70f, 46f, 90f)
        close()
    }
    drawPath(corps, Color(0xFF3A3236))
    // Face éclairée (côté droit)
    val clair = path {
        moveTo(50f, 88f)
        cubicTo(52f, 70f, 58f, 42f, 72f, 23f)
        lineTo(76f, 22f)
        cubicTo(62f, 40f, 55f, 70f, 54f, 90f)
        close()
    }
    drawPath(clair, Color(0xFF5E5258))
    oval(Color(0xFF1E191B), 50f, 21f, 25f, 5f) // ouverture du cornet
    oval(Color(0xFF6E6268), 50f, 18f, 26f, 1.4f) // liseré du bord
}

/** Coulemelle : grand pied fin chiné, chapeau en parasol écailleux avec mamelon. */
internal fun DrawScope.drawCoulemelle() {
    poly(Color(0xFFD9C9A8), 48f, 90f, 52f, 90f, 51.5f, 40f, 48.5f, 40f)
    oval(Color(0xFFCDBA92), 50f, 89f, 5f, 3f) // bulbe
    // Pied chiné (zébrures)
    val chine = Color(0xFFA88A62)
    stroke(chine, 48.5f, 60f, 51.5f, 63f, 1.2f)
    stroke(chine, 48.5f, 70f, 51.5f, 73f, 1.2f)
    stroke(chine, 48.5f, 80f, 51.5f, 83f, 1.2f)
    oval(Color(0xFFF0E8D8), 50f, 52f, 7f, 2.5f) // anneau
    oval(Color(0xFFF2EAD9), 50f, 42f, 40f, 2.5f) // dessous du chapeau
    val chapeau = path {
        moveTo(10f, 42f)
        cubicTo(22f, 22f, 78f, 22f, 90f, 42f)
        close()
    }
    drawPath(chapeau, Color(0xFFE8D7B8))
    // Écailles brunes
    val ecaille = Color(0xFF9B7650)
    val pts = floatArrayOf(32f, 34f, 68f, 34f, 22f, 39f, 78f, 39f, 42f, 31f, 58f, 31f, 50f, 36f, 36f, 39f, 64f, 39f)
    for (k in pts.indices step 2) oval(ecaille, pts[k], pts[k + 1], 2.6f, 1.6f)
    disc(Color(0xFF7A5434), 50f, 28f, 6f) // mamelon
}
