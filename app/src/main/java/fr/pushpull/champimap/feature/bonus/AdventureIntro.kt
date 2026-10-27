package fr.pushpull.champimap.feature.bonus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/*
 * « Début d'aventure » : au lancement d'une NOUVELLE sortie (jamais à la reprise d'une sortie en cours),
 *  1. un cercle se ferme façon iris sur l'écran de sortie (≈ 0,7 s) ;
 *  2. la phrase de départ (pioche StartMessages) s'affiche en surimpression (≈ 2 s) ;
 *  3. le cercle se rouvre sur la carte (≈ 0,6 s).
 * Un appui n'importe où passe directement à la réouverture.
 * Couleurs : uniquement celles du thème (fond « primary », texte « onPrimary »).
 * Animations coupées (« réduire les animations ») : le cercle saute directement, la phrase reste affichée.
 */

/** Durée de fermeture du cercle. */
private const val CLOSE_MS = 700

/** Durée d'affichage de la phrase, cercle fermé. */
private const val HOLD_MS = 2_000L

/** Durée de réouverture du cercle. */
private const val OPEN_MS = 600

/**
 * Demande d'intro, posée par l'écran Départ juste avant d'ouvrir la nouvelle sortie,
 * puis consommée une seule fois par l'écran de sortie.
 */
object AdventureIntro {
    @Volatile private var pendingSortieId: String? = null

    /** À appeler juste avant de naviguer vers une sortie qui vient d'être créée. */
    fun request(sortieId: String) {
        pendingSortieId = sortieId
    }

    /** Vrai (une seule fois) si l'intro a été demandée pour cette sortie. */
    @Synchronized
    fun consume(sortieId: String): Boolean {
        if (pendingSortieId != sortieId) return false
        pendingSortieId = null
        return true
    }
}

/**
 * Superposition « début d'aventure », à poser PAR-DESSUS l'écran de sortie (dernier élément affiché).
 * Ne fait rien si l'intro n'a pas été demandée pour [sortieId] (reprise d'une sortie, retour d'un spot…).
 */
@Composable
fun AdventureIntroOverlay(sortieId: String) {
    // Mémorisé avec l'écran : un retour depuis une fiche spot ne rejoue pas l'intro.
    var active by rememberSaveable(sortieId) { mutableStateOf(AdventureIntro.consume(sortieId)) }
    if (!active) return

    val anim = animationsEnabled()
    // Ouverture du cercle : 1 = grand ouvert (écran visible), 0 = fermé (écran couvert).
    val iris = remember { Animatable(1f) }
    var message by remember { mutableStateOf("") }
    var skip by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        message = runCatching { StartMessages.next() }.getOrNull() ?: StartMessages.ALL.first()

        // MÉLODIE (plus tard) : point d'accroche du son de départ.
        // Lancer ici la courte mélodie originale (flûte douce), en respectant un futur réglage
        // « son de départ » (coupable), puis la laisser finir seule : l'intro ne l'attend pas.

        // 1. Fermeture du cercle (interrompue si l'utilisateur touche l'écran).
        val closing = launch {
            if (anim) iris.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing)) else iris.snapTo(0f)
        }
        // 2. Phrase affichée, jusqu'à la fin du délai ou un appui.
        withTimeoutOrNull(CLOSE_MS + HOLD_MS) { snapshotFlow { skip }.first { it } }
        closing.cancel()
        // 3. Réouverture sur la carte, puis la superposition disparaît.
        if (anim) iris.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing)) else iris.snapTo(1f)
        active = false
    }

    val scheme = MaterialTheme.colorScheme
    val cover = scheme.primary
    Box(
        Modifier
            .fillMaxSize()
            // Bloque la carte pendant l'intro ; un appui passe à la suite.
            .pointerInput(Unit) { detectTapGestures { skip = true } }
            .drawBehind {
                // Voile plein écran percé d'un trou rond centré (règle pair-impair).
                val maxRadius = sqrt(size.width * size.width + size.height * size.height) / 2f
                val hole = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(center, maxRadius * iris.value))
                }
                drawPath(hole, cover)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                // La phrase apparaît quand le cercle se referme et s'efface quand il se rouvre.
                .graphicsLayer { alpha = ((0.45f - iris.value) / 0.45f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                champiText(message),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                "Touche l'écran pour passer",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimary.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
