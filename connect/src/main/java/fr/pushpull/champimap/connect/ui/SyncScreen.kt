package fr.pushpull.champimap.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pushpull.champimap.connect.DeptProgress
import fr.pushpull.champimap.connect.ItemStatus
import fr.pushpull.champimap.connect.SyncItem
import fr.pushpull.champimap.connect.SyncSnapshot

/*
 * Écran d'avancement de la synchronisation :
 *  - état général (« En cours… », « Terminé ✓ », « Échec ») + étape en cours + pourcentage global ;
 *  - avancement par département ;
 *  - liste détaillée des éléments (contours, urgences, météo, cartes) avec leur état ;
 *  - erreurs éventuelles, en clair ;
 *  - bouton Annuler (pendant) ou Fermer (après).
 */

/** Écran complet. `onCancel` : annuler la synchro ; `onClose` : rendre la main à Champimap. */
@Composable
fun SyncScreen(snapshot: SyncSnapshot, onCancel: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(snapshot) }
                if (snapshot.departements.isNotEmpty()) {
                    item { SectionTitle("Par département") }
                    items(snapshot.departements, key = { "d_" + it.code }) { DeptRow(it) }
                }
                if (snapshot.items.isNotEmpty()) {
                    item { SectionTitle("Détail") }
                    items(snapshot.items, key = { "i_" + it.id }) { ItemRow(it) }
                }
                if (snapshot.errors.isNotEmpty()) {
                    item { ErrorsCard(snapshot.errors) }
                }
                item {
                    Text(
                        "Connect ne connaît que des zones larges (départements, carrés de ~25 km), jamais tes spots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            BottomButtons(snapshot, onCancel, onClose)
        }
    }
}

/** En-tête : titre, état général, étape et barre de progression globale. */
@Composable
private fun Header(s: SyncSnapshot) {
    val (state, color) = when {
        s.running -> "En cours…" to MaterialTheme.colorScheme.primary
        s.finished && s.files.isNotEmpty() && s.errors.isEmpty() -> "Terminé ✓" to MaterialTheme.colorScheme.primary
        s.finished && s.files.isNotEmpty() -> "Terminé ✓ (avec des erreurs)" to ConnectColors.Orange
        s.finished -> "Échec" to MaterialTheme.colorScheme.error
        else -> "Préparation…" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Champimap Connect", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(state, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("${s.percent} %", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Progress(s.percent, color)
            if (s.step.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(s.step, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Petit titre de section. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
    )
}

/** Ligne d'un département : nom, pourcentage, barre. */
@Composable
private fun DeptRow(d: DeptProgress) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.code} · ${d.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${statusSymbol(d.status)}  ${d.percent} %", color = statusColor(d.status), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Progress(d.percent, statusColor(d.status))
        }
    }
}

/** Ligne d'un élément : symbole d'état, libellé, détail et barre s'il est en cours. */
@Composable
private fun ItemRow(i: SyncItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Text(statusSymbol(i.status), color = statusColor(i.status), fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(i.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (i.status == ItemStatus.RUNNING) Text("${i.percent} %", style = MaterialTheme.typography.bodySmall)
            }
            i.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = statusColor(i.status, muted = true))
            }
            if (i.status == ItemStatus.RUNNING) {
                Spacer(Modifier.height(4.dp))
                Progress(i.percent, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Carte des erreurs (chaque raison sur sa ligne). */
@Composable
private fun ErrorsCard(errors: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Problèmes rencontrés", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Boutons du bas (≥ 56 dp) : Annuler pendant la synchro, Fermer ensuite. */
@Composable
private fun BottomButtons(s: SyncSnapshot, onCancel: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (s.running) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Annuler", fontSize = 18.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Tu peux revenir à Champimap : la synchro continue en arrière-plan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Fermer", fontSize = 18.sp)
            }
        }
    }
}

/** Barre de progression arrondie. */
@Composable
private fun Progress(percent: Int, color: Color) {
    LinearProgressIndicator(
        progress = { percent.coerceIn(0, 100) / 100f },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** Symbole lisible de l'état. */
private fun statusSymbol(s: ItemStatus): String = when (s) {
    ItemStatus.WAITING -> "○"
    ItemStatus.RUNNING -> "…"
    ItemStatus.DONE -> "✓"
    ItemStatus.ERROR -> "✗"
    ItemStatus.SKIPPED -> "–"
}

/** Couleur de l'état. */
@Composable
private fun statusColor(s: ItemStatus, muted: Boolean = false): Color = when (s) {
    ItemStatus.DONE -> if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    ItemStatus.ERROR -> MaterialTheme.colorScheme.error
    ItemStatus.RUNNING -> if (muted) MaterialTheme.colorScheme.onSurfaceVariant else ConnectColors.Orange
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
