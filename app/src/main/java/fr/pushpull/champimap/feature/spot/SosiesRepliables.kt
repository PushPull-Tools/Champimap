package fr.pushpull.champimap.feature.spot

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.core.db.Species

/**
 * Avertissement compact et dépliable : une ligne (icône, titre, chevron), repliée par défaut.
 * Dépliée : la phrase d'avertissement puis les sosies (touchables, ouvrent la fiche).
 * Fine bordure rouge, fond du thème : visible sans écraser l'écran.
 */
@Composable
fun SosiesRepliables(
    titre: String,
    texte: String?,
    sosies: List<Species>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ouvert by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val depliable = !texte.isNullOrBlank() || sosies.isNotEmpty()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.error),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(if (depliable) Modifier.clickable { ouvert = !ouvert } else Modifier)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    champiText(titre),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (depliable) {
                    Icon(
                        if (ouvert) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (ouvert) "Replier" else "Déplier",
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
            if (ouvert && depliable) {
                HorizontalDivider(color = scheme.outlineVariant)
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    texte?.takeIf { it.isNotBlank() }?.let {
                        Text(champiText(it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    sosies.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onOpen(o.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SpeciesDot(o, 10)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                champiText(o.nameFr),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            EdibilityChip(o.edibility)
                        }
                    }
                }
            }
        }
    }
}
