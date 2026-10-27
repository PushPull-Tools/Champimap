package fr.pushpull.champimap.feature.reglages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.TrashEntry
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Corbeille : spots, sorties, récoltes, espèces de récolte et photos supprimés.
 * Restaurer ou supprimer définitivement (avec confirmation). Purge auto au-delà de 30 jours
 * à l'ouverture de l'écran.
 */

private val TRASH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

/** Libellé du type d'élément. */
private fun kindLabel(kind: String): String = when (kind) {
    "SPOT" -> "Spot"
    "SORTIE" -> "Sortie"
    "RECOLTE" -> "Récolte"
    "ITEM" -> "Espèce d'une récolte"
    "PHOTO" -> "Photo"
    "VENTE" -> "Vente"
    else -> "Élément"
}

/** Corbeille. Signature appelée par AppNav. */
@Composable
fun CorbeilleScreen(nav: NavController) {
    val entries by remember { AppGraph.db.backupDao().trash() }.collectAsState(initial = emptyList())
    var toDelete by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    // Purge automatique à l'ouverture (éléments de plus de 30 jours).
    LaunchedEffect(Unit) {
        runCatching { TrashManager.purgeOld() }.onFailure { ErrorLogger.log("Corbeille", "Purge automatique impossible", it) }
    }

    ChampiScreen(title = "Corbeille", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                champiText("Les éléments supprimés restent ici 30 jours, puis disparaissent définitivement."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (entries.isEmpty()) {
                    item { ChampiCard(Modifier.fillMaxWidth()) { Text(champiText("La corbeille est vide.")) } }
                }
                items(entries, key = { it.kind + it.id }) { e ->
                    TrashItem(
                        e,
                        onRestore = {
                            AppGraph.appScope.launch {
                                runCatching { TrashManager.restore(e) }.onFailure { ErrorLogger.log("Corbeille", "Restauration impossible", it) }
                            }
                        },
                        onDelete = { toDelete = e },
                    )
                }
            }
            if (entries.isNotEmpty()) {
                ChampiOutlinedButton("Vider la corbeille", onClick = { confirmEmpty = true }, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }

    toDelete?.let { e ->
        ConfirmDialog(
            title = "Supprimer définitivement ?",
            text = "${kindLabel(e.kind)} : impossible de revenir en arrière." +
                if (e.kind == "SPOT" || e.kind == "SORTIE") " Ses récoltes et photos seront aussi effacées." else "",
            onConfirm = {
                toDelete = null
                AppGraph.appScope.launch {
                    runCatching { TrashManager.deleteForever(e) }.onFailure { ErrorLogger.log("Corbeille", "Suppression définitive impossible", it) }
                }
            },
            onDismiss = { toDelete = null },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Vider la corbeille ?",
            text = "Tous les éléments (${entries.size}) seront effacés définitivement.",
            confirmLabel = "Vider",
            onConfirm = {
                confirmEmpty = false
                val all = entries
                AppGraph.appScope.launch {
                    all.forEach { e -> runCatching { TrashManager.deleteForever(e) }.onFailure { ErrorLogger.log("Corbeille", "Suppression définitive impossible", it) } }
                }
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}

/** Un élément de la corbeille, avec jours restants. */
@Composable
private fun TrashItem(e: TrashEntry, onRestore: () -> Unit, onDelete: () -> Unit) {
    val daysLeft = ((e.deletedAt + TrashManager.RETENTION_MS - System.currentTimeMillis()) / (24 * 3600 * 1000L)).coerceAtLeast(0)
    val date = Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(TRASH_FMT)
    // Le libellé d'une photo est son type de propriétaire : on affiche plutôt « Photo ».
    val label = if (e.kind == "PHOTO") null else e.label?.takeIf { it.isNotBlank() }
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(champiText(label ?: kindLabel(e.kind)), style = MaterialTheme.typography.titleMedium)
        Text(
            champiText("${kindLabel(e.kind)} · $date · encore $daysLeft j"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChampiButton("Restaurer", onClick = onRestore, modifier = Modifier.weight(1f))
            ChampiOutlinedButton("Supprimer", onClick = onDelete, modifier = Modifier.weight(1f))
        }
    }
}
