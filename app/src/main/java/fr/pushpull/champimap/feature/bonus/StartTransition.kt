package fr.pushpull.champimap.feature.bonus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.CircularReveal
import fr.pushpull.champimap.core.ui.RevealMode
import fr.pushpull.champimap.core.ui.transitionContentColor
import fr.pushpull.champimap.core.ui.transitionEdgeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Pages de transition des sorties.
 *
 * Démarrage (NOUVELLE sortie seulement, jamais à la reprise) :
 *  1. écran Départ : le fond du thème envahit l'écran en cercle depuis « C'est parti ! » (DepartScreen) ;
 *  2. écran Sortie, couvert : la phrase de départ (pioche StartMessages) apparaît en surimpression ≈ 2 s,
 *     avec un fin trait qui se remplit pour montrer le temps restant ;
 *  3. un cercle s'ouvre depuis le centre sur la carte de la sortie.
 *  Un appui n'importe où passe directement à l'ouverture.
 *
 * Fin : fermeture circulaire sur l'écran de sortie (SortieScreen), puis récap animé (FinSortieScreen).
 *
 * « Réduire les animations » (Android) : aucun cercle, la phrase s'affiche puis disparaît.
 */

/** Durée d'apparition de la phrase. */
private const val TEXT_IN_MS = 450

/** Durée d'affichage de la phrase, écran couvert. */
private const val HOLD_MS = 2_000

/** Durée d'ouverture du cercle sur la carte. */
private const val OPEN_MS = 650

/**
 * Demande de transition « à usage unique » pour une sortie : posée juste avant de naviguer,
 * consommée une seule fois par l'écran d'arrivée (un retour depuis une fiche spot ne la rejoue pas).
 */
class TransitionRequest {
    @Volatile private var pendingSortieId: String? = null

    /** À appeler juste avant de naviguer vers l'écran qui jouera la transition. */
    fun request(sortieId: String) {
        pendingSortieId = sortieId
    }

    /** Vrai (une seule fois) si la transition a été demandée pour cette sortie. */
    @Synchronized
    fun consume(sortieId: String): Boolean {
        if (pendingSortieId != sortieId) return false
        pendingSortieId = null
        return true
    }
}

/** Les deux transitions des sorties. */
object SortieTransitions {
    /** Démarrage : Départ → Sortie (phrase + ouverture circulaire). */
    val start = TransitionRequest()

    /** Fin : Sortie → récap animé (compteurs, XP en direct, succès). */
    val end = TransitionRequest()
}

/**
 * Page de transition de démarrage, à poser PAR-DESSUS l'écran de sortie (dernier élément affiché).
 * Ne fait rien si elle n'a pas été demandée pour [sortieId] (reprise d'une sortie, retour d'un spot…).
 */
@Composable
fun StartTransitionOverlay(sortieId: String) {
    // Mémorisé avec l'écran : un retour depuis une fiche spot ne rejoue pas la transition.
    var active by rememberSaveable(sortieId) { mutableStateOf(SortieTransitions.start.consume(sortieId)) }
    if (!active) return

    val anim = animationsEnabled()
    // Ouverture du trou : 0 = écran couvert, 1 = carte entièrement visible.
    val iris = remember { Animatable(0f) }
    // Apparition de la phrase (fondu + léger zoom) et trait du temps restant.
    val textIn = remember { Animatable(0f) }
    val hold = remember { Animatable(0f) }
    var message by remember { mutableStateOf("") }
    var skip by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        message = runCatching { StartMessages.next() }.getOrNull() ?: StartMessages.ALL.first()
        if (anim) {
            // 1. Phrase qui apparaît, puis trait qui se remplit pendant l'attente.
            val intro = launch {
                textIn.animateTo(1f, tween(TEXT_IN_MS, easing = FastOutSlowInEasing))
                hold.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
            }
            // 2. Attente de la fin du délai ou d'un appui.
            withTimeoutOrNull((TEXT_IN_MS + HOLD_MS).toLong()) { snapshotFlow { skip }.first { it } }
            intro.cancel()
            // 3. La phrase s'efface pendant que le cercle s'ouvre sur la carte.
            launch { textIn.animateTo(0f, tween(OPEN_MS / 2)) }
            iris.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing))
        } else {
            // Animations réduites : phrase fixe, puis disparition directe.
            textIn.snapTo(1f)
            hold.snapTo(1f)
            withTimeoutOrNull(HOLD_MS.toLong()) { snapshotFlow { skip }.first { it } }
        }
        active = false
    }

    val textColor = transitionContentColor()
    val edge = transitionEdgeColor()
    CircularReveal(
        progress = { iris.value },
        mode = RevealMode.HOLE,
        // Bloque la carte pendant la transition ; un appui passe à la suite.
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { skip = true } },
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .graphicsLayer {
                    alpha = textIn.value
                    val sc = 0.92f + 0.08f * textIn.value
                    scaleX = sc
                    scaleY = sc
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                champiText(message),
                style = MaterialTheme.typography.headlineSmall,
                color = textColor,
                textAlign = TextAlign.Center,
            )
            // Trait du temps restant (fond discret + partie remplie couleur du thème).
            Box(
                Modifier
                    .padding(top = 20.dp)
                    .width(120.dp)
                    .height(3.dp)
                    .background(textColor.copy(alpha = 0.15f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .width(120.dp * hold.value)
                        .height(3.dp)
                        .background(edge, RoundedCornerShape(2.dp)),
                )
            }
            Text(
                "Touche l'écran pour passer",
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
