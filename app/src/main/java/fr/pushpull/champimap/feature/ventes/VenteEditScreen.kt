package fr.pushpull.champimap.feature.ventes

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ui.ChampiSearchField
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.db.Vente
import fr.pushpull.champimap.core.newId
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.stats.StatsFormat
import fr.pushpull.champimap.feature.stats.StatsTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * Mode vendeur (expérimental) : saisie / modification d'une vente.
 * Date, sorties de la journée (cases à cocher), poids vendu (proposé = poids pesé récolté des
 * sorties cochées), prix au kilo, montant calculé en direct, espèce et note facultatives.
 * Enregistrement automatique ~300 ms après la dernière modification (comme partout dans l'appli) ;
 * une nouvelle vente n'est créée qu'à partir du moment où un poids est saisi.
 */

/** Contenu saisi, comparable (sans `updatedAt`) pour savoir s'il reste quelque chose à enregistrer. */
private data class VenteDraft(
    val date: LocalDate,
    val weightG: Int,
    val pricePerKgCents: Int,
    val speciesId: String?,
    val sortieIds: List<String>,
    val note: String,
)

/**
 * @param venteId vente à modifier, ou null pour une nouvelle vente.
 * @param sortieId sortie à lier d'office (nouvelle vente depuis la fin de sortie).
 */
@Composable
fun VenteEditScreen(nav: NavController, venteId: String?, sortieId: String?) {
    val context = LocalContext.current
    val dao = AppGraph.db.venteDao()
    val id = remember { venteId ?: newId() }

    // ---------------------------------------------------------------- État du formulaire
    var loaded by remember { mutableStateOf(false) }
    var exists by remember { mutableStateOf(venteId != null) }
    var deleted by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var weightText by remember { mutableStateOf("") }
    /** Vrai dès que l'utilisateur a tapé un poids : on ne le remplace plus automatiquement. */
    var weightEdited by remember { mutableStateOf(venteId != null) }
    var priceText by remember { mutableStateOf("") }
    var speciesId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<VenteDraft?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Données de la journée choisie : sorties, poids récolté par sortie, espèces récoltées.
    var daySorties by remember { mutableStateOf(listOf<Sortie>()) }
    var sortieWeights by remember { mutableStateOf(mapOf<String, Long>()) }
    var daySpecies by remember { mutableStateOf(listOf<Species>()) }
    val allSpecies by remember { AppGraph.db.speciesDao().all() }.collectAsState(initial = emptyList())

    // ---------------------------------------------------------------- Chargement initial
    LaunchedEffect(Unit) {
        runCatching {
            val existing = venteId?.let { dao.get(it) }
            if (existing != null) {
                date = VentesTime.dateOf(existing)
                selected = existing.sortieIdList()
                weightText = existing.weightG.takeIf { it > 0 }?.toString().orEmpty()
                priceText = VentesFormat.priceInput(existing.pricePerKgCents)
                speciesId = existing.speciesId
                note = existing.note.orEmpty()
                // Déjà en base : rien à réenregistrer tant que l'utilisateur ne change rien.
                lastSaved = VenteDraft(
                    date, existing.weightG, existing.pricePerKgCents, existing.speciesId, selected, note.trim(),
                )
            } else {
                exists = false
                // Nouvelle vente depuis une sortie : jour de la sortie, sortie cochée.
                val sortie = sortieId?.let { dao.sortie(it) }
                if (sortie != null) {
                    date = StatsTime.dateOf(sortie.startedAt)
                    selected = listOf(sortie.id)
                }
            }
        }.onFailure { ErrorLogger.log("Vente", "Chargement de la vente impossible", it) }
        loaded = true
    }

    // Sorties de la journée (recalculées quand la date change) ; on garde les cases encore valables.
    LaunchedEffect(date, loaded) {
        if (!loaded) return@LaunchedEffect
        runCatching {
            val (from, to) = VentesTime.dayBounds(date)
            val list = dao.sortiesBetween(from, to)
            daySorties = list
            val ids = list.map { it.id }
            sortieWeights = if (ids.isEmpty()) emptyMap() else dao.sortieWeights(ids).associate { it.sortieId to it.weightG }
            selected = selected.filter { it in ids }
        }.onFailure { ErrorLogger.log("Vente", "Lecture des sorties du jour impossible", it) }
    }

    // Espèces récoltées pendant les sorties cochées (suggestions) ; une seule → choisie d'office.
    val harvestedG = selected.sumOf { sortieWeights[it] ?: 0L }
    LaunchedEffect(selected, loaded) {
        if (!loaded) return@LaunchedEffect
        daySpecies = if (selected.isEmpty()) emptyList() else runCatching { dao.speciesOfSorties(selected) }.getOrDefault(emptyList())
        if (!exists && speciesId == null && daySpecies.size == 1) speciesId = daySpecies.first().id
    }

    // Poids proposé = poids pesé récolté des sorties cochées, tant que l'utilisateur n'a rien tapé.
    LaunchedEffect(harvestedG, weightEdited) {
        if (!weightEdited) weightText = if (harvestedG > 0) harvestedG.toString() else ""
    }

    val weightG = VentesFormat.parseWeight(weightText) ?: 0
    val priceCents = VentesFormat.parsePrice(priceText) ?: 0
    val priceInvalid = priceText.isNotBlank() && VentesFormat.parsePrice(priceText) == null
    val draft = VenteDraft(date, weightG, priceCents, speciesId, selected, note.trim())

    /** Construit l'entité à enregistrer, ou null s'il n'y a rien à enregistrer. */
    fun toEntity(d: VenteDraft): Vente? {
        if (deleted || (!exists && d.weightG <= 0)) return null
        return Vente(
            id = id,
            soldAt = VentesTime.toMillis(d.date),
            weightG = d.weightG,
            pricePerKgCents = d.pricePerKgCents,
            speciesId = d.speciesId,
            sortieIds = d.sortieIds.joinToString(",").ifEmpty { null },
            note = d.note.ifEmpty { null },
            updatedAt = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------- Enregistrement automatique
    LaunchedEffect(draft, loaded) {
        if (!loaded || draft == lastSaved) return@LaunchedEffect
        saved = false
        delay(300)
        val entity = toEntity(draft) ?: return@LaunchedEffect
        runCatching { dao.upsert(entity) }
            .onSuccess {
                exists = true
                lastSaved = draft
                saved = true
                delay(1500)
                saved = false
            }
            .onFailure { ErrorLogger.log("Vente", "Vente non enregistrée", it) }
    }

    // En quittant l'écran avant la fin du délai : dernier enregistrement en arrière-plan.
    // `deleted` est relu au moment de quitter : une vente qu'on vient de supprimer n'est jamais réécrite.
    val pending = if (loaded && draft != lastSaved) toEntity(draft) else null
    val latestPending by rememberUpdatedState(pending)
    DisposableEffect(Unit) {
        onDispose {
            val entity = latestPending
            if (entity != null && !deleted) {
                AppGraph.appScope.launch {
                    runCatching { AppGraph.db.venteDao().upsert(entity) }
                        .onFailure { ErrorLogger.log("Vente", "Vente non enregistrée", it) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Interface
    ChampiScreen(title = if (venteId != null) "Modifier la vente" else "Nouvelle vente", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Montant calculé, bien visible en haut.
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(champiText("Montant"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    VentesFormat.euros(amountCents(weightG, priceCents)),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    champiText(
                        when {
                            weightG <= 0 -> "Saisis le poids vendu pour enregistrer la vente."
                            saved -> "✓ Enregistré"
                            else -> "${VentesFormat.weight(weightG.toLong())} × ${VentesFormat.pricePerKg(priceCents.toLong())}"
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Date de la vente.
            SectionTitle("Date")
            ChampiCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(champiText(StatsFormat.capitalize(StatsFormat.date(date))), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                                date.year, date.monthValue - 1, date.dayOfMonth,
                            ).show()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(champiText("Changer")) }
                }
            }

            // Sorties de la journée.
            SectionTitle("Sorties liées")
            ChampiCard(Modifier.fillMaxWidth()) {
                if (daySorties.isEmpty()) {
                    Text(champiText("Aucune sortie ce jour-là."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                daySorties.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    val checked = s.id in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                            selected = if (checked) selected - s.id else selected + s.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { on -> selected = if (on) selected + s.id else selected - s.id })
                        Column(Modifier.weight(1f)) {
                            Text(champiText(s.placeName?.takeIf { it.isNotBlank() } ?: "Sortie de ${StatsFormat.time(s.startedAt)}"), style = MaterialTheme.typography.titleSmall)
                            val g = sortieWeights[s.id] ?: 0L
                            Text(
                                champiText("Départ ${StatsFormat.time(s.startedAt)} · " + if (g > 0) "${VentesFormat.weight(g)} récoltés" else "rien de pesé"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Poids et prix.
            SectionTitle("Poids et prix")
            ChampiCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it.filter { c -> c.isDigit() }.take(7); weightEdited = true },
                    label = { Text(champiText("Poids vendu (g)")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { if (weightG >= 1000) Text(VentesFormat.weight(weightG.toLong())) },
                )
                // Retour au poids récolté si l'utilisateur l'a modifié.
                if (harvestedG > 0 && weightG.toLong() != harvestedG) {
                    TextButton(
                        onClick = { weightText = harvestedG.toString(); weightEdited = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(champiText("Reprendre le poids récolté (${VentesFormat.weight(harvestedG)})")) }
                }
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it.take(10) },
                    label = { Text(champiText("Prix au kilo (€)")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    isError = priceInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (priceInvalid) Text("Prix illisible, ex. 12,50") },
                )
            }

            // Espèce (facultative).
            SectionTitle("Espèce (facultatif)")
            SpeciesPicker(
                selectedId = speciesId,
                suggestions = daySpecies,
                all = allSpecies,
                onSelect = { speciesId = it },
            )

            // Note (facultative).
            SectionTitle("Note (facultatif)")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(500) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
                placeholder = { Text(champiText("Acheteur, marché, remarque…")) },
            )

            ChampiButton("Terminé", onClick = { nav.popBackStack() })
            if (exists) {
                ChampiOutlinedButton("Supprimer la vente", onClick = { confirmDelete = true })
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer cette vente ?",
            text = "Elle reste 30 jours dans la corbeille.",
            onConfirm = {
                confirmDelete = false
                deleted = true
                AppGraph.appScope.launch {
                    runCatching { AppGraph.db.venteDao().softDelete(id, System.currentTimeMillis()) }
                        .onFailure { ErrorLogger.log("Vente", "Suppression de vente impossible", it) }
                }
                nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Choix de l'espèce : espèce choisie (avec « Retirer »), espèces récoltées pendant les sorties
 * cochées, et recherche dans le catalogue (nom courant ou latin, 6 résultats au plus).
 */
@Composable
private fun SpeciesPicker(
    selectedId: String?,
    suggestions: List<Species>,
    all: List<Species>,
    onSelect: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val chosen = all.firstOrNull { it.id == selectedId }
    ChampiCard(Modifier.fillMaxWidth()) {
        if (selectedId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    champiText(chosen?.nameFr ?: "Espèce inconnue"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSelect(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(champiText("Retirer")) }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        }
        val others = suggestions.filter { it.id != selectedId }
        if (others.isNotEmpty()) {
            Text(champiText("Récoltées pendant ces sorties"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            others.forEach { sp -> SpeciesRow(sp) { onSelect(sp.id) } }
        }
        ChampiSearchField(query, { query = it.take(40) }, placeholder = "Chercher une espèce")
        val q = query.trim().lowercase()
        if (q.length >= 2) {
            val results = all.filter {
                it.id != selectedId && (it.nameFr.lowercase().contains(q) || it.nameLatin.lowercase().contains(q) ||
                    (it.otherNames?.lowercase()?.contains(q) == true))
            }.take(6)
            if (results.isEmpty()) {
                Text(champiText("Aucune espèce trouvée."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            results.forEach { sp -> SpeciesRow(sp) { onSelect(sp.id); query = "" } }
        }
    }
}

/** Ligne cliquable d'une espèce (nom courant + nom latin). */
@Composable
private fun SpeciesRow(sp: Species, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(champiText(sp.nameFr), style = MaterialTheme.typography.bodyLarge)
        Text(sp.nameLatin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
