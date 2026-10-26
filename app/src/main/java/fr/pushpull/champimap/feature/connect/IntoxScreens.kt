package fr.pushpull.champimap.feature.connect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Hospital
import fr.pushpull.champimap.core.db.IntoxFiche
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.newId
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.core.ui.theme.SafetyTheme
import fr.pushpull.champimap.feature.reglages.Departements
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Écrans de sécurité « En cas d'intoxication » (toujours en SafetyTheme : jamais de party ni d'émojis).
 *
 * IntoxScreen : numéros utiles (15, centre antipoison selon la position, urgences les plus proches),
 * sortie(s) concernée(s) avec espèces, photos et quantités, bons réflexes. La fiche est enregistrée
 * automatiquement (IntoxFiche) dès qu'une sortie est choisie.
 * Le paramètre `sortieId` peut contenir plusieurs identifiants séparés par des virgules
 * (ouverture d'une fiche existante depuis la liste).
 *
 * IntoxListScreen : liste des fiches + nouvelle fiche en cochant une ou plusieurs sorties.
 */

// ---------------------------------------------------------------------------------------------
// Données affichées
// ---------------------------------------------------------------------------------------------

/** Une espèce récoltée pendant une sortie, avec quantités et photos. */
private data class SpeciesLine(
    val name: String,
    val latin: String?,
    val edibility: Edibility?,
    val count: Int,
    val weightG: Int,
    val photos: List<String>,
)

/** Résumé d'une sortie pour la fiche. */
private data class SortieSummary(val sortie: Sortie, val species: List<SpeciesLine>)

/** Position de référence et sa provenance. */
private data class RefPosition(val lat: Double, val lon: Double, val fromGps: Boolean)

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH 'h' mm", Locale.FRANCE)

/** « samedi 24 octobre 2026 · Bois de… » */
private fun sortieLabel(s: Sortie): String {
    val d = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault())
    val date = d.format(DATE_FMT).replaceFirstChar { it.uppercase() }
    return if (s.placeName.isNullOrBlank()) date else "$date · ${s.placeName}"
}

private fun dateTimeLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return d.format(DATE_FMT).replaceFirstChar { it.uppercase() } + " à " + d.format(TIME_FMT)
}

/** Charge espèces, quantités et photos d'une sortie. */
private suspend fun loadSummary(sortieId: String): SortieSummary? = withContext(Dispatchers.IO) {
    val db = AppGraph.db
    val sortie = db.sortieDao().get(sortieId) ?: return@withContext null
    val items = db.recolteDao().ofSortie(sortieId).first().flatMap { db.recolteDao().itemsList(it.id) }
        .filter { it.deletedAt == null }
    val species = db.speciesDao().byIds(items.map { it.speciesId }.distinct()).associateBy { it.id }
    val lines = items.groupBy { it.speciesId }.map { (sid, list) ->
        val sp = species[sid]
        val photos = list.flatMap { item -> db.photoDao().ofOwner(item.id).first().map { it.path } }
        SpeciesLine(
            name = sp?.nameFr ?: "Espèce inconnue",
            latin = sp?.nameLatin,
            edibility = sp?.edibility,
            count = list.size,
            weightG = list.sumOf { it.weightG ?: 0 },
            photos = photos,
        )
    }.sortedBy { it.name }
    SortieSummary(sortie, lines)
}

/**
 * Position de référence : dernière position connue (si permission), sinon centre du premier
 * département dont la carte est présente, sinon du premier département coché.
 */
private suspend fun referencePosition(context: Context): RefPosition? {
    lastKnownLocation(context)?.let { return RefPosition(it.first, it.second, true) }
    val maps = ConnectClient.mapsDir(context).listFiles()?.map { it.name.removeSuffix(".pmtiles") } ?: emptyList()
    val checked = runCatching { AppGraph.settings.flow.first().departements }.getOrDefault(emptyList())
    val dept = (maps + checked).firstNotNullOfOrNull { Departements.get(it) } ?: return null
    return RefPosition(dept.lat, dept.lon, false)
}

/** Dernière position connue tous fournisseurs confondus (la plus récente), ou null. */
private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        lm.getProviders(true)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (e: SecurityException) {
        null
    }
}

