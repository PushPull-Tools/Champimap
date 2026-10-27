package fr.pushpull.champimap.feature.stats

import fr.pushpull.champimap.core.util.motPluriel
import fr.pushpull.champimap.core.util.pluriel
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.ventes.VentesStatsSection
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * Points d'entrée du module « stats » (signatures fixes, appelées par nav/AppNav.kt) :
 *  - StatsScreen : période Jour/Semaine/Mois/Saison, total, barres, sorties/spots, podium,
 *    « Météo et récoltes », Carte aux trésors, Export CSV, lien vers le calendrier ;
 *  - CalendrierScreen (CalendrierScreen.kt) et TresorsScreen (TresorsScreen.kt) délèguent à leurs fichiers.
 */

@Composable
fun StatsScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = rememberStatsData()
    val today = remember { LocalDate.now() }

    // Période choisie (Saison par défaut) et décalage (0 = en cours, −1 = précédente…).
    var periodIndex by rememberSaveable { mutableIntStateOf(StatsPeriod.SAISON.ordinal) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selectedBar by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val period = StatsPeriod.entries[periodIndex]
    val range = StatsTime.rangeFor(period, offset, today)
    val summary = remember(data, period, range) {
        computeSummary(data.harvests, data.sorties, data.visits, data.persons, period, range, today)
    }

    ChampiScreen(
        title = "Statistiques",
        actions = {
            IconButton(onClick = { nav.navigate(Routes.CALENDRIER) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Calendrier", tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedSelector(
                options = StatsPeriod.entries.map { it.label },
                selected = periodIndex,
                onSelect = { periodIndex = it; offset = 0; selectedBar = null },
            )

            // Navigation entre périodes : ‹ Saison 2026-2027 ›
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset -= 1; selectedBar = null }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Période précédente")
                }
                Text(
                    champiText(StatsTime.label(period, range, offset)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { offset += 1; selectedBar = null }, enabled = offset < 0, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Période suivante")
                }
            }

            // Total + barres.
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(champiText("Récolté"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    StatsFormat.kg(summary.totalG),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (summary.unweighed > 0) {
                    Text(
                        champiText("${pluriel(summary.unweighed, "cueillette")} sans poids, non ${motPluriel(summary.unweighed, "comptée")}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.bars.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    BarChart(summary.bars, selectedBar, onSelect = { selectedBar = if (selectedBar == it) null else it })
                    selectedBar?.let { summary.bars.getOrNull(it) }?.let { b ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            champiText("${b.fullLabel} : ${StatsFormat.kg(b.grams)}"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Sorties et spots.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(summary.sorties.toString(), motPluriel(summary.sorties, "sortie"), Modifier.weight(1f))
                StatTile(summary.spots.toString(), motPluriel(summary.spots, "spot"), Modifier.weight(1f))
            }

            // Podium par personne.
            SectionTitle("Podium")
            ChampiCard(Modifier.fillMaxWidth()) {
                if (summary.podium.isEmpty()) {
                    Text(champiText("Aucune récolte pesée sur cette période."), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Podium(summary.podium)
                }
            }

            // Mode vendeur (expérimental) : section « Ventes », affichée seulement si le mode est actif.
            VentesStatsSection(nav, range, summary.totalG)

            // Météo et récoltes (expérimental).
            SectionTitle("Météo et récoltes")
            WeatherCorrelationCard(data)

            // Actions.
            ChampiButton("Carte aux trésors", onClick = { nav.navigate(Routes.TRESORS) })
            ChampiOutlinedButton(
                text = if (exporting) "Export en cours…" else "Export CSV",
                enabled = !exporting,
                onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            val uri = CsvExport.writeHarvestsCsv(context)
                            CsvExport.share(context, uri)
                        } catch (e: Exception) {
                            ErrorLogger.log("Statistiques", "Export CSV impossible", e)
                            Toast.makeText(context, "L'export n'a pas fonctionné", Toast.LENGTH_SHORT).show()
                        } finally {
                            exporting = false
                        }
                    }
                },
            )
            TextButton(onClick = { nav.navigate(Routes.CALENDRIER) }, modifier = Modifier.fillMaxWidth()) {
                Text(champiText("Voir le calendrier des sorties"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun CalendrierScreen(nav: NavController) {
    CalendrierContent(nav)
}

@Composable
fun TresorsScreen(nav: NavController) {
    TresorsContent(nav)
}
