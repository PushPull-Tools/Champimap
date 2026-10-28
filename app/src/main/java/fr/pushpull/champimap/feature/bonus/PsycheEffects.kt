package fr.pushpull.champimap.feature.bonus

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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

/*
 * Thème « Psyché » (Mushroom party) : ambiance de « trip » aux champignons, sans jamais gêner la lecture.
 *
 * Ce qui bouge (uniquement les fonds, bordures et accents — jamais le texte ni les boutons) :
 *  - fond : motif kaléidoscopique (6 branches) à déformation fractale, qui tourne et se transforme
 *    lentement, « respire » (zoom lent) et ondule (vagues concentriques), couleurs qui changent en douceur ;
 *    Android 13+ : shader AGSL (RuntimeShader) ; avant : version Canvas (taches de couleur qui dérivent,
 *    rosace de pétales, ondes) ;
 *  - cartes : surface nuit violette qui respire, bordure irisée qui tourne et halos colorés autour (traînées).
 *
 * Lisibilité : le texte reste clair sur fond sombre. La luminance du fond est plafonnée (texte clair à ≥ 4,5:1)
 * et les cartes sont quasi opaques (contraste ≥ 7:1).
 * Performances : UNE seule boucle infinie (dans PsycheBackground) pilote tout, partagée via LocalPsychePhase ;
 * pinceaux, chemins et shader sont créés une fois (par taille), rien n'est alloué à chaque image.
 * « Réduire les animations » (Android) : tout est figé sur une image fixe, toujours colorée.
 * Étirement du mode party (tirage vers le haut, voir PartyUnlock.kt) : le fond ondule et s'étire
 * depuis le bas, les cartes s'allongent et vibrent, puis tout revient en rebondissant.
 * Les écrans de sécurité (SafetyTheme) coupent le thème Psyché : rien de tout ça ne s'y affiche.
 */

// ─── Réglages de l'animation ────────────────────────────────────────────────────────────────────

/** Durée de la boucle commune (2 min) : tous les mouvements en sont des multiples entiers, raccord invisible. */
private const val DUREE_TRIP_MS = 120_000

/** Phase utilisée quand les animations sont coupées : une image fixe déjà bien colorée. */
private const val PHASE_FIGEE = 0.13f

/** Nombre de respirations par boucle (≈ 10 s chacune, rythme d'une respiration lente). */
private const val RESPIRATIONS = 12f

/** Nombre de tours de la bordure irisée des cartes par boucle (≈ 15 s par tour). */
private const val TOURS_BORDURE = 8f

private const val TAU = (2.0 * Math.PI).toFloat()

// ─── Couleurs ───────────────────────────────────────────────────────────────────────────────────

/**
 * Teintes du fond (version Canvas) : saturées mais sombres, pour que le texte clair reste lisible
 * même quand deux taches se superposent.
 */
private val TEINTES_FOND = listOf(
    Color(0xFF6A2BD9), // violet
    Color(0xFFB01883), // magenta
    Color(0xFF3046C8), // indigo
    Color(0xFF127A70), // sarcelle
    Color(0xFFA8561A), // ambre
)

/** Teintes vives des pétales de la rosace (Canvas), posées en transparence. */
private val TEINTES_PETALES = listOf(
    Color(0xFFFF8AD0), Color(0xFFB98CFF), Color(0xFF7CF2D4), Color(0xFFFFD36E),
)

/** Reflets irisés des bordures de cartes (la première couleur est répétée à la fin : raccord invisible). */
private val IRISE = listOf(
    Color(0xFFFF8AD0), Color(0xFFB98CFF), Color(0xFF6FA8FF), Color(0xFF7CF2D4),
    Color(0xFFFFE08A), Color(0xFFFF9F6E), Color(0xFFFF8AD0),
)

