package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.db.RecolteItem
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.flow.flowOf
import java.io.File

/*
 * Récolte du jour (fiche spot) : cadenas, liste des espèces récoltées avec photo miniature,
 * nom, poids (+50 / +100 / saisie libre), pastilles stade / qualité / état.
 * Verrouillée = rien n'est modifiable ni supprimable.
 */

/** Pastilles : mot-clé enregistré → libellé affiché. */
private val STADES = listOf("BOUCHON" to "Bouchon", "JEUNE" to "Jeune", "BEAU" to "Beau", "OUVERT" to "Ouvert", "PASSE" to "Passé")
private val QUALITES = listOf(
    "FERME" to "Ferme", "VEREUX" to "Véreux", "LIMACE" to "Limacé",
    "MOISI" to "Moisi", "PARASITE" to "Parasité", "ABIME" to "Abîmé",
)
private val ETATS = listOf("GORGE_EAU" to "Gorgé d'eau", "SEC" to "Sec", "GELE" to "Gelé")

/** Ajoute ou retire un mot-clé d'une liste « A,B,C » (null si la liste devient vide). */
private fun toggleKey(list: String?, key: String): String? {
    val set = list?.split(',')?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
    if (key in set) set.remove(key) else set.add(key)
    return set.joinToString(",").ifBlank { null }
}

private fun hasKey(list: String?, key: String) = list?.split(',')?.contains(key) == true

/**
 * Carte de la récolte affichée (`recolteId` null = pas encore de récolte : texte d'aide).
 * @param onItemPhoto prend une photo pour une espèce récoltée.
 */
@Composable
fun RecolteCard(
    recolteId: String?,
    saver: AutoSaver,
    onUndo: (UndoAction) -> Unit,
    onInfo: (String) -> Unit,
    onItemPhoto: (String) -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val db = AppGraph.db
    val recolte by remember(recolteId) {
        if (recolteId != null) db.recolteDao().flow(recolteId) else flowOf(null)
    }.collectAsState(initial = null)
    val items by remember(recolteId) {
        if (recolteId != null) db.recolteDao().items(recolteId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val species by remember { db.speciesDao().all() }.collectAsState(initial = emptyList())
    val byId = remember(species) { species.associateBy { it.id } }

    ChampiCard(Modifier.fillMaxWidth()) {
        val r = recolte
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = when {
                r == null -> "Récolte du jour"
                SpotRepository.isToday(r.time) -> "Récolte du jour"
                else -> "Récolte du ${formatDate(r.time)}"
            }
            Text(champiText(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (r != null) {
                val total = items.sumOf { it.weightG ?: 0 }
                if (total > 0) Text(formatWeight(total), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { saver.now { SpotRepository.updateRecolte(r.id) { it.copy(locked = !it.locked) } } }) {
                    Icon(
                        if (r.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (r.locked) "Déverrouiller la récolte" else "Verrouiller la récolte",
                        tint = if (r.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (r == null || items.isEmpty()) {
            Text(
                champiText("Appuie sur « Trouvé ! » pour noter ce que tu ramasses."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@ChampiCard
        }
        if (r.locked) {
            Text(champiText("Récolte verrouillée : rien ne peut être modifié ni supprimé."), style = MaterialTheme.typography.bodySmall)
        }
        items.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RecolteItemRow(
                item = item,
                species = byId[item.speciesId],
                recolte = r,
                saver = saver,
                onDelete = {
                    saver.now { SpotRepository.trashItem(item.id) }
                    onUndo(UndoAction("Supprimé") { SpotRepository.restoreItem(item.id) })
                },
                onPhoto = { onItemPhoto(item.id) },
                onOpenSpecies = { onOpenSpecies(item.speciesId) },
            )
        }
    }
}

/** « 1 250 g » → « 1,25 kg » au-delà d'un kilo. */
fun formatWeight(g: Int): String =
    if (g < 1000) "$g g" else String.format(java.util.Locale.FRENCH, "%.2f kg", g / 1000f)

/** Une espèce récoltée : miniature, nom, poids, pastilles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecolteItemRow(
    item: RecolteItem,
    species: Species?,
    recolte: Recolte,
    saver: AutoSaver,
    onDelete: () -> Unit,
    onPhoto: () -> Unit,
    onOpenSpecies: () -> Unit,
) {
    val locked = recolte.locked
    val photo by remember(item.id) { AppGraph.db.photoDao().latestOf(item.id) }.collectAsState(initial = null)
    // Poids : état local pendant la frappe, resynchronisé quand la base change (+50 / +100).
    var weightText by remember(item.id) { mutableStateOf(item.weightG?.toString().orEmpty()) }
    LaunchedEffect(item.weightG) {
        if (weightText.toIntOrNull() != item.weightG) weightText = item.weightG?.toString().orEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Miniature (appui = nouvelle photo si non verrouillée).
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !locked, onClick = onPhoto),
                contentAlignment = Alignment.Center,
            ) {
                val p = photo
                if (p != null) {
                    AsyncImage(model = File(p.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Ajouter une photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onOpenSpecies)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeciesDot(species, 10)
                    Spacer(Modifier.width(6.dp))
                    Text(champiText(species?.nameFr ?: "Espèce inconnue"), style = MaterialTheme.typography.titleMedium)
                }
                species?.let { Text(it.nameLatin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!locked) {
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer cette espèce") }
            }
        }

        // Poids : saisie libre + raccourcis.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = weightText,
                onValueChange = { v ->
                    val digits = v.filter { it.isDigit() }.take(6)
                    weightText = digits
                    saver.save("poids-${item.id}") { SpotRepository.updateItem(item.id) { it.copy(weightG = digits.toIntOrNull()) } }
                },
                enabled = !locked,
                label = { Text(champiText("Poids (g)")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            listOf(50, 100).forEach { add ->
                OutlinedButton(
                    onClick = {
                        val v = (weightText.toIntOrNull() ?: 0) + add
                        weightText = v.toString()
                        saver.now { SpotRepository.updateItem(item.id) { it.copy(weightG = (it.weightG ?: 0) + add) } }
                    },
                    enabled = !locked,
                    modifier = Modifier.size(width = 76.dp, height = 56.dp),
                ) { Text("+$add") }
            }
        }

        ChipGroup("Stade", STADES, enabled = !locked, isOn = { item.stade == it }) { key ->
            // Un seul stade à la fois (appui sur le stade choisi = on le retire).
            saver.now { SpotRepository.updateItem(item.id) { it.copy(stade = if (it.stade == key) null else key) } }
        }
        ChipGroup("Qualité", QUALITES, enabled = !locked, isOn = { hasKey(item.qualites, it) }) { key ->
            saver.now { SpotRepository.updateItem(item.id) { it.copy(qualites = toggleKey(it.qualites, key)) } }
        }
        ChipGroup("État", ETATS, enabled = !locked, isOn = { hasKey(item.etats, it) }) { key ->
            saver.now { SpotRepository.updateItem(item.id) { it.copy(etats = toggleKey(it.etats, key)) } }
        }
    }
}

/** Groupe de pastilles sélectionnables avec son titre. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    title: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    isOn: (String) -> Boolean,
    onToggle: (String) -> Unit,
) {
    SectionTitle(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            SelectChip(label, selected = isOn(key), enabled = enabled) { onToggle(key) }
        }
    }
}