// ---------------------------------------------------------------------------------------------
// Actions : appeler (clavier seulement) et y aller (appli GPS)
// ---------------------------------------------------------------------------------------------

/** Ouvre le clavier d'appel avec le numéro, SANS composer. */
private fun dial(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits(phone)))
    runCatching { context.startActivity(intent) }
        .onFailure { ErrorLogger.log("Intoxication", "Clavier d'appel indisponible", it) }
}

/** Ouvre une appli GPS sur un lieu (choix de l'appli). */
private fun goTo(context: Context, lat: Double, lon: Double, name: String) {
    val la = String.format(Locale.US, "%.6f", lat)
    val lo = String.format(Locale.US, "%.6f", lon)
    val uri = Uri.parse("geo:$la,$lo?q=$la,$lo(${Uri.encode(name)})")
    val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Y aller avec…")
    runCatching { context.startActivity(chooser) }
        .onFailure { ErrorLogger.log("Intoxication", "Aucune appli GPS", it) }
}

// ---------------------------------------------------------------------------------------------
// Fiche « En cas d'intoxication »
// ---------------------------------------------------------------------------------------------

@Composable
fun IntoxScreen(nav: NavController, sortieId: String?) {
    SafetyTheme {
        IntoxContent(nav, sortieId)
    }
}