/** Taches de couleur prêtes à l'emploi (dégradé radial de rayon 1, agrandi au dessin) : aucune allocation par image. */
private val TACHES: List<Brush> = TEINTES_FOND.map { c ->
    Brush.radialGradient(listOf(c, c.copy(alpha = 0.45f), c.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)
}

// ─── Boucle commune ─────────────────────────────────────────────────────────────────────────────

/** Phase figée (aperçus, écrans hors PsycheBackground, animations coupées). */
private object PhaseFigee : State<Float> {
    override val value: Float = PHASE_FIGEE
}

/** Phase (0 → 1) de la boucle psyché commune, fournie par PsycheBackground aux cartes. */
val LocalPsychePhase = staticCompositionLocalOf<State<Float>> { PhaseFigee }

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
 * Si [enabled] est faux, la phase reste figée à [figee] (aucune animation).
 */
@Composable
internal fun rememberLoopPhase(durationMs: Int, enabled: Boolean, figee: Float = 0f): State<Float> {
    if (!enabled) return remember(figee) { mutableFloatStateOf(figee) }
    val transition = rememberInfiniteTransition(label = "boucle")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
}

// ─── Fond d'écran ───────────────────────────────────────────────────────────────────────────────

/** Nombre de bandes horizontales du fond pendant l'ondulation (étirement du mode party). */
private const val BANDES_ONDULATION = 28

/** Fond d'écran : couleur du thème, ou « trip » animé (et élastique) en mode party. */
@Composable
fun PsycheBackground(content: @Composable () -> Unit) {
    val psyche = LocalChampiStyle.current.psyche
    val stretch = LocalPartyStretch.current
    val density = LocalDensity.current
    val waveAmpPx = with(density) { 36.dp.toPx() }
    val rippleWidthPx = with(density) { 1.5.dp.toPx() }

    // La boucle n'existe qu'en psyché ; ailleurs, phase figée (aucun coût).
    val phase: State<Float> = if (psyche) rememberLoopPhase(DUREE_TRIP_MS, animationsEnabled(), PHASE_FIGEE) else PhaseFigee
    val shader = if (psyche) rememberTripShader() else null

    // Le modificateur change, mais la Box et son contenu restent les mêmes (pas de perte d'état).
    val fond: Modifier = if (psyche) {
        Modifier.drawWithCache {
            // Tout ce qui dépend de la taille est préparé ici, une seule fois par taille d'écran.
            val canvas = if (shader == null) TripCanvas(size, rippleWidthPx) else null
            val couche: DrawScope.(Float, Float) -> Unit = { p, marge ->
                val brush = shader?.brush
                if (shader != null && brush != null) {
                    shader.update(size, p)
                    // Rectangle agrandi de [marge] : aucune zone vide quand une bande se décale.
                    drawRect(brush, topLeft = Offset(-marge, -marge), size = Size(size.width + 2 * marge, size.height + 2 * marge))
                } else {
                    canvas?.draw(this, p, marge)
                }
            }
            onDrawBehind {
                val p = phase.value
                val s = stretch?.value ?: 0f
                if (abs(s) < 0.002f) couche(p, 0f) else drawWavyBackground(couche, p, s, stretch?.clockMs ?: 0L, waveAmpPx)
            }
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
    CompositionLocalProvider(LocalPsychePhase provides phase) {
        Box(Modifier.fillMaxSize().then(fond)) {
            Box(Modifier.fillMaxSize().then(etirement)) { content() }
        }
    }
}

/**
 * Fond pendant l'étirement : l'écran est découpé en bandes horizontales, chacune décalée
 * sur une vague (qui défile avec [clockMs]) et remontée d'autant plus qu'elle est haute.
 * [s] = étirement (0 → ~1, négatif pendant le rebond), [couche] dessine le fond normal.
 */
private fun DrawScope.drawWavyBackground(
    couche: DrawScope.(Float, Float) -> Unit,
    phase: Float,
    s: Float,
    clockMs: Long,
    ampPx: Float,
) {
    val w = size.width
    val h = size.height
    val bandH = h / BANDES_ONDULATION
    val t = clockMs / 1000f
    val marge = maxOf(ampPx * 2f, h * 0.3f)
    for (i in 0 until BANDES_ONDULATION) {
        val top = i * bandH
        val dx = sin(i * 0.55f + t * 9f) * ampPx * s
        val dy = -(h - top) * 0.12f * s
        // +1 px pour éviter les fines lignes entre deux bandes.
        clipRect(0f, top, w, top + bandH + 1f) {
            translate(left = dx, top = dy) { couche(phase, marge) }
        }
    }
}

// ─── Fond, version shader (Android 13+) ─────────────────────────────────────────────────────────

/**
 * Motif du fond en AGSL : kaléidoscope à 6 branches + déformation fractale, respiration, ondes,
 * teintes en palette cosinus. Toutes les vitesses sont des multiples entiers de la boucle (raccord invisible).
 */
private const val TRIP_AGSL = """
uniform float2 uSize;
uniform float uPhase;

const float TAU = 6.2831853;

// Palette cosinus : teintes saturées mais harmonieuses, qui s'enchaînent sans à-coup.
float3 palette(float t) {
    return 0.5 + 0.5 * cos(TAU * (t + float3(0.0, 0.33, 0.67)));
}

half4 main(float2 fragCoord) {
    float a = uPhase * TAU;
    float2 p = (fragCoord - 0.5 * uSize) / min(uSize.x, uSize.y);

    // Respiration : zoom lent, environ 10 s par cycle.
    p *= 1.0 + 0.07 * sin(a * 12.0);

    // Ondulation : vagues concentriques qui déforment doucement le motif.
    float r = length(p);
    p += (p / max(r, 0.001)) * 0.02 * sin(r * 16.0 - a * 24.0);
    r = length(p);

    // Kaléidoscope : 6 branches symétriques, un tour complet par boucle (2 min).
    float seg = TAU / 6.0;
    float ang = mod(atan(p.y, p.x) + a, seg);
    ang = abs(ang - 0.5 * seg);
    float2 q = float2(cos(ang), sin(ang)) * r * 3.2;

    // Motif fractal : repliements successifs de l'espace, de plus en plus fins.
    for (int i = 1; i <= 4; i++) {
        float fi = float(i);
        q += float2(sin(q.y * 1.4 + a * fi + fi), cos(q.x * 1.2 - a * fi + 0.7 * fi)) * (0.7 / fi);
    }
    float v = 0.5 + 0.5 * sin(q.x + q.y);
    float filaments = smoothstep(0.85, 1.0, 0.5 + 0.5 * sin((q.x - q.y) * 2.0));

    // Couleur : la teinte glisse lentement (4 cycles par boucle) et varie avec le motif.
    float3 col = palette(v * 0.6 + r * 0.25 + uPhase * 4.0);
    float3 nuit = float3(0.07, 0.03, 0.12);
    col = mix(nuit, col, 0.35 + 0.35 * v + 0.25 * filaments);

    // Vignette : bords plus sombres.
    col *= mix(0.55, 1.0, 1.0 - smoothstep(0.15, 1.2, r));

    // Garde-fou de lisibilité : luminance plafonnée, le texte clair reste à 4,5:1 au moins.
    float3 lin = col * col;
    float lum = dot(lin, float3(0.2126, 0.7152, 0.0722));
    if (lum > 0.11) {
        col *= sqrt(0.11 / lum);
    }
    return half4(half3(col), 1.0);
}
"""

/** Shader du fond, compilé une seule fois ; seules ses deux variables (uniforms) changent à chaque image. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class TripShader {
    private val shader = RuntimeShader(TRIP_AGSL)

    /** Pinceau qui renvoie toujours le même shader (pas de recréation à chaque image). */
    val brush: ShaderBrush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader = shader
    }

    /** Met à jour la taille et la phase avant le dessin. */
    fun update(size: Size, phase: Float) {
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uPhase", phase)
    }
}

/**
 * Shader du fond si le téléphone le permet (Android 13+), sinon null (version Canvas).
 * Si la compilation échoue (appareil exotique, tests), on bascule aussi sur Canvas.
 */
@Composable
private fun rememberTripShader(): TripShaderHandle? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { TripShaderHandle(TripShader()) }.getOrNull() }
    } else {
        null
    }

