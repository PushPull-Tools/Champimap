package fr.pushpull.champimap.connect.ui

import androidx.compose.foundation.background
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
import java.util.Locale
import fr.pushpull.champimap.connect.DeptProgress
import fr.pushpull.champimap.connect.ItemStatus
import fr.pushpull.champimap.connect.SyncItem
import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.SyncSnapshot

/*
 * Écran d'avancement de la synchronisation :
 *  - état général (« En cours… », « Terminé ✓ », « Échec ») + pourcentage global + ligne de résumé
 *    (« 3 tâches sur 9 · 42 % · Carte · Haute-Vienne ») ;
 *  - les vraies tâches, dans l'ordre réel d'exécution, regroupées : un bloc par département
 *    (pourcentage du département), puis « Général » ; tâche en cours mise en évidence ;
 *  - erreurs éventuelles, en clair ;
 *  - bouton Annuler (pendant) ou Fermer (après), avec le rappel « Conseillé : en Wi-Fi ».
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
                // Un bloc par groupe, dans l'ordre réel : départements puis « Général ».
                taskGroups(snapshot.items).forEach { (group, tasks) ->
                    val dept = snapshot.departements.firstOrNull { d -> tasks.any { it.departement == d.code } }
                    item(key = "g_$group") { GroupHeader(group, tasks, dept) }
                    items(tasks, key = { "i_" + it.id }) { ItemRow(it) }
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
        s.finished && s.hasResult && s.errors.isEmpty() -> "Terminé ✓" to MaterialTheme.colorScheme.primary
        s.finished && s.hasResult -> "Terminé ✓ (avec des erreurs)" to ConnectColors.Orange
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
            Spacer(Modifier.height(8.dp))
            // Même ligne de résumé que dans Champimap et la notification.
            Text(s.summary(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Groupes de tâches dans l'ordre réel : chaque département dans l'ordre de sa première tâche,
 * puis le bloc « Général » (tâches sans département).
 */
private fun taskGroups(items: List<SyncItem>): List<Pair<String, List<SyncItem>>> {
    val depts = items.filter { it.departement != null }.groupBy { it.group }.toList()
    val general = items.filter { it.departement == null }
    return if (general.isEmpty()) depts else depts + (SyncProtocol.GROUP_GENERAL to general)
}

/** Titre d'un groupe : nom, tâches traitées / total et, pour un département, son pourcentage. */
@Composable
private fun GroupHeader(group: String, tasks: List<SyncItem>, dept: DeptProgress?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            val done = tasks.count { it.isProcessed }
            Text(
                if (dept != null) "$done/${tasks.size} · ${dept.percent} %" else "$done/${tasks.size}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (dept != null) {
            Spacer(Modifier.height(4.dp))
            Progress(dept.percent, statusColor(dept.status))
        }
    }
}

/**
 * Ligne d'une tâche : symbole d'état, libellé court, état à droite (%, « Déjà à jour », « Annulé », taille),
 * détail (message d'erreur en rouge) et barre si elle est en cours. La tâche en cours est surlignée.
 */
@Composable
private fun ItemRow(i: SyncItem) {
    val running = i.status == ItemStatus.RUNNING
    val bg = if (running) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(statusSymbol(i.status), color = statusColor(i.status), fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    i.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (running) FontWeight.Bold else FontWeight.Normal,
                )
                statusText(i)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = statusColor(i.status, muted = !running)) }
            }
            i.detail?.takeIf { it.isNotBlank() && i.status != ItemStatus.SKIPPED }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = statusColor(i.status, muted = true))
            }
            if (running) {
                Spacer(Modifier.height(4.dp))
                Progress(i.percent, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Texte d'état à droite d'une tâche (null = rien). */
private fun statusText(i: SyncItem): String? = when (i.status) {
    ItemStatus.RUNNING -> "${i.percent} %"
    ItemStatus.SKIPPED -> "Déjà à jour"
    ItemStatus.CANCELLED -> "Annulé"
    ItemStatus.ERROR -> "Erreur"
    ItemStatus.DONE -> i.sizeBytes?.let { sizeText(it) }
    ItemStatus.WAITING -> null
}

/** Taille lisible : « 35,2 Mo » ou « 830 Ko ». */
private fun sizeText(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.FRANCE, "%.1f Mo", bytes / 1048576.0)
    else String.format(Locale.FRANCE, "%d Ko", (bytes + 1023) / 1024)

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
        // Rappel permanent : les cartes et le modèle sont lourds.
        Spacer(Modifier.height(4.dp))
        Text(
            WIFI_REMINDER,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
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
    ItemStatus.SKIPPED -> "✓"
    ItemStatus.CANCELLED -> "–"
}

/** Couleur de l'état. */
@Composable
private fun statusColor(s: ItemStatus, muted: Boolean = false): Color = when (s) {
    ItemStatus.DONE, ItemStatus.SKIPPED -> if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    ItemStatus.ERROR -> MaterialTheme.colorScheme.error
    ItemStatus.RUNNING -> if (muted) MaterialTheme.colorScheme.onSurfaceVariant else ConnectColors.Orange
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
