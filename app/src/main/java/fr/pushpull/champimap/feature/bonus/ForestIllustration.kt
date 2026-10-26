package fr.pushpull.champimap.feature.bonus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.max

/*
 * Illustration par défaut d'une fiche spot : forêt dense du Limousin en style plat.
 * Plans successifs : ciel, collines, lisière lointaine (avec un chevreuil), rangée
 * d'arbres (hêtre cuivré, châtaigniers, bouleaux, chênes), sous-bois, sol avec granite
 * moussu, fougères, bruyère, feuilles, châtaignes, petits champignons, écureuil,
 * rouge-gorge et hérisson.
 * Dessinée dans un repère de 390 × 210, mise à l'échelle pour REMPLIR la place
 * disponible (rognage centré si les proportions diffèrent).
 */

private const val REF_W = 390f
private const val REF_H = 210f

/** Illustration de forêt du Limousin (proportions 390 × 210 si aucune taille n'est imposée). */
@Composable
fun ForestIllustration(modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .aspectRatio(REF_W / REF_H, matchHeightConstraintsFirst = false)
            .semantics { contentDescription = "Illustration : forêt du Limousin" },
    ) {
        val s = max(size.width / REF_W, size.height / REF_H)
        val dx = (size.width - REF_W * s) / 2f
        val dy = (size.height - REF_H * s) / 2f
        clipRect {
            withTransform({
                translate(dx, dy)
                scale(s, s, Offset.Zero)
            }) { drawForest() }
        }
    }
}

/** Dessine toute la scène dans le repère 390 × 210. */
private fun DrawScope.drawForest() {
    drawSkyAndHills()
    drawFarTreeline()
    drawMidTrees()
    drawUndergrowth()
    drawGround()
    drawForegroundLife()
}

/** Ciel clair et collines du plateau de Millevaches au loin. */
private fun DrawScope.drawSkyAndHills() {
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFFDCEBEF), Color(0xFFF3EEDC)), startY = 0f, endY = 120f),
        topLeft = Offset.Zero,
        size = androidx.compose.ui.geometry.Size(REF_W, REF_H),
    )
    disc(Color(0xFFFFF4D2), 320f, 34f, 16f) // soleil voilé
    val collineLoin = path {
        moveTo(0f, 92f)
        cubicTo(60f, 70f, 120f, 78f, 180f, 84f)
        cubicTo(240f, 72f, 310f, 66f, 390f, 80f)
        lineTo(390f, 130f); lineTo(0f, 130f)
        close()
    }
    drawPath(collineLoin, Color(0xFFB9CDB9))
    val colline = path {
        moveTo(0f, 104f)
        cubicTo(80f, 90f, 150f, 98f, 220f, 100f)
        cubicTo(290f, 92f, 340f, 92f, 390f, 98f)
        lineTo(390f, 140f); lineTo(0f, 140f)
        close()
    }
    drawPath(colline, Color(0xFF9DBA98))
}

/** Lisière lointaine : rangée serrée de conifères et feuillus bleutés, chevreuil dans le pré. */
private fun DrawScope.drawFarTreeline() {
    val loin = Color(0xFF7FA58A)
    val loinFonce = Color(0xFF6A927A)
    // Feuillus arrondis
    var x = -6f
    var k = 0
    while (x < 400f) {
        val r = 11f + (k % 3) * 3f
        disc(if (k % 2 == 0) loin else loinFonce, x, 100f - (k % 4) * 3f, r)
        x += 14f
        k++
    }
    // Conifères (douglas) qui dépassent
    floatArrayOf(40f, 118f, 210f, 300f, 360f).forEachIndexed { i, cx ->
        val h = 30f + (i % 2) * 8f
        poly(Color(0xFF54806A), cx, 104f - h, cx - 9f, 108f, cx + 9f, 108f)
    }
    // Pré de lisière et chevreuil au loin
    drawRect(Color(0xFFA9C48C), Offset(150f, 108f), androidx.compose.ui.geometry.Size(110f, 14f))
    drawPictoAt("chevreuil", 205f, 121f, 20f, shadow = false)
}