/**
 * Enveloppe sans exigence d'API : le code commun n'utilise jamais TripShader (Android 13+) directement.
 * Elle n'est créée que sur Android 13+, les tests de version ne sont là que pour l'analyse (lint).
 */
private class TripShaderHandle(private val impl: Any) {
    /** Pinceau du shader (null n'arrive jamais en pratique). */
    val brush: Brush?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (impl as TripShader).brush else null

    /** Met à jour la taille et la phase avant le dessin. */
    fun update(size: Size, phase: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (impl as TripShader).update(size, phase)
    }
}

// ─── Fond, version Canvas (Android 8 à 12) ──────────────────────────────────────────────────────

/** Nombre de taches de couleur qui dérivent, de pétales de la rosace et d'ondes. */
private const val NB_TACHES = 5
private const val NB_PETALES = 12
private const val NB_ONDES = 3

/**
 * Fond « trip » dessiné au Canvas : nuit violette, taches de couleur qui dérivent et respirent
 * (la teinte de chacune glisse vers la suivante), rosace de pétales qui tourne, ondes qui s'élargissent.
 * Préparé une fois par taille d'écran (dans drawWithCache).
 */
private class TripCanvas(size: Size, rippleWidthPx: Float) {
    private val maxDim = size.maxDimension
    private val onde = Stroke(width = rippleWidthPx)
    private val vignette = Brush.radialGradient(
        listOf(Color.Transparent, ChampiColors.PsyFond.copy(alpha = 0.7f)),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = maxDim * 0.75f,
    )

