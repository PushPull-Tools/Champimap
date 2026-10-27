package fr.pushpull.champimap.feature.bonus

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Thème « Psyché » (Mushroom party) : fond d'écran en dégradé animé et cartes
 * en dégradé pastel animé entourées d'une bordure arc-en-ciel qui tourne.
 * Toutes les animations en boucle sont coupées si l'utilisateur a choisi « réduire les
 * animations » dans Android (échelle d'animation à 0).
 * Étirement du mode party (tirage vers le haut, voir PartyUnlock.kt) : le fond ondule et s'étire
 * depuis le bas, les cartes s'allongent et vibrent, puis tout revient en rebondissant.
 */

/** Couleurs du fond psyché (boucle de 18 s). */
private val FOND_PSYCHE = listOf(ChampiColors.PsyViolet, ChampiColors.PsyRose, ChampiColors.PsyOrange, ChampiColors.PsyTurquoise)

/** Couleurs pastel des cartes psyché (boucle de 14 s). */
private val CARTE_PSYCHE = listOf(Color(0xFFFFB3E6), Color(0xFFC9A7FF), Color(0xFF9FF3D9))

/** Arc-en-ciel de la bordure (la première couleur est répétée à la fin pour un raccord invisible). */
private val ARC_EN_CIEL = listOf(
    Color(0xFFFF3B3B), Color(0xFFFF9F1C), Color(0xFFFFE53B), Color(0xFF3BE07A),
    Color(0xFF2BD9D9), Color(0xFF3B6BFF), Color(0xFFB03BFF), Color(0xFFFF3B3B),
)

private const val DUREE_FOND_MS = 18_000
private const val DUREE_CARTE_MS = 14_000
private const val DUREE_BORDURE_MS = 4_000

/**
 * true si les animations sont autorisées sur le téléphone.
 * « Réduire les animations » (accessibilité) met ANIMATOR_DURATION_SCALE à 0.
 */
@Composable
internal fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
}

/**
 * Phase d'une boucle infinie, de 0 à 1 en [durationMs] millisecondes.
 * Si [enabled] est faux, la phase reste figée à 0 (aucune animation).
 */
@Composable
internal fun rememberLoopPhase(durationMs: Int, enabled: Boolean): State<Float> {
    if (!enabled) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "boucle")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
}

/**
 * Palette « qui glisse » : chaque arrêt du dégradé prend une couleur interpolée
 * dans la palette cyclique, décalée selon [phase]. Le dernier arrêt égale le premier.
 */
private fun cyclicColors(palette: List<Color>, phase: Float): List<Color> {
    val n = palette.size
    return List(n + 1) { i ->
        val pos = (i + phase * n) % n
        val a = floor(pos).toInt() % n
        val b = (a + 1) % n
        lerp(palette[a], palette[b], pos - floor(pos))
    }
}

/**
 * Pinceau de dégradé linéaire dont les couleurs glissent et dont la direction
 * tourne lentement (un tour complet par boucle).
 */
private fun movingGradient(palette: List<Color>, phase: Float, size: Size, origin: Offset = Offset.Zero): Brush {
    val angle = phase * 2f * Math.PI.toFloat()
    val half = sqrt(size.width * size.width + size.height * size.height) / 2f
    val center = Offset(origin.x + size.width / 2f, origin.y + size.height / 2f)
    val dir = Offset(cos(angle) * half, sin(angle) * half)
    return Brush.linearGradient(cyclicColors(palette, phase), start = center - dir, end = center + dir)
}

/** Nombre de bandes horizontales du fond pendant l'ondulation. */
private const val BANDES_ONDULATION = 28

