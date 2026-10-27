package fr.pushpull.champimap.feature.bonus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import fr.pushpull.champimap.core.AppGraph
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/*
 * Pluies cachées : 6 / 12 / 24 appuis « dans le vide » (sur aucun bouton ni champ),
 * à moins d'1 s d'intervalle → pluie de feuilles / de gouttes / de champignons
 * pendant ~4 s, dessinée par-dessus l'appli sans jamais bloquer les appuis.
 * Cette enveloppe intègre aussi le détecteur du mode party (PartyUnlockDetector, avec son étirement).
 */

/** Les trois pluies possibles. */
private enum class RainKind { LEAVES, RAIN, MUSHROOMS }

private const val MAX_GAP_MS = 1_000L
private const val TAP_MAX_MS = 500L
private const val RAIN_DURATION_MS = 4_000L

/** Une particule : position de départ (fractions d'écran), vitesse, taille, couleur… */
private class Particle(
    val x: Float,
    val delay: Float,
    val speed: Float,
    val size: Float,
    val phase: Float,
    val spin: Float,
    val color: Color,
)

/**
 * Superposition qui détecte les appuis répétés dans le vide (6, 12, 24) et lance les pluies cachées.
 *
 * Détection (corrigée) : les appuis sont OBSERVÉS en passe Initial, c'est-à-dire avant tous les écrans
 * enfants, et rien n'est jamais consommé (les clics normaux ne sont pas gênés). Seul le relâchement est
 * ensuite relu en passe Final (même évènement, après les enfants) pour savoir si un bouton, une carte
 * cliquable ou la carte géographique l'a pris : dans ce cas ce n'était pas un appui « dans le vide ».
 * Avant, tout était lu en passe Final et un appui dont l'appui initial ou un micro-mouvement avait été
 * marqué « consommé » par un conteneur (défilement, surface…) était écarté : aucune pluie ne partait.
 *
 * Les pluies sont lancées même si Android réduit les animations : c'est un geste volontaire, court (~4 s),
 * qui n'est rejoué qu'à la demande.
 */
@Composable
fun HiddenRainsOverlay(content: @Composable () -> Unit) {
    var rain by remember { mutableStateOf<RainKind?>(null) }
    var rainId by remember { mutableIntStateOf(0) }
    val counter = remember { TapCounter() }

    Box(Modifier.fillMaxSize()) {
        PartyUnlockDetector {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            // État de l'appui en cours (un seul doigt suivi).
                            var tracking = false
                            var cancelled = false
                            var start = Offset.Zero
                            var startTime = 0L
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                // Plusieurs doigts : ce n'est pas un appui simple.
                                if (event.changes.size > 1) cancelled = true
                                when {
                                    change.changedToDownIgnoreConsumed() -> {
                                        tracking = true
                                        cancelled = event.changes.size > 1
                                        start = change.position
                                        startTime = change.uptimeMillis
                                    }
                                    tracking && change.changedToUpIgnoreConsumed() -> {
                                        tracking = false
                                        val quick = change.uptimeMillis - startTime <= TAP_MAX_MS
                                        // Même évènement relu après les enfants : un élément cliquable l'a-t-il pris ?
                                        val final = awaitPointerEvent(PointerEventPass.Final)
                                        val takenByChild = final.changes.firstOrNull { it.id == change.id }?.isConsumed ?: false
                                        if (!cancelled && quick && !takenByChild) {
                                            val kind = counter.onTap(change.uptimeMillis)
                                            if (kind != null) {
                                                // Carnet : on mémorise la pluie déclenchée (succès secrets « pluies cachées »).
                                                val key = kind.name.lowercase() // "leaves", "rain", "mushrooms"
                                                AppGraph.appScope.launch { runCatching { AppGraph.settings.addRainSeen(key) } }
                                                rain = kind
                                                rainId++
                                            }
                                        }
                                    }
                                    tracking && change.pressed -> {
                                        // Un glissement n'est pas un appui (on ne regarde que la distance, pas la consommation).
                                        val moved = (change.position - start).getDistance() > viewConfiguration.touchSlop
                                        if (moved) cancelled = true
                                    }
                                }
                            }
                        }
                    },
            ) { content() }
        }

        // Pluie dessinée par-dessus : un Canvas sans pointerInput ne capte aucun appui.
        val kind = rain
        if (kind != null) {
            RainLayer(kind, rainId) { rain = null }
        }
    }
}

/**
 * Compteur d'appuis rapprochés : renvoie la pluie à lancer au 6e, 12e ou 24e appui
 * (puis repart de zéro après le 24e), null sinon.
 */
private class TapCounter {
    private var count = 0
    private var lastTap = 0L

    fun onTap(time: Long): RainKind? {
        count = if (time - lastTap <= MAX_GAP_MS) count + 1 else 1
        lastTap = time
        return when (count) {
            6 -> RainKind.LEAVES
            12 -> RainKind.RAIN
            24 -> RainKind.MUSHROOMS.also { count = 0 }
            else -> null
        }
    }
}