    fun draw(scope: DrawScope, p: Float, marge: Float) = with(scope) {
        val a = p * TAU
        val w = size.width
        val h = size.height
        val respiration = sin(a * RESPIRATIONS)

        // 1. Nuit violette (agrandie de la marge pour l'ondulation du mode party).
        drawRect(ChampiColors.PsyFond, topLeft = Offset(-marge, -marge), size = Size(w + 2 * marge, h + 2 * marge))

        // 2. Taches de couleur : orbites lentes, rayon qui respire, teinte en fondu vers la suivante.
        val n = TACHES.size
        for (i in 0 until NB_TACHES) {
            val orbite = a * (1 + i % 3) + i * 1.7f
            val cx = w * (0.5f + 0.32f * cos(orbite))
            val cy = h * (0.5f + 0.30f * sin(orbite * 2f + i))
            val rayon = maxDim * (0.42f + 0.06f * sin(a * RESPIRATIONS + i))
            val teinte = p * 4f + i * 1.3f
            val k = floor(teinte).toInt()
            val f = teinte - k
            translate(cx, cy) {
                scale(rayon, Offset.Zero) {
                    drawCircle(TACHES[k % n], radius = 1f, center = Offset.Zero, alpha = 0.55f * (1f - f))
                    drawCircle(TACHES[(k + 1) % n], radius = 1f, center = Offset.Zero, alpha = 0.55f * f)
                }
            }
        }

        // 3. Rosace (effet kaléidoscope) : pétales en transparence qui tournent et s'allongent en respirant.
        val longueur = maxDim * (0.34f + 0.04f * respiration)
        val largeur = longueur * 0.22f
        val centre = Offset(w / 2f, h / 2f)
        for (i in 0 until NB_PETALES) {
            val couleur = TEINTES_PETALES[i % TEINTES_PETALES.size]
            rotate(degrees = p * 360f + i * (360f / NB_PETALES), pivot = centre) {
                drawOval(
                    color = couleur,
                    topLeft = Offset(centre.x, centre.y - largeur / 2f),
                    size = Size(longueur, largeur),
                    alpha = 0.07f,
                )
            }
        }

        // 4. Ondes concentriques qui s'élargissent et s'effacent (≈ 10 s chacune).
        for (i in 0 until NB_ONDES) {
            val t = (p * RESPIRATIONS + i.toFloat() / NB_ONDES) % 1f
            drawCircle(lerp(TEINTES_PETALES[i], Color.White, 0.2f), radius = maxDim * 0.7f * t, center = centre, alpha = 0.18f * (1f - t), style = onde)
        }

        // 5. Vignette : bords plus sombres, le regard reste au centre.
        drawRect(vignette)
    }
}

// ─── Cartes ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Apparence d'une carte en mode party : surface nuit violette quasi opaque (le texte clair reste net),
 * qui respire doucement ; bordure irisée de 2 dp qui tourne lentement ; deux halos colorés autour
 * (traînées de couleur) dont l'intensité suit la respiration.
 */
@Composable
fun Modifier.psycheCard(): Modifier {
    val phase = LocalPsychePhase.current
    val surface = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val borderPx = with(density) { 2.dp.toPx() }
    val radiusPx = with(density) { 18.dp.toPx() }
    val jitterPx = with(density) { 2.dp.toPx() }
    val halo1Px = with(density) { 3.dp.toPx() }
    val halo2Px = with(density) { 7.dp.toPx() }
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
        .drawWithCache {
            // Formes préparées une fois par taille de carte.
            val w = size.width
            val h = size.height
            val contour = roundRectPath(0f, w, h, radiusPx)
            val halo1 = roundRectPath(halo1Px, w, h, radiusPx)
            val halo2 = roundRectPath(halo2Px, w, h, radiusPx)
            val irise = Brush.sweepGradient(IRISE, Offset(w / 2f, h / 2f))
            val disque = size.maxDimension + halo2Px
            onDrawBehind {
                val p = phase.value
                val souffle = 0.5f + 0.5f * sin(p * TAU * RESPIRATIONS)
                val angle = p * 360f * TOURS_BORDURE
                // 1. Halos puis bordure : un disque irisé qui tourne, découpé à chaque forme.
                clipPath(halo2) { rotate(angle) { drawCircle(irise, disque, center, alpha = 0.08f + 0.08f * souffle) } }
                clipPath(halo1) { rotate(angle) { drawCircle(irise, disque, center, alpha = 0.18f + 0.12f * souffle) } }
                clipPath(contour) { rotate(angle) { drawCircle(irise, disque, center) } }
                // 2. Intérieur en retrait de la bordure (plus épaisse pendant l'étirement : les contours « vibrent »).
                val border = borderPx * (1f + 1.5f * abs(stretch?.value ?: 0f))
                drawRoundRect(
                    color = lerp(surface, ChampiColors.PsySurfaceHaute, souffle * 0.6f),
                    topLeft = Offset(border, border),
                    size = Size((w - 2 * border).coerceAtLeast(0f), (h - 2 * border).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((radiusPx - border).coerceAtLeast(0f)),
                    alpha = 0.94f,
                )
            }
        }
}

/** Rectangle arrondi agrandi de [inflate] de chaque côté (0 = forme de la carte). */
private fun roundRectPath(inflate: Float, w: Float, h: Float, radius: Float): Path = Path().apply {
    addRoundRect(RoundRect(-inflate, -inflate, w + inflate, h + inflate, CornerRadius(radius + inflate)))
}
