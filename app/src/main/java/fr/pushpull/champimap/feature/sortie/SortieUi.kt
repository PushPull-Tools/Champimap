package fr.pushpull.champimap.feature.sortie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.theme.SafetyTheme
import fr.pushpull.champimap.core.util.AstroCalc
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/*
 * Petits éléments d'interface partagés par les écrans de sortie :
 * horloge, minutes avant la nuit en forêt, période de chasse, bandeaux d'alerte.
 */

/** Heure courante, rafraîchie toutes les `periodMs` (pour les durées affichées). */
@Composable
fun rememberNow(periodMs: Long = 30_000): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

/** Minutes avant la nuit noire en forêt (négatif = déjà nuit), null si incalculable. */
fun minutesBeforeForestDark(lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()): Long? {
    val dark = AstroCalc.forestDark(LocalDate.now(), lat, lon) ?: return null
    val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), dark.zone)
    return Duration.between(now, dark).toMinutes()
}

/** « 1 h 34 avant la nuit en forêt » / « Il fait nuit en forêt ». */
fun nightText(minutes: Long?): String = when {
    minutes == null -> "Heure de la nuit inconnue"
    minutes <= 0 -> "Il fait nuit en forêt"
    else -> Geo.formatDuration(minutes * 60_000) + " avant la nuit en forêt"
}

/** Période de chasse : de début septembre à fin février. */
fun isHuntingSeason(date: LocalDate = LocalDate.now()): Boolean = date.monthValue >= 9 || date.monthValue <= 2

/**
 * Bandeau d'alerte (sécurité) : toujours en thème normal, jamais en thème party.
 * @param onClose null → pas de croix.
 */
@Composable
fun SafetyBanner(
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    strong: Boolean = true,
    onClose: (() -> Unit)? = null,
) {
    SafetyTheme {
        val bg = if (strong) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        val fg = if (strong) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        Row(
            modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(champiText(title), color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                if (text != null) Text(champiText(text), color = fg, style = MaterialTheme.typography.bodyMedium)
            }
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Masquer", tint = fg)
                }
            }
        }
    }
}

/** Pastille d'état sur la carte (fond semi-opaque pour rester lisible sur le fond de carte). */
@Composable
fun MapPill(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Text(
        champiText(text),
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(bg, RoundedCornerShape(15.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** Couleur Compose depuis « #RRGGBB ». */
fun colorFromHex(hex: String?): Color = Color(MapIcons.parseColor(hex))
