package fr.pushpull.champimap.feature.bonus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.settings.ThemeMode
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/*
 * Mode « Mushroom party » (thème Psyché) : déblocage caché + étirement élastique.
 *
 * Déblocage : tirer fort vers le haut en partant du bas de l'écran (au-dessus de la zone des gestes
 * Android, sinon c'est le système qui prend le geste « Accueil »). Le même geste, plus appuyé,
 * revient au thème Auto quand le mode party est actif.
 *
 * « Tirage » mesuré de deux façons :
 *  - écran sans défilement (ou liste qui n'a pas bougé) : distance parcourue par le doigt vers le haut ;
 *  - liste qui défile : seulement le SUR-défilement (ce que la liste n'a pas pu consommer une fois en bas).
 * Un simple défilement de liste ne déclenche donc jamais rien.
 *
 * Étirement (mode party uniquement) : pendant le tirage, PartyStretch passe de 0 à ~1 (élastique, jamais
 * au-delà) ; au lâcher, retour par un ressort peu amorti (rebond). Le fond psyché ondule, l'écran s'étire
 * depuis le bas et les cartes vibrent (voir PsycheEffects.kt, qui lit LocalPartyStretch).
 *
 * Le détecteur OBSERVE seulement (passe Initial, rien n'est consommé) : listes, boutons et cartes
 * fonctionnent normalement en dessous.
 */

/** Le geste de déblocage doit commencer dans cette part basse de l'écran (40 %). */
private const val START_ZONE = 0.40f

/** Tirage minimal pour activer le mode party (part de la hauteur d'écran). */
private const val UNLOCK_PULL = 0.30f

/** Tirage minimal, plus appuyé, pour quitter le mode party (on peut jouer avec l'étirement avant). */
private const val EXIT_PULL = 0.55f

/** Durée maximale du geste de déblocage : il doit être franc, pas un lent défilement. */
private const val MAX_DURATION_MS = 1_200L

/** Tirage (part de la hauteur) qui donne ~63 % de l'étirement maximal (courbe élastique). */
private const val STRETCH_SCALE = 0.30f

/**
 * État partagé de l'étirement élastique du mode party.
 * [value] : 0 au repos, tend vers 1 en tirant ; légèrement négatif pendant le rebond du retour.
 * [clockMs] : horloge qui n'avance que pendant l'étirement (ondulations, vibrations).
 */
@Stable
class PartyStretch internal constructor() {
    internal val amount = Animatable(0f)
    var clockMs by mutableLongStateOf(0L)
        internal set
    val value: Float get() = amount.value
}

/** Étirement courant du mode party (null hors de PartyUnlockDetector, ex. aperçus). */
val LocalPartyStretch = staticCompositionLocalOf<PartyStretch?> { null }

/**
 * Observe les défilements imbriqués des écrans pendant le geste :
 * note si un contenu a vraiment défilé, et cumule le sur-défilement vers le haut.
 */
private class ScrollWatcher : NestedScrollConnection {
    var contentScrolled = false
    var overscrollUp = 0f

    fun reset() {
        contentScrolled = false
        overscrollUp = 0f
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) contentScrolled = true
        // Doigt qui monte = delta négatif ; ce que la liste n'a pas pu consommer = sur-défilement.
        if (available.y < 0f) overscrollUp += -available.y
        return Offset.Zero // on ne consomme rien
    }
}

/** Enveloppe l'appli : bascule du thème Psyché sur un fort tirage vers le haut, et étirement en mode party. */
@Composable
fun PartyUnlockDetector(content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val watcher = remember { ScrollWatcher() }
    val stretch = remember { PartyStretch() }
    val scope = rememberCoroutineScope()
    // Valeurs lues dans la boucle d'appuis (qui, elle, n'est jamais relancée).
    val psyche by rememberUpdatedState(LocalChampiStyle.current.psyche)
    val gestureInsetPx by rememberUpdatedState(WindowInsets.systemGestures.getBottom(LocalDensity.current))

    // Horloge des ondulations : ne tourne que pendant l'étirement (aucun coût au repos).
    LaunchedEffect(stretch) {
        snapshotFlow { abs(stretch.value) > 0.002f }
            .distinctUntilChanged()
            .collectLatest { moving ->
                while (moving) withFrameMillis { stretch.clockMs = it }
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(watcher)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // État du geste en cours (un seul doigt suivi).
                    var tracking = false
                    var unlockCandidate = false
                    var fired = false
                    var startX = 0f
                    var startY = 0f
                    var startTime = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val height = size.height.toFloat()
                        when {
                            // Début : tout tirage peut étirer ; seul un geste né en bas peut (dé)bloquer.
                            change.changedToDownIgnoreConsumed() -> {
                                val y = change.position.y
                                tracking = height > 0f
                                unlockCandidate = tracking && y >= height * (1f - START_ZONE) && y <= height - gestureInsetPx
                                fired = false
                                startX = change.position.x
                                startY = y
                                startTime = change.uptimeMillis
                                watcher.reset()
                            }
                            // Doigt levé (ou plusieurs doigts) : fin du geste, retour élastique.
                            !change.pressed || event.changes.size > 1 -> {
                                if (tracking) scope.launch { releaseStretch(stretch) }
                                tracking = false
                                unlockCandidate = false
                            }
                            tracking -> {
                                val rawUp = startY - change.position.y
                                // Liste qui défile : seul le sur-défilement compte ; sinon le trajet du doigt.
                                val pull = if (watcher.contentScrolled) watcher.overscrollUp else max(0f, rawUp)

                                // Étirement élastique, uniquement en mode party.
                                if (psyche) {
                                    val target = 1f - exp(-pull / (height * STRETCH_SCALE))
                                    scope.launch { stretch.amount.snapTo(target) }
                                }

                                // (Dé)blocage : assez tiré, assez vite, plutôt vertical.
                                if (unlockCandidate && !fired) {
                                    val dx = abs(change.position.x - startX)
                                    val elapsed = change.uptimeMillis - startTime
                                    val needed = height * (if (psyche) EXIT_PULL else UNLOCK_PULL)
                                    if (elapsed > MAX_DURATION_MS) {
                                        unlockCandidate = false // trop lent : geste ordinaire
                                    } else if (pull > needed && dx < max(rawUp, pull)) {
                                        fired = true
                                        unlockCandidate = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        togglePsyche()
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalPartyStretch provides stretch) { content() }
    }
}

/** Relâchement : retour à 0 par un ressort peu amorti (léger rebond, effet « élastique »). */
private suspend fun releaseStretch(stretch: PartyStretch) {
    if (stretch.value == 0f) return
    stretch.amount.animateTo(
        targetValue = 0f,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMediumLow),
    )
}

/**
 * Active Psyché (et le débloque dans la liste des thèmes), ou revient en Auto
 * si Psyché est déjà actif. Écrit dans les réglages en arrière-plan.
 */
private fun togglePsyche() {
    AppGraph.appScope.launch {
        runCatching {
            val repo = AppGraph.settings
            val s = repo.flow.first()
            if (s.psycheUnlocked && s.themeMode == ThemeMode.PSYCHE) {
                repo.setThemeMode(ThemeMode.AUTO)
            } else {
                repo.setPsycheUnlocked(true)
                repo.setThemeMode(ThemeMode.PSYCHE)
            }
        }.onFailure { ErrorLogger.log("Bonus", "Bascule du thème Psyché impossible", it) }
    }
}
