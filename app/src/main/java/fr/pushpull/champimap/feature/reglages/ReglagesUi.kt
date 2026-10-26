package fr.pushpull.champimap.feature.reglages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Petits composants propres au module « réglages » : logo, section repliable,
 * ligne d'option avec interrupteur, ligne-lien, ouverture du navigateur.
 */

/** Logo Champimap : épingle verte avec un cœur orange, dessiné en Compose (aucune image). */
@Composable
fun ChampiLogo(size: Dp = 72.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = w * 0.36f
        val cx = w / 2f
        val cy = r + h * 0.06f
        // Épingle : disque + pointe vers le bas.
        val pin = Path().apply {
            moveTo(cx - r * 0.82f, cy + r * 0.58f)
            lineTo(cx, h * 0.97f)
            lineTo(cx + r * 0.82f, cy + r * 0.58f)
            close()
        }
        drawPath(pin, ChampiColors.Mousse)
        drawCircle(ChampiColors.Mousse, radius = r, center = Offset(cx, cy))
        // Cœur orange au centre (deux lobes + pointe).
        val hr = r * 0.26f
        drawCircle(ChampiColors.Girolle, radius = hr, center = Offset(cx - hr * 0.85f, cy - hr * 0.3f))
        drawCircle(ChampiColors.Girolle, radius = hr, center = Offset(cx + hr * 0.85f, cy - hr * 0.3f))
        val tip = Path().apply {
            moveTo(cx - hr * 1.78f, cy - hr * 0.05f)
            lineTo(cx, cy + hr * 1.9f)
            lineTo(cx + hr * 1.78f, cy - hr * 0.05f)
            close()
        }
        drawPath(tip, ChampiColors.Girolle)
    }
}

/**
 * Section repliable des paramètres : titre + bouton « + » / « − ».
 * L'état replié est stocké par l'appelant (réglage `collapsedSections`).
 */
@Composable
fun CollapsibleSection(
    title: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    ChampiCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(champiText(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(
                if (collapsed) Icons.Filled.Add else Icons.Filled.Remove,
                contentDescription = if (collapsed) "Déplier" else "Replier",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (!collapsed) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

/** Option avec interrupteur et explication sous le titre. */
@Composable
fun SwitchRow(title: String, explanation: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(champiText(title), style = MaterialTheme.typography.titleMedium)
            Text(champiText(explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/** Ligne cliquable menant à un autre écran (flèche à droite). */
@Composable
fun LinkRow(title: String, explanation: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(champiText(title), style = MaterialTheme.typography.titleMedium)
            Text(champiText(explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/** Ouvre une adresse https dans le navigateur du téléphone (l'appli elle-même n'a pas Internet). */
fun openInBrowser(context: Context, url: String, screen: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        ErrorLogger.log(screen, "Aucun navigateur pour ouvrir le lien", e)
    }
}