/** Rangée principale d'arbres (pictos agrandis) : la forêt dense. */
private fun DrawScope.drawMidTrees() {
    // Masse sombre du sous-bois derrière les troncs, pour l'effet « dense »
    val masse = path {
        moveTo(0f, 120f)
        cubicTo(40f, 96f, 90f, 112f, 140f, 104f)
        lineTo(140f, 160f); lineTo(0f, 160f)
        close()
    }
    drawPath(masse, Color(0xFF4E7550))
    val masse2 = path {
        moveTo(260f, 106f)
        cubicTo(310f, 96f, 350f, 110f, 390f, 102f)
        lineTo(390f, 160f); lineTo(260f, 160f)
        close()
    }
    drawPath(masse2, Color(0xFF4E7550))

    // Arbres de gauche à droite (clé, centre x, pied y, taille)
    drawPictoAt("douglas", 18f, 160f, 96f, shadow = false)
    drawPictoAt("chene", 62f, 166f, 112f, shadow = false)
    drawPictoAt("bouleau", 104f, 162f, 86f, shadow = false)
    drawPictoAt("chataignier", 140f, 168f, 104f, shadow = false)
    drawPictoAt("bouleau", 262f, 160f, 80f, shadow = false)
    drawPictoAt("chataignier", 300f, 166f, 100f, shadow = false)
    drawPictoAt("chene", 352f, 168f, 116f, shadow = false)
    // Hêtre cuivré, l'arbre remarquable, légèrement décalé du centre (laisse voir la lisière)
    drawPictoAt("hetre", 236f, 170f, 120f, shadow = false)
}

/** Sous-bois : fougères et bruyère entre les troncs. */
private fun DrawScope.drawUndergrowth() {
    floatArrayOf(10f, 48f, 86f, 124f, 172f, 214f, 252f, 290f, 330f, 372f).forEachIndexed { i, cx ->
        drawFern(cx, 170f + (i % 3) * 3f, 16f + (i % 2) * 5f, if (i % 2 == 0) Color(0xFF5C8E3A) else Color(0xFF6FA046))
    }
}

/** Fougère : touffe de frondes en éventail (pennes figurées par de petits ovales). */
private fun DrawScope.drawFern(cx: Float, baseY: Float, h: Float, color: Color) {
    val angles = floatArrayOf(-0.9f, -0.45f, 0f, 0.45f, 0.9f)
    for (a in angles) {
        val tx = cx + a * h * 1.1f
        val ty = baseY - h * (1f - kotlin.math.abs(a) * 0.35f)
        stroke(color, cx, baseY, tx, ty, 1.4f)
        // Pennes le long de la fronde
        for (t in 1..4) {
            val f = t / 5f
            oval(color, cx + (tx - cx) * f, baseY + (ty - baseY) * f, 3.2f - f * 1.5f, 1.4f)
        }
    }
}

