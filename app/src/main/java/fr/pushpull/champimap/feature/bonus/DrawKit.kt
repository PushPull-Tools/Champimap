package fr.pushpull.champimap.feature.bonus

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

/*
 * Petite boîte à outils de dessin partagée par les pictos, l'illustration de forêt
 * et les pluies cachées. Tous les pictos sont dessinés dans un carré de référence
 * de 100 × 100 unités, puis mis à l'échelle (voir [inBox]).
 */

/** Ombre portée commune (noir à 12 %) : donne la même « assise » à tous les pictos. */
internal val OMBRE = Color(0x1F000000)

/** Construit un chemin (Path) avec une syntaxe compacte. */
internal fun path(block: Path.() -> Unit): Path = Path().apply(block)

/** Ellipse définie par son centre et ses deux rayons. */
internal fun DrawScope.oval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float) =
    drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))

/** Disque défini par son centre et son rayon. */
internal fun DrawScope.disc(color: Color, cx: Float, cy: Float, r: Float) =
    drawCircle(color, r, Offset(cx, cy))

/** Trait à bouts arrondis. */
internal fun DrawScope.stroke(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) =
    drawLine(color, Offset(x1, y1), Offset(x2, y2), width, StrokeCap.Round)

/** Polygone plein à partir d'une suite de coordonnées x1, y1, x2, y2… */
internal fun DrawScope.poly(color: Color, vararg xy: Float) {
    if (xy.size < 6) return
    val p = Path()
    p.moveTo(xy[0], xy[1])
    var i = 2
    while (i + 1 < xy.size) {
        p.lineTo(xy[i], xy[i + 1])
        i += 2
    }
    p.close()
    drawPath(p, color)
}

/**
 * Chapeau en dôme : base plate de [x1] à [x2] à la hauteur [yBase], sommet à [apex].
 * (Une courbe de Bézier cubique n'atteint que 75 % de la hauteur de ses points de contrôle,
 * d'où la correction.)
 */
internal fun dome(x1: Float, x2: Float, yBase: Float, apex: Float): Path {
    val ctrl = yBase - (yBase - apex) / 0.75f
    return path {
        moveTo(x1, yBase)
        cubicTo(x1, ctrl, x2, ctrl, x2, yBase)
        close()
    }
}

/** Ombre au sol sous un sujet (dans le repère 100 × 100). */
internal fun DrawScope.groundShadow(cx: Float = 50f, cy: Float = 93f, rx: Float = 30f) =
    oval(OMBRE, cx, cy, rx, rx * 0.15f)

/**
 * Dessine [block] dans un carré de [side] pixels dont le coin haut gauche est ([left], [top]).
 * À l'intérieur de [block], le repère va de 0 à [unit] (100 par défaut).
 */
internal fun DrawScope.inBox(left: Float, top: Float, side: Float, unit: Float = 100f, block: DrawScope.() -> Unit) {
    val s = side / unit
    withTransform({
        translate(left, top)
        scale(s, s, Offset.Zero)
    }) { block() }
}
