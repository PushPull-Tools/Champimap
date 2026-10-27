package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotStatus
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import java.io.File

/*
 * Carte d'en-tête de la fiche spot : nom, coordonnées (œil), précision, visites,
 * et toutes les infos du spot (statut, note étoiles, essences, repère, note, mémo vocal).
 * Par défaut en LECTURE compacte (« Actif · ★★★★☆ · Hêtre, Châtaignier ») ;
 * le crayon « Modifier » passe en édition, « OK » revient en lecture.
 * Les champs texte gardent leur propre état pendant la frappe et s'enregistrent 300 ms après.
 */

/** Libellé d'un statut de spot. */
fun statusLabel(s: SpotStatus): String = when (s) {
    SpotStatus.ACTIF -> "Actif"
    SpotStatus.EPUISE -> "Épuisé"
    SpotStatus.DETRUIT -> "Détruit"
    SpotStatus.A_EXPLORER -> "À explorer"
}

/** Essences d'arbres proposées d'office (les essences libres déjà saisies s'y ajoutent). */
private val ESSENCES = listOf("Chêne", "Châtaignier", "Hêtre", "Bouleau", "Pin", "Douglas", "Épicéa", "Charme", "Frêne", "Mixte")

// -------------------------------------------------------------------------------------------
// Essences : champ texte `Spot.essence`, valeurs séparées par des virgules
// (compatible avec les anciennes valeurs uniques, sans changement de schéma)
// -------------------------------------------------------------------------------------------

/** « Hêtre, Châtaignier » → [Hêtre, Châtaignier] (sans doublon, accents et casse ignorés). */
fun parseEssences(value: String?): List<String> =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { normalize(it) } ?: emptyList()

/** Ajoute ou retire une essence de la liste enregistrée (null si la liste devient vide). */
private fun toggleEssence(value: String?, essence: String): String? {
    val list = parseEssences(value).toMutableList()
    val i = list.indexOfFirst { normalize(it) == normalize(essence) }
    if (i >= 0) list.removeAt(i) else list.add(essence)
    return list.joinToString(", ").ifBlank { null }
}

/** Ajoute une essence si elle n'y est pas déjà. */
private fun addEssence(value: String?, essence: String): String? {
    val list = parseEssences(value)
    return if (list.any { normalize(it) == normalize(essence) }) value else (list + essence).joinToString(", ")
}

// -------------------------------------------------------------------------------------------
// Carte d'en-tête
// -------------------------------------------------------------------------------------------

