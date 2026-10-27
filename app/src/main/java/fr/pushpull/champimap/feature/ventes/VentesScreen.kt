package fr.pushpull.champimap.feature.ventes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.db.Vente
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.stats.StatsFormat
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Mode vendeur (expérimental) : écran « Mes ventes ».
 * Ventes regroupées par jour (le plus récent en haut) avec le total du jour ;
 * « Ajouter une vente », appui sur une vente pour la modifier, corbeille avec confirmation
 * (restaurable 30 jours depuis Sauvegarde → Corbeille).
 */

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)

/** Liste des ventes. Accessible depuis Stats quand le mode vendeur est actif. */
@Composable
fun VentesScreen(nav: NavController) {
    val db = AppGraph.db
    val ventes by remember { db.venteDao().allFlow() }.collectAsState(initial = emptyList())
    val speciesList by remember { db.speciesDao().all() }.collectAsState(initial = emptyList())
    val species = remember(speciesList) { speciesList.associateBy { it.id } }
    var toDelete by remember { mutableStateOf<Vente?>(null) }

    // Regroupement par jour, du plus récent au plus ancien (la requête trie déjà).
    val byDay = remember(ventes) { ventes.groupBy { VentesTime.dateOf(it) }.toList() }

    ChampiScreen(title = "Mes ventes", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            ChampiButton("Ajouter une vente", onClick = { nav.navigate(Routes.venteEdit()) })
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ventes.isEmpty()) {
                    item {
                        ChampiCard(Modifier.fillMaxWidth()) {
                            Text(champiText("Aucune vente pour l'instant."), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(byDay, key = { it.first.toEpochDay() }) { (day, list) ->
                    DayCard(
                        day = day,
                        ventes = list,
                        species = species,
                        onOpen = { nav.navigate(Routes.venteEdit(venteId = it.id)) },
                        onDelete = { toDelete = it },
                    )
                }
            }
        }
    }

    toDelete?.let { v ->
        ConfirmDialog(
            title = "Supprimer cette vente ?",
            text = "${VentesFormat.weight(v.weightG.toLong())} · ${VentesFormat.euros(v.amountCents())}. " +
                "Elle reste 30 jours dans la corbeille.",
            onConfirm = {
                toDelete = null
                AppGraph.appScope.launch {
                    runCatching { AppGraph.db.venteDao().softDelete(v.id, System.currentTimeMillis()) }
                        .onFailure { ErrorLogger.log("Mes ventes", "Suppression de vente impossible", it) }
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}

/** Une journée : date, totaux du jour, puis chaque vente. */
@Composable
private fun DayCard(
    day: LocalDate,
    ventes: List<Vente>,
    species: Map<String, Species>,
    onOpen: (Vente) -> Unit,
    onDelete: (Vente) -> Unit,
) {
    val totalCents = ventes.sumOf { it.amountCents() }
    val totalG = ventes.sumOf { it.weightG.toLong() }
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(
            champiText(StatsFormat.capitalize(day.format(DAY_FMT))),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            champiText("${VentesFormat.weight(totalG)} · ${VentesFormat.euros(totalCents)}"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        ventes.forEach { v ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            VenteRow(v, species, onOpen = { onOpen(v) }, onDelete = { onDelete(v) })
        }
    }
}

/** Ligne d'une vente : espèce, calcul du montant, note ; corbeille à droite. */
@Composable
private fun VenteRow(v: Vente, species: Map<String, Species>, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(champiText(speciesLabel(v.speciesId, species)), style = MaterialTheme.typography.titleSmall)
            val price = if (v.pricePerKgCents > 0) " × ${VentesFormat.pricePerKg(v.pricePerKgCents.toLong())}" else ""
            Text(
                champiText("${VentesFormat.weight(v.weightG.toLong())}$price = ${VentesFormat.euros(v.amountCents())}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val n = v.sortieIdList().size
            val extra = listOfNotNull(
                if (n > 0) "$n sortie${if (n > 1) "s" else ""} liée${if (n > 1) "s" else ""}" else null,
                v.note?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (extra.isNotEmpty()) {
                Text(
                    champiText(extra),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Supprimer la vente", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