/** Anime une pluie pendant ~4 s puis appelle [onDone]. [id] relance l'animation à chaque déclenchement. */
@Composable
private fun RainLayer(kind: RainKind, id: Int, onDone: () -> Unit) {
    val particles = remember(id) { makeParticles(kind) }
    var t by remember(id) { mutableFloatStateOf(0f) }

    LaunchedEffect(id) {
        val start = withFrameMillis { it }
        var now = start
        while (now - start < RAIN_DURATION_MS) {
            now = withFrameMillis { it }
            t = (now - start) / RAIN_DURATION_MS.toFloat()
        }
        onDone()
    }

    Canvas(Modifier.fillMaxSize()) {
        // Fondu à la fin pour ne pas couper net.
        val alpha = if (t > 0.85f) ((1f - t) / 0.15f).coerceIn(0f, 1f) else 1f
        for (p in particles) {
            // Chaque particule tombe du haut (-10 %) au bas (+110 %), avec son propre retard.
            val local = (t - p.delay) * p.speed
            if (local <= 0f || local >= 1.2f) continue
            val y = -0.1f * size.height + local * size.height * 1.2f
            when (kind) {
                RainKind.LEAVES -> drawLeaf(p, y, t, alpha)
                RainKind.RAIN -> drawDrop(p, y, alpha)
                RainKind.MUSHROOMS -> drawMushroomSilhouette(p, y, t, alpha)
            }
        }
    }
}

/** Crée les particules d'une pluie (nombre, couleurs et tailles selon le type). */
private fun makeParticles(kind: RainKind): List<Particle> {
    val rnd = Random(System.nanoTime())
    val (count, colors) = when (kind) {
        RainKind.LEAVES -> 34 to listOf(Color(0xFFD08A2E), Color(0xFFB0522A), Color(0xFFE0B040), Color(0xFF8A9A3A))
        RainKind.RAIN -> 110 to listOf(Color(0xFF6FA8DC), Color(0xFF8FBCE6), Color(0xFF5A8FC8))
        RainKind.MUSHROOMS -> 30 to listOf(Color(0xFFD83A2E), Color(0xFF8A4B22), Color(0xFFF2A93B), Color(0xFFE8D7B8), Color(0xFF7B2FF7))
    }
    return List(count) {
        Particle(
            x = rnd.nextFloat(),
            delay = rnd.nextFloat() * 0.45f,
            speed = when (kind) {
                RainKind.RAIN -> 2.6f + rnd.nextFloat() * 1.2f
                else -> 1.3f + rnd.nextFloat() * 0.8f
            },
            size = when (kind) {
                RainKind.LEAVES -> 9f + rnd.nextFloat() * 8f
                RainKind.RAIN -> 14f + rnd.nextFloat() * 14f
                RainKind.MUSHROOMS -> 14f + rnd.nextFloat() * 12f
            },
            phase = rnd.nextFloat() * 6.28f,
            spin = (rnd.nextFloat() - 0.5f) * 720f,
            color = colors[rnd.nextInt(colors.size)],
        )
    }
}

/** Feuille qui tombe en se balançant et en tournoyant. */
private fun DrawScope.drawLeaf(p: Particle, y: Float, t: Float, alpha: Float) {
    val s = p.size * density / 2.5f
    val x = p.x * size.width + sin(t * 9f + p.phase) * 28f * density
    rotate(p.spin * t + p.phase * 57f, Offset(x, y)) {
        val leaf = path {
            moveTo(x - s, y)
            cubicTo(x - s * 0.4f, y - s * 0.9f, x + s * 0.4f, y - s * 0.9f, x + s, y)
            cubicTo(x + s * 0.4f, y + s * 0.9f, x - s * 0.4f, y + s * 0.9f, x - s, y)
            close()
        }
        drawPath(leaf, p.color.copy(alpha = alpha))
        drawLine(p.color.copy(alpha = alpha * 0.6f).compositeDark(), Offset(x - s, y), Offset(x + s, y), 1.2f * density)
    }
}

/** Goutte de pluie : trait légèrement incliné. */
private fun DrawScope.drawDrop(p: Particle, y: Float, alpha: Float) {
    val x = p.x * size.width
    val len = p.size * density / 2f
    drawLine(
        p.color.copy(alpha = 0.8f * alpha),
        Offset(x, y),
        Offset(x - len * 0.25f, y + len),
        2f * density,
        StrokeCap.Round,
    )
}

/** Silhouette simple de champignon (chapeau + pied), qui oscille doucement. */
private fun DrawScope.drawMushroomSilhouette(p: Particle, y: Float, t: Float, alpha: Float) {
    val s = p.size * density / 2f
    val x = p.x * size.width + sin(t * 5f + p.phase) * 14f * density
    rotate(sin(t * 7f + p.phase) * 25f, Offset(x, y)) {
        // Pied
        drawRoundRect(
            Color(0xFFF2EEE4).copy(alpha = alpha),
            topLeft = Offset(x - s * 0.28f, y),
            size = androidx.compose.ui.geometry.Size(s * 0.56f, s * 0.9f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.2f),
        )
        // Chapeau
        drawPath(dome(x - s, x + s, y + s * 0.1f, y - s * 0.8f), p.color.copy(alpha = alpha))
    }
}

/** Assombrit une couleur (nervure de feuille). */
private fun Color.compositeDark(): Color = Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha)