/** En-tête du spot : identité + infos, lecture compacte ou édition. */
@Composable
fun SpotHeaderCard(spot: Spot, saver: AutoSaver, onInfo: (String) -> Unit) {
    var editing by remember(spot.id) { mutableStateOf(false) }
    val visits by remember(spot.id) { AppGraph.db.spotDao().visitCount(spot.id) }.collectAsState(initial = 0)

    ChampiCard(Modifier.fillMaxWidth()) {
        // --- Nom + crayon « Modifier » / coche « OK » ----------------------------------------
        if (editing) {
            SpotNameField(spot, saver)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    champiText(spot.name.ifBlank { "Spot" }),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Modifier les infos du spot", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // --- Coordonnées (masquables) et précision -------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (spot.coordsHidden) "•••••• ; ••••••" else formatCoords(spot.lat, spot.lon),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val acc = spot.accuracyM?.let { "Précision ±${it.toInt()} m" } ?: if (spot.placedManually) "Placé à la main" else "Précision inconnue"
                val v = if (visits <= 1) "$visits visite" else "$visits visites"
                Text(champiText("$acc · $v"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { saver.now { SpotRepository.updateSpot(spot.id) { it.copy(coordsHidden = !it.coordsHidden) } } }) {
                Icon(
                    if (spot.coordsHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (spot.coordsHidden) "Afficher les coordonnées" else "Masquer les coordonnées",
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)

        if (editing) {
            SpotInfosEditor(spot, saver, onInfo)
            ChampiButton("OK", onClick = { editing = false }, modifier = Modifier.padding(top = 12.dp))
        } else {
            SpotInfosSummary(spot, onInfo)
        }
    }
}

/** Champ du nom du spot (mode édition). */
@Composable
private fun SpotNameField(spot: Spot, saver: AutoSaver) {
    var name by remember(spot.id) { mutableStateOf(spot.name) }
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
}

/**
 * Lecture compacte : « Actif · ★★★★☆ · Hêtre, Châtaignier », puis repère, note (3 lignes max)
 * et présence d'un mémo vocal. Texte d'aide si rien n'est encore renseigné.
 */
@Composable
private fun SpotInfosSummary(spot: Spot, onInfo: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val essences = parseEssences(spot.essence)
    val rating = spot.rating ?: 0
    // Ligne principale : statut · étoiles (pleines en orange girolle) · essences.
    val statusText = champiText(statusLabel(spot.status))
    val essencesText = champiText(essences.joinToString(", "))
    val line = buildAnnotatedString {
        append(statusText)
        if (rating > 0) {
            append("  ·  ")
            withStyle(SpanStyle(color = scheme.secondary)) { append("★".repeat(rating)) }
            withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append("☆".repeat(5 - rating)) }
        }
        if (essences.isNotEmpty()) {
            append("  ·  ")
            append(essencesText)
        }
    }
    Text(line, style = MaterialTheme.typography.bodyLarge)

    spot.repere?.takeIf { it.isNotBlank() }?.let {
        Text(champiText("Repère : $it"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
    spot.note?.takeIf { it.isNotBlank() }?.let {
        Text(
            champiText(it),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    val memoFile = spot.voiceMemoPath?.let { File(it) }?.takeIf { it.exists() }
    val hasMemo = memoFile != null
    // Mémo vocal : lecture / stop directement, sans passer par « Modifier ».
    if (memoFile != null) {
        Row(Modifier.padding(top = 8.dp)) { MemoPlayButton(memoFile, onInfo) }
    }
    if (rating == 0 && essences.isEmpty() && spot.repere.isNullOrBlank() && spot.note.isNullOrBlank() && !hasMemo) {
        Text(
            champiText("Touche le crayon pour noter les essences, un repère, une note…"),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Édition : statut, étoiles, essences (pastilles multiples + « Autre »), repère, note, mémo vocal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotInfosEditor(spot: Spot, saver: AutoSaver, onInfo: (String) -> Unit) {
    var repere by remember(spot.id) { mutableStateOf(spot.repere.orEmpty()) }
    var note by remember(spot.id) { mutableStateOf(spot.note.orEmpty()) }

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

    SectionTitle("Essences d'arbres")
    EssenceChips(spot, saver)

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

/**
 * Pastilles d'essences à sélection multiple : essences proposées + essences libres déjà
 * utilisées sur n'importe quel spot, puis « + Autre » pour en saisir une nouvelle
 * (elle apparaît aussitôt comme pastille sélectionnée).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EssenceChips(spot: Spot, saver: AutoSaver) {
    val used by remember { AppGraph.db.spotDao().essences() }.collectAsState(initial = emptyList())
    val selected = parseEssences(spot.essence)
    val selectedKeys = selected.map { normalize(it) }.toSet()
    // Proposées d'office, puis les essences libres (de tous les spots) par ordre alphabétique.
    val options = remember(used, spot.essence) {
        val base = ESSENCES.map { normalize(it) }.toSet()
        val extra = (used.flatMap { parseEssences(it) } + selected)
            .filter { normalize(it) !in base }
            .distinctBy { normalize(it) }
            .sortedBy { normalize(it) }
        ESSENCES + extra
    }
    var adding by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf("") }

    /** Enregistre l'essence libre saisie (première lettre en majuscule) et la sélectionne. */
    fun addOther() {
        val label = other.trim().replaceFirstChar { it.uppercase() }
        if (label.isNotEmpty()) saver.now { SpotRepository.updateSpot(spot.id) { it.copy(essence = addEssence(it.essence, label)) } }
        other = ""
        adding = false
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { e ->
            SelectChip(e, selected = normalize(e) in selectedKeys) {
                // Lecture fraîche dans updateSpot : deux appuis rapides ne s'écrasent pas.
                saver.now { SpotRepository.updateSpot(spot.id) { it.copy(essence = toggleEssence(it.essence, e)) } }
            }
        }
        if (!adding) SelectChip("+ Autre", selected = false) { adding = true }
    }
    if (adding) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = other,
                onValueChange = { other = it.take(40) },
                placeholder = { Text(champiText("Ex. Noisetier")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addOther() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { addOther() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(champiText(" Ajouter"), maxLines = 1)
            }
        }
    }
}