@Composable
private fun IntoxContent(nav: NavController, sortieId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sorties concernées (une ou plusieurs) et fiche associée.
    val initialIds = remember(sortieId) { sortieId?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList() }
    var selected by rememberSaveable { mutableStateOf(ArrayList(initialIds)) }
    var ficheId by rememberSaveable { mutableStateOf<String?>(null) }
    var ficheCreatedAt by rememberSaveable { mutableStateOf(0L) }
    var notes by rememberSaveable { mutableStateOf("") }
    var ficheLoaded by rememberSaveable { mutableStateOf(false) }

    // Reprend la fiche existante pour ces sorties (pas de doublon à chaque ouverture).
    LaunchedEffect(Unit) {
        if (ficheLoaded) return@LaunchedEffect
        if (initialIds.isNotEmpty()) {
            val existing = withContext(Dispatchers.IO) { AppGraph.db.intoxDao().latestFor(initialIds.joinToString(",")) }
            if (existing != null) {
                ficheId = existing.id
                ficheCreatedAt = existing.createdAt
                notes = existing.notes ?: ""
            }
        }
        ficheLoaded = true
    }

    // Enregistrement automatique (léger délai pendant la saisie des notes).
    LaunchedEffect(selected, notes, ficheLoaded) {
        if (!ficheLoaded || selected.isEmpty()) return@LaunchedEffect
        delay(600)
        val id = ficheId ?: newId().also { ficheId = it }
        if (ficheCreatedAt == 0L) ficheCreatedAt = System.currentTimeMillis()
        runCatching {
            withContext(Dispatchers.IO) {
                AppGraph.db.intoxDao().upsert(
                    IntoxFiche(id = id, createdAt = ficheCreatedAt, sortieIds = selected.joinToString(","), notes = notes.ifBlank { null }),
                )
            }
        }.onFailure { ErrorLogger.log("Intoxication", "Fiche non enregistrée", it) }
    }

    // Position, centre antipoison et urgences.
    val position by produceState<RefPosition?>(initialValue = null) { value = referencePosition(context) }
    val center = remember(position) {
        val centers = PoisonCenters.load(context)
        position?.let { PoisonCenters.forPosition(centers, it.lat, it.lon) } ?: centers.firstOrNull()
    }
    val hospitals by produceState(initialValue = emptyList<Pair<Hospital, Double>>(), position) {
        val p = position ?: return@produceState
        val all = withContext(Dispatchers.IO) { AppGraph.db.hospitalDao().all() }
        value = PoisonCenters.nearestHospitals(all, p.lat, p.lon)
    }

    ChampiScreen(title = "En cas d'intoxication", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 15 · SAMU (rouge).
            Banner(
                title = "15 · SAMU",
                subtitle = "Urgence vitale, malaise, troubles importants",
                background = ChampiColors.Urgence,
                content = Color.White,
            ) { CallButton(onClick = { dial(context, "15") }, inverted = true) }

            // Centre antipoison selon la position.
            Banner(
                title = "Centre antipoison",
                subtitle = center?.let { "${it.name} · ${formatPhone(it.phone)}" } ?: "Aucun centre connu",
                background = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            ) { center?.let { c -> CallButton(onClick = { dial(context, c.phone) }) } }

            // Urgences les plus proches.
            Banner(
                title = "Urgences",
                subtitle = when {
                    position == null -> "Position inconnue : coche tes départements et lance la synchro Champimap Connect."
                    hospitals.isEmpty() -> "Aucune urgence téléchargée : lance la synchro Champimap Connect. En cas de doute, appelle le 15."
                    position?.fromGps == true -> "Les plus proches de ta dernière position"
                    else -> "Les plus proches de la zone de tes cartes"
                },
                background = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {}
            hospitals.forEach { (h, km) ->
                Banner(
                    title = h.name,
                    subtitle = buildString {
                        append(String.format(Locale.FRANCE, "%.0f km à vol d'oiseau", km))
                        h.phone?.let { append(" · ${formatPhone(it)}") }
                    },
                    background = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurface,
                ) {
                    h.phone?.let { p -> CallButton(onClick = { dial(context, p) }) }
                    Spacer(Modifier.width(6.dp))
                    GoButton(onClick = { goTo(context, h.lat, h.lon, h.name) })
                }
            }

            // Sortie(s) concernée(s).
            SectionTitle("Sortie concernée")
            if (selected.isEmpty()) {
                SortiePicker(
                    selected = emptySet(),
                    onToggle = { id -> selected = ArrayList(selected + id) },
                )
            } else {
                selected.forEach { id -> SortieBlock(id) }
                ChampiCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (heure du repas, symptômes…)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    if (ficheCreatedAt > 0) {
                        VSpace(6.dp)
                        Text(
                            "Fiche enregistrée le ${dateTimeLabel(ficheCreatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Les bons réflexes.
            SectionTitle("Les bons réflexes")
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    "Garde les restes et les épluchures · note l'heure du repas et des premiers symptômes · ne te fais pas vomir.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Accès à toutes les fiches.
            ChampiButton("Toutes les fiches", onClick = {
                scope.launch { nav.navigate(Routes.INTOX_LIST) { launchSingleTop = true } }
            })
            VSpace(16.dp)
        }
    }
}

/** Bandeau : titre et sous-titre à gauche, boutons-icônes à droite. */
@Composable
private fun Banner(
    title: String,
    subtitle: String?,
    background: Color,
    content: Color,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(background, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = content) }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** Bouton-icône « appeler » (ouvre le clavier, ne compose pas). */
@Composable
private fun CallButton(onClick: () -> Unit, inverted: Boolean = false) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (inverted) Color.White else MaterialTheme.colorScheme.primary,
            contentColor = if (inverted) ChampiColors.Urgence else MaterialTheme.colorScheme.onPrimary,
        ),
    ) { Icon(Icons.Filled.Call, contentDescription = "Appeler") }
}

/** Bouton-icône « y aller » (appli GPS). */
@Composable
private fun GoButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ),
    ) { Icon(Icons.Filled.NearMe, contentDescription = "Y aller") }
}

/** Détail d'une sortie : espèces, quantités, miniatures des photos. */
@Composable
private fun SortieBlock(sortieId: String) {
    val summary by produceState<SortieSummary?>(initialValue = null, sortieId) {
        value = runCatching { loadSummary(sortieId) }
            .onFailure { ErrorLogger.log("Intoxication", "Sortie illisible", it) }
            .getOrNull()
    }
    ChampiCard(Modifier.fillMaxWidth()) {
        val s = summary
        if (s == null) {
            Text("Chargement…", style = MaterialTheme.typography.bodyMedium)
        } else {
            SortieDetail(s)
        }
    }
}

