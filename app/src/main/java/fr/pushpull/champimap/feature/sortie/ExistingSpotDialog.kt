package fr.pushpull.champimap.feature.sortie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.db.SpotMarker
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * « Spot existant » : liste des spots les plus proches (du plus près au plus loin).
 * Pour chacun : « J'y suis » (passage noté, fiche ouverte) ou « Y aller » (flèche de guidage).
 */
@Composable
internal fun ExistingSpotDialog(
    spots: List<SpotMarker>,
    fix: GpsFix?,
    onDismiss: () -> Unit,
    onGuide: (SpotMarker) -> Unit,
    onHere: (SpotMarker) -> Unit,
) {
    // Tri par distance ; sans position, par ordre de la liste (les plus récents d'abord).
    val sorted: List<Pair<SpotMarker, Double?>> = if (fix == null) spots.map { it to null }
    else spots.map { it to Geo.distanceM(fix.lat, fix.lon, it.lat, it.lon) }.sortedBy { it.second }
    val list = sorted.take(30)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(champiText("Spot existant")) },
        text = {
            if (list.isEmpty()) {
                Text(champiText("Aucun spot pour l'instant. Utilise « Marquer un spot » pour créer le premier."))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.first.id }) { (spot, dist) ->
                        SpotRow(spot, dist, onGuide = { onGuide(spot) }, onHere = { onHere(spot) })
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(champiText("Fermer")) } },
    )
}

/** Une ligne : pastille de couleur, nom, distance, deux actions. */
@Composable
private fun SpotRow(spot: SpotMarker, distance: Double?, onGuide: () -> Unit, onHere: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(colorFromHex(spot.colorHex ?: MapIcons.EMPTY_COLOR), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(spot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    champiText(
                        listOfNotNull(distance?.let { Geo.formatDistance(it) }, spot.speciesName ?: "pas encore de récolte")
                            .joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onGuide, modifier = Modifier.heightIn(min = 48.dp)) { Text(champiText("Y aller")) }
            TextButton(onClick = onHere, modifier = Modifier.heightIn(min = 48.dp)) { Text(champiText("J'y suis")) }
        }
    }
}
