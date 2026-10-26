package fr.pushpull.champimap.feature.stats

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiChip
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Carte « Météo et récoltes » (expérimental) de l'écran Statistiques.
 * Affiche les conclusions du moteur WeatherCorrelation, ou « Pas encore assez de récoltes pour conclure ».
 */

/** États de calcul de la carte. */
private sealed interface CorrelationState {
    data object Loading : CorrelationState
    data class Ready(val report: CorrelationReport) : CorrelationState
    data object Failed : CorrelationState
}

@Composable
fun WeatherCorrelationCard(data: StatsData) {
    // Recalcul hors du fil principal à chaque changement des récoltes.
    val state by produceState<CorrelationState>(CorrelationState.Loading, data.harvests) {
        value = try {
            CorrelationState.Ready(withContext(Dispatchers.Default) { WeatherCorrelation.compute(data.harvests) })
        } catch (e: Exception) {
            ErrorLogger.log("Statistiques", "Calcul météo et récoltes impossible", e)
            CorrelationState.Failed
        }
    }

    ChampiCard(Modifier.fillMaxWidth()) {
        ChampiChip("Expérimental")
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            CorrelationState.Loading -> Text(champiText("Calcul en cours…"), style = MaterialTheme.typography.bodyMedium)
            CorrelationState.Failed -> Text(champiText("Le calcul n'a pas fonctionné."), style = MaterialTheme.typography.bodyMedium)
            is CorrelationState.Ready -> ReportContent(s.report)
        }
    }
}

/** Contenu du rapport : conclusions par espèce, ou explication de ce qui manque. */
@Composable
private fun ReportContent(report: CorrelationReport) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (report.insights.isEmpty()) {
        Text(champiText("Pas encore assez de récoltes pour conclure"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val detail = when {
            report.weighedHarvests == 0 ->
                "Pèse tes cueillettes : il faut au moins ${WeatherCorrelation.MIN_HARVESTS} récoltes pesées d'une même espèce."
            report.harvestsWithWeather == 0 ->
                "La météo n'est pas encore disponible : lance une synchro Champimap Connect (Plus › Champimap Connect)."
            else ->
                "Il faut au moins ${WeatherCorrelation.MIN_HARVESTS} récoltes pesées d'une même espèce, avec la météo du mois d'avant."
        }
        Text(champiText(detail), style = MaterialTheme.typography.bodyMedium, color = muted)
    } else {
        report.insights.forEachIndexed { i, insight ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(champiText(insight.speciesName), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            insight.sentences.forEach { s ->
                Text(champiText(s), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
            Text(champiText(insight.footer), style = MaterialTheme.typography.bodySmall, color = muted, fontWeight = FontWeight.Medium)
        }
    }
    // Espèces en attente : combien de récoltes il manque.
    if (report.pending.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        val list = report.pending.take(4).joinToString(" · ") { "${it.speciesName} ${it.withWeather}/${WeatherCorrelation.MIN_HARVESTS}" }
        Text(champiText("En attente : $list"), style = MaterialTheme.typography.bodySmall, color = muted)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        champiText("Calculé uniquement sur tes récoltes et la météo de zones larges (~25 km)."),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
}