/** Contenu d'une sortie chargée. */
@Composable
private fun SortieDetail(s: SortieSummary) {
    Column {
        Text(sortieLabel(s.sortie), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (s.species.isEmpty()) {
            Text("Aucune espèce notée pour cette sortie.", style = MaterialTheme.typography.bodyMedium)
        }
        s.species.forEach { line ->
            VSpace(8.dp)
            Text(
                buildString {
                    append(line.name)
                    if (!line.latin.isNullOrBlank()) append(" (${line.latin})")
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = when (line.edibility) {
                    Edibility.TOXIQUE, Edibility.MORTEL -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                buildString {
                    append(if (line.count > 1) "${line.count} récoltes" else "1 récolte")
                    if (line.weightG > 0) append(" · ${line.weightG} g")
                    when (line.edibility) {
                        Edibility.TOXIQUE -> append(" · toxique")
                        Edibility.MORTEL -> append(" · mortel")
                        Edibility.A_EVITER -> append(" · à éviter")
                        Edibility.PSYCHOTROPE -> append(" · psychotrope")
                        else -> Unit
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.photos.isNotEmpty()) {
                VSpace(6.dp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(line.photos) { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = line.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** Choix d'une ou plusieurs sorties (cases à cocher, plus récentes d'abord). */
@Composable
private fun SortiePicker(selected: Set<String>, onToggle: (String) -> Unit, limit: Int = 30) {
    val sorties by remember { AppGraph.db.sortieDao().all() }.collectAsState(initial = emptyList())
    ChampiCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        if (sorties.isEmpty()) {
            Text("Aucune sortie enregistrée.", modifier = Modifier.padding(16.dp))
        }
        sorties.take(limit).forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(s.id) }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = s.id in selected, onCheckedChange = { onToggle(s.id) })
                Text(sortieLabel(s), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Liste des fiches
// ---------------------------------------------------------------------------------------------

@Composable
fun IntoxListScreen(nav: NavController) {
    SafetyTheme {
        IntoxListContent(nav)
    }
}

@Composable
private fun IntoxListContent(nav: NavController) {
    val scope = rememberCoroutineScope()
    val fiches by remember { AppGraph.db.intoxDao().all() }.collectAsState(initial = emptyList())
    val sorties by remember { AppGraph.db.sortieDao().all() }.collectAsState(initial = emptyList())
    val byId = remember(sorties) { sorties.associateBy { it.id } }
    var creating by rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var toDelete by remember { mutableStateOf<IntoxFiche?>(null) }

    ChampiScreen(title = "Intoxications", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (creating) {
                // Nouvelle fiche : cocher une ou plusieurs sorties.
                SectionTitle("Nouvelle fiche : coche les sorties concernées")
                SortiePicker(
                    selected = checked.toSet(),
                    onToggle = { id -> checked = ArrayList(if (id in checked) checked - id else checked + id) },
                )
                ChampiButton(
                    text = "Créer la fiche",
                    enabled = checked.isNotEmpty(),
                    onClick = {
                        val ids = checked.joinToString(",")
                        creating = false
                        checked = ArrayList()
                        nav.navigate(Routes.intox(ids))
                    },
                )
                androidx.compose.material3.TextButton(onClick = { creating = false; checked = ArrayList() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Annuler")
                }
            } else {
                ChampiButton("Nouvelle fiche", onClick = { creating = true })
                ChampiButton(
                    "Numéros d'urgence",
                    onClick = { nav.navigate(Routes.intox()) },
                    color = ChampiColors.Urgence,
                )
            }

            SectionTitle("Fiches enregistrées")
            if (fiches.isEmpty()) {
                Text(
                    "Aucune fiche pour l'instant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            fiches.forEach { f ->
                val ids = f.sortieIds.split(',').filter { it.isNotBlank() }
                ChampiCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { nav.navigate(Routes.intox(f.sortieIds)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dateTimeLabel(f.createdAt), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            ids.forEach { id ->
                                Text(
                                    byId[id]?.let { sortieLabel(it) } ?: "Sortie supprimée",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            f.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        }
                        IconButton(onClick = { toDelete = f }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer la fiche")
                        }
                    }
                }
            }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }

    toDelete?.let { f ->
        ConfirmDialog(
            title = "Supprimer cette fiche ?",
            text = "Les sorties et les récoltes ne sont pas supprimées.",
            onConfirm = {
                toDelete = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { AppGraph.db.intoxDao().delete(f.id) } }
                        .onFailure { ErrorLogger.log("Intoxication", "Fiche non supprimée", it) }
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}
