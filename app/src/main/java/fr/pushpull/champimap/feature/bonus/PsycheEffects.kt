package fr.pushpull.champimap.feature.bonus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle

/*
 * SOCLE (version minimale) — le module « bonus » remplace ces implémentations
 * en gardant EXACTEMENT les mêmes signatures, utilisées par core/ui/Components.kt.
 */

/** Fond d'écran : couleur du thème, ou dégradé psychédélique animé en mode party. */
@Composable
fun PsycheBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/** Apparence d'une carte en mode party (dégradé animé + bordure arc-en-ciel tournante). */
@Composable
fun Modifier.psycheCard(): Modifier = this.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))

/**
 * Transforme un texte affiché selon les options : émojis liés au texte, « champignong ».
 * À appeler sur tout texte visible (les composants partagés le font déjà).
 */
@Composable
fun champiText(text: String): String {
    val style = LocalChampiStyle.current
    return if (style.champignong) champignongify(text) else text
}

/** « champignon(s) » → « champignong(s) », en respectant la majuscule. */
fun champignongify(text: String): String =
    text.replace(Regex("([Cc])hampignon(s?)\\b")) { m -> "${m.groupValues[1]}hampignong${m.groupValues[2]}" }

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
