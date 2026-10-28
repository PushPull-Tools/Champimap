package fr.pushpull.champimap.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Transition circulaire réutilisable (effet « iris »), à poser PAR-DESSUS un écran.
 *
 *  - RevealMode.DISC : un disque plein grandit depuis l'origine et finit par couvrir tout l'écran
 *    (ex. appui sur « C'est parti ! » : le fond envahit l'écran depuis le bouton).
 *  - RevealMode.HOLE : l'écran est couvert, sauf un trou rond qui s'agrandit depuis l'origine
 *    (ex. ouverture sur la carte de la sortie, ou sur le récap). Pour une FERMETURE,
 *    il suffit d'animer le trou de 1 vers 0.
 *
 * progress : 0 = rien (disque nul / trou fermé), 1 = plein écran (disque couvrant / trou grand ouvert).
 * Le progrès est lu dans la phase de dessin (lambda) : pas de recomposition à chaque image.
 * Couleurs : fond du thème ; en mode Psyché, dégradé violet nuit (le texte blanc reste lisible).
 */

/** Façon dont le cercle couvre ou découvre l'écran. */
enum class RevealMode { DISC, HOLE }

/** Fond des pages de transition : fond du thème, ou dégradé radial en mode Psyché. */
@Composable
fun transitionBrush(): Brush =
    if (LocalChampiStyle.current.psyche) {
        Brush.radialGradient(listOf(ChampiColors.PsyViolet, ChampiColors.PsyFond))
    } else {
        SolidColor(MaterialTheme.colorScheme.background)
    }

/** Couleur du texte posé sur le fond de transition (toujours lisible). */
@Composable
fun transitionContentColor(): Color =
    if (LocalChampiStyle.current.psyche) Color.White else MaterialTheme.colorScheme.onBackground

/** Couleur du liseré qui souligne le bord du cercle pendant l'animation. */
@Composable
fun transitionEdgeColor(): Color =
    if (LocalChampiStyle.current.psyche) ChampiColors.PsyOr else MaterialTheme.colorScheme.primary

/**
 * Mémorise le centre de l'élément (coordonnées racine) : sert d'origine au cercle.
 * Exemple : `ChampiButton(..., modifier = Modifier.trackCenterInRoot { origin = it })`.
 */
fun Modifier.trackCenterInRoot(onCenter: (Offset) -> Unit): Modifier =
    this.onGloballyPositioned { c ->
        onCenter(c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f))
    }

/**
 * Calque de transition circulaire plein écran.
 * @param progress avancement 0..1 (lu au dessin).
 * @param originInRoot centre du cercle en coordonnées racine (null = centre du calque).
 * @param content contenu posé sur la partie couverte (ex. phrase de départ), centré.
 */
@Composable
fun CircularReveal(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    mode: RevealMode = RevealMode.HOLE,
    originInRoot: Offset? = null,
    brush: Brush = transitionBrush(),
    edgeColor: Color = transitionEdgeColor(),
    content: @Composable BoxScope.() -> Unit = {},
) {
    // Position du calque dans la racine, pour convertir l'origine en coordonnées locales.
    var selfInRoot by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { selfInRoot = it.positionInRoot() }
            .drawBehind {
                val p = progress().coerceIn(0f, 1f)
                val o = originInRoot?.let { it - selfInRoot } ?: center
                // Rayon qui atteint le coin le plus éloigné de l'origine : l'écran entier est couvert à 1.
                val dx = max(o.x, size.width - o.x)
                val dy = max(o.y, size.height - o.y)
                val radius = sqrt(dx * dx + dy * dy) * p
                when (mode) {
                    RevealMode.DISC -> if (radius > 0f) drawCircle(brush, radius, o)
                    RevealMode.HOLE -> {
                        // Voile plein écran percé d'un trou rond (règle pair-impair).
                        val path = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            if (radius > 0f) addOval(Rect(o, radius))
                        }
                        drawPath(path, brush)
                    }
                }
                // Liseré du bord, seulement pendant le mouvement (s'efface aux extrémités).
                if (p > 0f && p < 1f) {
                    val fade = (minOf(p, 1f - p) * 6f).coerceIn(0f, 1f)
                    drawCircle(edgeColor, radius, o, alpha = fade, style = Stroke(width = 3.dp.toPx()))
                }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
