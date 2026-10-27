package fr.pushpull.champimap.feature.ventes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.stats.DateRange
import fr.pushpull.champimap.feature.stats.KeyValueRow
import fr.pushpull.champimap.feature.stats.StatTile
import fr.pushpull.champimap.feature.stats.StatsFormat
import fr.pushpull.champimap.nav.Routes
import java.util.Locale

/*
 * Mode vendeur (expérimental) : section « Ventes » de l'écran Statistiques et bouton
 * « Ajouter une vente » de la fin de sortie. Les deux ne s'affichent que si le mode est actif.
 */

/**
 * Section « Ventes » des stats, sur la période choisie : chiffre d'affaires, poids vendu,
 * prix moyen au kilo, nombre de ventes, meilleure journée, part vendue / récoltée,
 * répartition par espèce, et accès à « Mes ventes ».
 * @param harvestedG poids pesé récolté sur la même période (part vendue).
 */
@Composable
fun VentesStatsSection(nav: NavController, range: DateRange, harvestedG: Long) {
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    if (settings?.sellerMode != true) return

    val ventes by remember { AppGraph.db.venteDao().allFlow() }.collectAsState(initial = emptyList())
    val speciesList by remember { AppGraph.db.speciesDao().all() }.collectAsState(initial = emptyList())
    val names = remember(speciesList) { speciesList.associate { it.id to it.nameFr } }
    val summary = remember(ventes, range, harvestedG, names) { computeVentesSummary(ventes, range, harvestedG, names) }

    SectionTitle("Ventes")
    if (summary.count == 0) {
        ChampiCard(Modifier.fillMaxWidth()) {
            Text(champiText("Aucune vente sur cette période."), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        // Chiffre d'affaires en grand.
        ChampiCard(Modifier.fillMaxWidth()) {
            Text(champiText("Chiffre d'affaires"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(VentesFormat.euros(summary.amountCents), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(VentesFormat.weight(summary.weightG), "vendus", Modifier.weight(1f))
            StatTile(summary.avgPricePerKgCents?.let { VentesFormat.euros(it) } ?: "—", "prix moyen / kg", Modifier.weight(1f))
            StatTile(summary.count.toString(), if (summary.count > 1) "ventes" else "vente", Modifier.weight(1f))
        }
        ChampiCard(Modifier.fillMaxWidth()) {
            summary.bestDay?.let { b ->
                KeyValueRow("Meilleure journée", "${StatsFormat.date(b.date)} · ${VentesFormat.euros(b.amountCents)}")
            }
            KeyValueRow(
                "Part vendue / récoltée",
                summary.soldShare?.let { percent(it) + " (${VentesFormat.weight(summary.weightG)} / ${VentesFormat.weight(harvestedG)})" }
                    ?: "rien de pesé",
            )
        }
        // Répartition par espèce (part du chiffre d'affaires).
        ChampiCard(Modifier.fillMaxWidth()) {
            Text(champiText("Par espèce"), style = MaterialTheme.typography.titleMedium)
            summary.bySpecies.forEach { s -> SpeciesShareRow(s) }
        }
    }
    ChampiOutlinedButton("Mes ventes", onClick = { nav.navigate(Routes.VENTES) })
}

/** Une espèce : nom, poids, montant, part et barre proportionnelle (couleurs du thème). */
@Composable
private fun SpeciesShareRow(s: SpeciesShare) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(champiText(s.name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(VentesFormat.euros(s.amountCents), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            champiText("${VentesFormat.weight(s.weightG)} · ${percent(s.share)}"),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxWidth(s.share.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(scheme.primary))
        }
    }
}

/** « 42 % » (arrondi à l'unité, format français). */
private fun percent(f: Float): String = String.format(Locale.FRANCE, "%d %%", Math.round(f * 100))

/**
 * Bouton « Ajouter une vente » de la fin de sortie (mode vendeur actif uniquement) :
 * ouvre une nouvelle vente liée à la sortie, poids proposé = poids pesé récolté.
 */
@Composable
fun VenteFinSortieButton(nav: NavController, sortieId: String) {
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    if (settings?.sellerMode != true) return
    ChampiOutlinedButton("Ajouter une vente", onClick = { nav.navigate(Routes.venteEdit(sortieId = sortieId)) })
}
