package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotStatus
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Fiche spot, partie « identité » (nom, coordonnées masquables, précision, visites)
 * et partie « détails » (statut, essence, note étoiles, repère, note, mémo vocal).
 * Les champs texte gardent leur propre état pendant la frappe et s'enregistrent 300 ms après.
 */

/** Libellé d'un statut de spot. */
fun statusLabel(s: SpotStatus): String = when (s) {
    SpotStatus.ACTIF -> "Actif"
    SpotStatus.EPUISE -> "Épuisé"
    SpotStatus.DETRUIT -> "Détruit"
    SpotStatus.A_EXPLORER -> "À explorer"
}

/** Essences d'arbres proposées (le champ reste libre). */
private val ESSENCES = listOf("Chêne", "Châtaignier", "Hêtre", "Bouleau", "Pin", "Douglas", "Épicéa", "Charme", "Frêne", "Mixte")

/** Nom, coordonnées (œil), précision et nombre de visites. */
@Composable
fun SpotIdentityCard(spot: Spot, saver: AutoSaver) {
    var name by remember(spot.id) { mutableStateOf(spot.name) }
    val visits by remember(spot.id) { AppGraph.db.spotDao().visitCount(spot.id) }.collectAsState(initial = 0)

    ChampiCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = { v ->
                name = v
                saver.save("name") { SpotRepository.updateSpot(spot.id) { it.copy(name = v) } }
            },
            label = { Text(champiText("Nom du spot")) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (spot.coordsHidden) "•••••• ; ••••••" else formatCoords(spot.lat, spot.lon),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { saver.now { SpotRepository.updateSpot(spot.id) { it.copy(coordsHidden = !it.coordsHidden) } } }) {
                Icon(
                    if (spot.coordsHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (spot.coordsHidden) "Afficher les coordonnées" else "Masquer les coordonnées",
                )
            }
        }
        val acc = spot.accuracyM?.let { "Précision ±${it.toInt()} m" } ?: if (spot.placedManually) "Placé à la main" else "Précision inconnue"
        val v = if (visits <= 1) "$visits visite" else "$visits visites"
        Text(champiText("$acc · $v"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Statut, essence, étoiles, repère, note et mémo vocal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpotDetailsCard(spot: Spot, saver: AutoSaver, onInfo: (String) -> Unit) {
    var essence by remember(spot.id) { mutableStateOf(spot.essence.orEmpty()) }
    var repere by remember(spot.id) { mutableStateOf(spot.repere.orEmpty()) }
    var note by remember(spot.id) { mutableStateOf(spot.note.orEmpty()) }

    ChampiCard(Modifier.fillMaxWidth()) {
        SectionTitle("Statut")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpotStatus.entries.forEach { st ->
                SelectChip(statusLabel(st), selected = spot.status == st) {
                    saver.now { SpotRepository.updateSpot(spot.id) { it.copy(status = st) } }
                }
            }
        }

        SectionTitle("Note")
        Row {
            (1..5).forEach { n ->
                val on = (spot.rating ?: 0) >= n
                IconButton(
                    onClick = {
                        // Appui sur l'étoile déjà choisie = on retire la note.
                        val newRating = if (spot.rating == n) null else n
                        saver.now { SpotRepository.updateSpot(spot.id) { it.copy(rating = newRating) } }
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (on) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "$n étoile" + if (n > 1) "s" else "",
                        tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionTitle("Essence d'arbre")
        OutlinedTextField(
            value = essence,
            onValueChange = { v ->
                essence = v
                saver.save("essence") { SpotRepository.updateSpot(spot.id) { it.copy(essence = v.trim().ifBlank { null }) } }
            },
            placeholder = { Text(champiText("Ex. châtaignier")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ESSENCES.forEach { e ->
                SelectChip(e, selected = essence.equals(e, ignoreCase = true)) {
                    essence = e
                    saver.now { SpotRepository.updateSpot(spot.id) { it.copy(essence = e) } }
                }
            }
        }

        SectionTitle("Repère")
        OutlinedTextField(
            value = repere,
            onValueChange = { v ->
                repere = v
                saver.save("repere") { SpotRepository.updateSpot(spot.id) { it.copy(repere = v.ifBlank { null }) } }
            },
            placeholder = { Text(champiText("Ex. gros rocher moussu, 20 pas après la barrière")) },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Note")
        OutlinedTextField(
            value = note,
            onValueChange = { v ->
                note = v
                saver.save("note") { SpotRepository.updateSpot(spot.id) { it.copy(note = v.ifBlank { null }) } }
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Mémo vocal")
        VoiceMemo(spot, saver, onInfo)
    }
}
