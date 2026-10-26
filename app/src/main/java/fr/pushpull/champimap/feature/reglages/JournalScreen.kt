package fr.pushpull.champimap.feature.reglages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.BuildConfig
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.ErrorLog
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
 * Journal : uniquement les erreurs (jamais de position ni de nom de spot), avec détail
 * technique repliable, export en fichier texte (choix de l'emplacement par Android) et « Vider ».
 */

private val LOG_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)

private fun fmt(t: Long): String = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(LOG_FMT)

/** Journal. Signature appelée par AppNav. */
@Composable
fun JournalScreen(nav: NavController) {
    val context = LocalContext.current
    val logs by remember { AppGraph.db.errorLogDao().all() }.collectAsState(initial = emptyList())
    var confirmClear by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    // Export texte : Android demande où enregistrer le fichier.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        AppGraph.appScope.launch {
            val ok = runCatching {
                val text = buildText(AppGraph.db.errorLogDao().allOnce())
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("Flux d'écriture indisponible")
            }.onFailure { ErrorLogger.log("Journal", "Export du journal impossible", it) }.isSuccess
            info = if (ok) "Journal exporté." else "L'export n'a pas fonctionné."
        }
    }

    ChampiScreen(title = "Journal", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                champiText("Seulement ce qui n'a pas fonctionné, gardé 30 jours. Aucune position ni nom de spot n'y est écrit."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            info?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (logs.isEmpty()) {
                    item {
                        ChampiCard(Modifier.fillMaxWidth()) { Text(champiText("Aucune erreur. Tout va bien !")) }
                    }
                }
                items(logs, key = { it.id }) { LogItem(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                ChampiButton("Exporter", enabled = logs.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                    exportLauncher.launch("champimap-journal.txt")
                })
                ChampiOutlinedButton("Vider", enabled = logs.isNotEmpty(), modifier = Modifier.weight(1f), onClick = { confirmClear = true })
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Vider le journal ?",
            text = "Toutes les erreurs enregistrées seront effacées.",
            confirmLabel = "Vider",
            onConfirm = {
                confirmClear = false
                AppGraph.appScope.launch { runCatching { AppGraph.db.errorLogDao().clear() } }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** Une erreur : date, écran, message ; détail technique repliable. */
@Composable
private fun LogItem(e: ErrorLog) {
    var open by remember { mutableStateOf(false) }
    ChampiCard(Modifier.fillMaxWidth().clickable(enabled = e.detail != null) { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${fmt(e.time)} · ${e.screen}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.message, style = MaterialTheme.typography.bodyLarge)
            }
            if (e.detail != null) {
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Replier" else "Voir le détail")
            }
        }
        if (open && e.detail != null) {
            Text(e.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Texte de l'export : en-tête (version, Android) puis chaque erreur. */
private fun buildText(logs: List<ErrorLog>): String = buildString {
    appendLine("Champimap ${BuildConfig.VERSION_NAME} — journal des erreurs")
    appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MODEL}")
    appendLine()
    logs.forEach { e ->
        appendLine("[${fmt(e.time)}] ${e.screen} : ${e.message}")
        e.detail?.let { appendLine(it) }
        appendLine()
    }
}