/** Sol : talus, granite moussu, bruyère, feuilles mortes. */
private fun DrawScope.drawGround() {
    val sol = path {
        moveTo(0f, 172f)
        cubicTo(90f, 164f, 180f, 176f, 260f, 168f)
        cubicTo(320f, 162f, 360f, 170f, 390f, 166f)
        lineTo(390f, 210f); lineTo(0f, 210f)
        close()
    }
    drawPath(sol, Color(0xFF7A5A3A))
    val litiere = path {
        moveTo(0f, 186f)
        cubicTo(100f, 180f, 200f, 190f, 390f, 182f)
        lineTo(390f, 210f); lineTo(0f, 210f)
        close()
    }
    drawPath(litiere, Color(0xFF8E6A44))

    // Bloc de granite moussu (à gauche) et petite pierre (à droite)
    val granite = path {
        moveTo(18f, 196f)
        cubicTo(16f, 176f, 36f, 168f, 58f, 170f)
        cubicTo(78f, 172f, 88f, 184f, 86f, 196f)
        close()
    }
    drawPath(granite, Color(0xFFA7A39A))
    disc(Color(0xFF8E8A82), 40f, 186f, 2f) // grain du granite
    disc(Color(0xFF8E8A82), 66f, 184f, 1.6f)
    disc(Color(0xFFC2BEB4), 52f, 180f, 1.8f)
    val mousse = path {
        moveTo(24f, 178f)
        cubicTo(36f, 166f, 64f, 164f, 80f, 176f)
        cubicTo(64f, 172f, 40f, 172f, 24f, 178f)
        close()
    }
    drawPath(mousse, Color(0xFF6E9A3E))
    oval(Color(0xFF9A968C), 330f, 196f, 14f, 7f)
    oval(Color(0xFF6E9A3E), 326f, 191f, 9f, 2.5f)

    // Bruyère (touffes violettes)
    floatArrayOf(102f, 184f, 196f, 192f, 286f, 188f, 368f, 194f).let { p ->
        for (k in p.indices step 2) drawHeather(p[k], p[k + 1])
    }

    // Feuilles mortes éparses
    val teintes = listOf(Color(0xFFD08A2E), Color(0xFFB0522A), Color(0xFFE0B040))
    floatArrayOf(
        120f, 200f, 150f, 196f, 176f, 204f, 222f, 200f, 250f, 205f,
        274f, 199f, 300f, 204f, 350f, 204f, 96f, 205f, 8f, 204f,
    ).let { p ->
        for (k in p.indices step 2) oval(teintes[(k / 2) % teintes.size], p[k], p[k + 1], 3.2f, 1.6f)
    }

    // Châtaignes et bogue ouverte sous le châtaignier
    drawChestnut(146f, 192f)
    drawChestnut(156f, 194f)
    drawChestnut(306f, 190f)
}

/** Touffe de bruyère : tiges vertes et clochettes mauves. */
private fun DrawScope.drawHeather(cx: Float, baseY: Float) {
    val tige = Color(0xFF55743A)
    stroke(tige, cx, baseY, cx - 5f, baseY - 10f, 1.2f)
    stroke(tige, cx, baseY, cx, baseY - 12f, 1.2f)
    stroke(tige, cx, baseY, cx + 5f, baseY - 9f, 1.2f)
    val mauve = Color(0xFFB765B5)
    for (t in 0..2) {
        disc(mauve, cx - 5f + t * 0.6f, baseY - 10f + t * 3f, 1.4f)
        disc(mauve, cx, baseY - 12f + t * 3f, 1.4f)
        disc(mauve, cx + 5f - t * 0.6f, baseY - 9f + t * 3f, 1.4f)
    }
}

/** Châtaigne brune brillante. */
private fun DrawScope.drawChestnut(cx: Float, cy: Float) {
    oval(Color(0xFF6E3A1E), cx, cy, 4f, 3.4f)
    oval(Color(0xFFE6D2B0), cx, cy + 2.2f, 2.6f, 1f)
    disc(Color(0x55FFFFFF), cx - 1.2f, cy - 1.2f, 1f)
}

/** Premier plan : petits champignons et animaux. */
private fun DrawScope.drawForegroundLife() {
    // Petits champignons dans la litière
    drawPictoAt("cepe", 112f, 200f, 22f)
    drawPictoAt("cepe", 126f, 202f, 16f)
    drawPictoAt("girolle", 268f, 202f, 16f)
    drawPictoAt("girolle", 279f, 203f, 13f)
    drawPictoAt("amanite", 186f, 200f, 18f)
    // Animaux
    drawPictoAt("ecureuil", 58f, 172f, 26f, shadow = false) // assis sur le granite
    drawPictoAt("rougegorge", 330f, 192f, 20f, shadow = false) // perché sur la pierre
    drawPictoAt("herisson", 228f, 207f, 26f)
}
