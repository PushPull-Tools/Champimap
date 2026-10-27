package fr.pushpull.champimap.feature.reglages

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Battue
import fr.pushpull.champimap.core.newId
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Jours de battue saisis à la main (section Sécurité des paramètres) :
 * le jour venu, l'Accueil affiche « Battue prévue aujourd'hui ». Ajout par calendrier,
 * note facultative, suppression. Les jours passés sont masqués de la liste.
 */

private val BATTUE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)

@Composable
fun BattueEditor() {
    var picking by remember { mutableStateOf(false) }
    val all by remember { AppGraph.db.battueDao().all() }.collectAsState(initial = emptyList())
    val today = LocalDate.now().toString()
    val upcoming = all.filter { it.date >= today }
    var note by remember { mutableStateOf("") }

    /** Enregistre un jour de battue (date ISO « 2026-11-14 »). */
    fun add(date: LocalDate) {
        val n = note.trim().takeIf { it.isNotEmpty() }
        AppGraph.appScope.launch {
            runCatching { AppGraph.db.battueDao().upsert(Battue(id = newId(), date = date.toString(), note = n)) }
                .onFailure { ErrorLogger.log("Paramètres", "Jour de battue non enregistré", it) }
        }
        note = ""
    }

    VSpace(8.dp)
    Text(champiText("Jours de battue"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(
        champiText("Note les battues annoncées (mairie, panneaux) : un bandeau te prévient le jour même."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    upcoming.forEach { b ->
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = runCatching { LocalDate.parse(b.date).format(BATTUE_FMT) }.getOrDefault(b.date)
            Text(
                label + (b.note?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                AppGraph.appScope.launch { runCatching { AppGraph.db.backupDao().deleteBattue(b.id) } }
            }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer ce jour de battue") }
        }
    }
    OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(60) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        label = { Text("Lieu ou note (facultatif)") },
    )
    VSpace(6.dp)
    ChampiOutlinedButton("Ajouter un jour de battue", onClick = { picking = true })

    if (picking) {
        BattueDatePicker(onPick = { picking = false; add(it) }, onDismiss = { picking = false })
    }
}

/** Millisecondes dans une journée (le DatePicker Material3 travaille en minuit UTC). */
private const val DAY_MS = 86_400_000L

/**
 * Calendrier Material3 (suit le thème clair / sombre de l'appli), en français :
 * la locale française donne des noms de mois et de jours français et une semaine qui commence le lundi.
 * Les jours passés ne sont pas sélectionnables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BattueDatePicker(onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayMs = LocalDate.now().toEpochDay() * DAY_MS
    val state = rememberDatePickerState(
        initialSelectedDateMillis = todayMs,
        yearRange = LocalDate.now().year..(LocalDate.now().year + 2),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayMs
        },
    )
    // Configuration forcée en français (mois, jours, premier jour de la semaine = lundi).
    val base = LocalConfiguration.current
    val frConfig = remember(base) { Configuration(base).apply { setLocale(Locale.FRANCE) } }
    val colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    CompositionLocalProvider(LocalConfiguration provides frConfig) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MS)) } ?: onDismiss() },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
            colors = colors,
        ) {
            DatePicker(state = state, colors = colors, showModeToggle = false)
        }
    }
}