/** Fond d'écran : couleur du thème, ou dégradé psychédélique animé (et élastique) en mode party. */
@Composable
fun PsycheBackground(content: @Composable () -> Unit) {
    val psyche = LocalChampiStyle.current.psyche
    val stretch = LocalPartyStretch.current
    val density = LocalDensity.current
    val waveAmpPx = with(density) { 36.dp.toPx() }
    // Le modificateur change, mais la Box et son contenu restent les mêmes (pas de perte d'état).
    val fond: Modifier = if (psyche) {
        val phase by rememberLoopPhase(DUREE_FOND_MS, animationsEnabled())
        Modifier.drawBehind {
            val s = stretch?.value ?: 0f
            val brush = movingGradient(FOND_PSYCHE, phase, size)
            if (abs(s) < 0.002f) drawRect(brush) else drawWavyBackground(brush, s, stretch?.clockMs ?: 0L, waveAmpPx)
        }
    } else {
        Modifier.background(MaterialTheme.colorScheme.background)
    }
    // Contenu étiré verticalement depuis le bas pendant le tirage (aucun effet au repos ni hors party).
    val etirement: Modifier = if (psyche && stretch != null) {
        Modifier.graphicsLayer {
            scaleY = 1f + 0.06f * stretch.value
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    } else {
        Modifier
    }
    Box(Modifier.fillMaxSize().then(fond)) {
        Box(Modifier.fillMaxSize().then(etirement)) { content() }
    }
}

/**
 * Fond pendant l'étirement : l'écran est découpé en bandes horizontales, chacune décalée
 * sur une vague (qui défile avec [clockMs]) et remontée d'autant plus qu'elle est haute.
 * [s] = étirement (0 → ~1, négatif pendant le rebond).
 */
private fun DrawScope.drawWavyBackground(brush: Brush, s: Float, clockMs: Long, ampPx: Float) {
    val w = size.width
    val h = size.height
    val bandH = h / BANDES_ONDULATION
    val t = clockMs / 1000f
    for (i in 0 until BANDES_ONDULATION) {
        val top = i * bandH
        val dx = sin(i * 0.55f + t * 9f) * ampPx * s
        val dy = -(h - top) * 0.12f * s
        // +1 px pour éviter les fines lignes entre deux bandes.
        clipRect(0f, top, w, top + bandH + 1f) {
            translate(left = dx, top = dy) {
                // Rectangle plus grand que l'écran : aucune zone vide quand la bande se décale.
                drawRect(brush, topLeft = Offset(-ampPx * 2f, -h * 0.3f), size = Size(w + ampPx * 4f, h * 1.6f))
            }
        }
    }
}

/**
 * Apparence d'une carte en mode party : dégradé pastel animé (14 s), coins 18 dp,
 * bordure conique arc-en-ciel de 3 dp qui tourne (4 s).
 */
@Composable
fun Modifier.psycheCard(): Modifier {
    val anim = animationsEnabled()
    val phase by rememberLoopPhase(DUREE_CARTE_MS, anim)
    val spin by rememberLoopPhase(DUREE_BORDURE_MS, anim)
    val density = LocalDensity.current
    val borderPx = with(density) { 3.dp.toPx() }
    val radiusPx = with(density) { 18.dp.toPx() }
    val jitterPx = with(density) { 2.dp.toPx() }
    val stretch = LocalPartyStretch.current

    return this
        // Étirement du mode party : la carte s'allonge et vibre, proportionnellement au tirage.
        .graphicsLayer {
            val s = stretch?.value ?: 0f
            val t = (stretch?.clockMs ?: 0L) / 1000f
            val force = abs(s)
            scaleY = 1f + 0.05f * s
            scaleX = 1f - 0.02f * s
            rotationZ = sin(t * 45f) * 1.2f * force
            translationX = sin(t * 60f + 1f) * jitterPx * force
        }
        .drawBehind {
            // Bordure plus épaisse pendant l'étirement (les contours « vibrent »).
            val border = borderPx * (1f + 1.5f * abs(stretch?.value ?: 0f))
            // 1. Bordure : un grand disque arc-en-ciel qui tourne, découpé à la forme de la carte.
            val outline = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radiusPx)))
            }
            clipPath(outline) {
                rotate(spin * 360f, center) {
                    drawCircle(Brush.sweepGradient(ARC_EN_CIEL, center), radius = size.maxDimension, center = center)
                }
            }
            // 2. Intérieur de la carte, en retrait de l'épaisseur de la bordure.
            val inner = Size((size.width - 2 * border).coerceAtLeast(0f), (size.height - 2 * border).coerceAtLeast(0f))
            drawRoundRect(
                brush = movingGradient(CARTE_PSYCHE, phase, inner, Offset(border, border)),
                topLeft = Offset(border, border),
                size = inner,
                cornerRadius = CornerRadius((radiusPx - border).coerceAtLeast(0f)),
            )
        }
}
