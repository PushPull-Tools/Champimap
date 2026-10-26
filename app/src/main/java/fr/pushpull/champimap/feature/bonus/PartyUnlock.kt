package fr.pushpull.champimap.feature.bonus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Déblocage caché du mode « Mushroom party » (thème Psyché) :
 * tirer fort vers le haut en partant du bas de l'écran.
 * Le même geste, une fois en Psyché, revient au thème Auto.
 * Le détecteur OBSERVE seulement les appuis (passe Initial, rien n'est consommé) :
 * les listes, boutons et cartes en dessous fonctionnent normalement.
 */

/** Le geste doit commencer dans cette part basse de l'écran (15 %). */
private const val START_ZONE = 0.15f

/** Distance verticale minimale à parcourir vers le haut (35 % de la hauteur). */
private const val MIN_TRAVEL = 0.35f

/** Durée maximale du geste : il doit être franc, pas un lent défilement. */
private const val MAX_DURATION_MS = 900L

/** Enveloppe l'appli et bascule le thème Psyché sur un fort tirage vers le haut depuis le bas. */
@Composable
fun PartyUnlockDetector(content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // État du geste en cours (un seul doigt suivi).
                    var tracking = false
                    var fired = false
                    var startX = 0f
                    var startY = 0f
                    var startTime = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val height = size.height.toFloat()
                        when {
                            // Début : on ne suit que les gestes nés dans le bas de l'écran.
                            change.changedToDownIgnoreConsumed() -> {
                                tracking = height > 0f && change.position.y >= height * (1f - START_ZONE)
                                fired = false
                                startX = change.position.x
                                startY = change.position.y
                                startTime = change.uptimeMillis
                            }
                            // Doigt levé (ou plusieurs doigts) : fin du suivi.
                            !change.pressed || event.changes.size > 1 -> tracking = false
                            // Mouvement : assez haut, assez vite, plutôt vertical → bascule.
                            tracking && !fired -> {
                                val dy = startY - change.position.y
                                val dx = abs(change.position.x - startX)
                                val elapsed = change.uptimeMillis - startTime
                                if (elapsed > MAX_DURATION_MS) {
                                    tracking = false
                                } else if (dy > height * MIN_TRAVEL && dx < dy) {
                                    fired = true
                                    tracking = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    togglePsyche()
                                }
                            }
                        }
                    }
                }
            },
    ) { content() }
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
