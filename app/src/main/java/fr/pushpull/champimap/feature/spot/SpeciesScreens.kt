package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/*
 * Écrans « espèces » du module spot :
 *  - EspecesPickerScreen : « Complète ta cueillette » (choisir l'espèce ajoutée à une récolte) ;
 *  - CatalogueScreen : onglet Espèces (toutes les espèces, recherche) ;
 *  - FicheEspeceScreen : fiche d'une espèce (comestibilité, sosies dangereux, saison, habitat).
 */

/** Rappel de sécurité affiché sur les fiches. */
private const val RAPPEL_SECURITE = "En cas de doute, fais vérifier par un pharmacien ou une société mycologique."

/** Champ de recherche (nom courant ou latin, fautes tolérées). */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(champiText("Nom courant ou latin")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** Ligne d'espèce : disque coloré, noms, pastille de comestibilité. */
@Composable
private fun SpeciesRow(s: Species, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesDot(s, 16)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(champiText(s.nameFr), style = MaterialTheme.typography.titleMedium)
            Text(s.nameLatin, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        EdibilityChip(s.edibility)
    }
}

/**
 * « Complète ta cueillette » : choix d'une espèce pour la récolte `recolteId`.
 * Sections : recherche, « Tes habituels », « Les plus présents dans tes départements »
 * (pour l'instant : espèces marquées `common`), puis « Voir toutes les espèces (A → Z) ».
 */
@Composable
fun EspecesPickerScreen(nav: NavController, recolteId: String) {
    val context = LocalContext.current
    val db = AppGraph.db
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SpeciesLoader.ensureLoaded(context) }

    val all by remember { db.speciesDao().all() }.collectAsState(initial = emptyList())
    val habitualIds by remember { db.recolteDao().habitualSpeciesIds(8) }.collectAsState(initial = emptyList())
    val recolte by remember(recolteId) { db.recolteDao().flow(recolteId) }.collectAsState(initial = null)
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val byId = remember(all) { all.associateBy { it.id } }
    val habituels = habitualIds.mapNotNull { byId[it] }
    val communs = all.filter { it.common && it.id !in habitualIds }

    /** Ajoute l'espèce à la récolte puis revient à la fiche spot. */
    fun pick(s: Species) {
        if (busy) return
        busy = true
        scope.launch {
            SpotRepository.addItem(recolteId, s.id)
            nav.popBackStack()
        }
    }

    ChampiScreen(title = "Complète ta cueillette", onBack = { nav.popBackStack() }) {
        if (recolte?.locked == true) {
            Text(
                champiText("Cette récolte est verrouillée : ouvre le cadenas pour ajouter une espèce."),
                modifier = Modifier.padding(16.dp),
            )
            return@ChampiScreen
        }
        SearchField(query) { query = it }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isNotBlank()) {
                val found = searchSpecies(all, query)
                if (found.isEmpty()) {
                    item { Text(champiText("Aucune espèce trouvée."), Modifier.padding(16.dp)) }
                }
                items(found, key = { "q" + it.id }) { SpeciesRow(it) { pick(it) } }
            } else {
                if (all.isEmpty()) {
                    item { Text(champiText("Liste des espèces en cours de chargement…"), Modifier.padding(16.dp)) }
                }
                if (habituels.isNotEmpty()) {
                    item { SectionTitle("Tes habituels", Modifier.padding(horizontal = 16.dp)) }
                    items(habituels, key = { "h" + it.id }) { SpeciesRow(it) { pick(it) } }
                }
                if (communs.isNotEmpty()) {
                    item { SectionTitle("Les plus présents dans tes départements", Modifier.padding(horizontal = 16.dp)) }
                    items(communs, key = { "c" + it.id }) { SpeciesRow(it) { pick(it) } }
                }
                if (!showAll) {
                    item {
                        ChampiOutlinedButton(
                            "Voir toutes les espèces (A → Z)",
                            onClick = { showAll = true },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    item { SectionTitle("Toutes les espèces (A → Z)", Modifier.padding(horizontal = 16.dp)) }
                    items(all, key = { "a" + it.id }) { SpeciesRow(it) { pick(it) } }
                }
            }
            item { VSpace(24.dp) }
        }
    }
}

/** Onglet Espèces : catalogue complet avec recherche. */
@Composable
fun CatalogueScreen(nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SpeciesLoader.ensureLoaded(context) }
    val all by remember { AppGraph.db.speciesDao().all() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val shown = remember(all, query) { searchSpecies(all, query) }

    ChampiScreen(title = "Espèces") {
        SearchField(query) { query = it }
        Text(
            champiText(RAPPEL_SECURITE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        champiText(if (all.isEmpty()) "Liste des espèces en cours de chargement…" else "Aucune espèce trouvée."),
                        Modifier.padding(16.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { s -> SpeciesRow(s) { nav.navigate(Routes.ficheEspece(s.id)) } }
            item { VSpace(24.dp) }
        }
    }
}

/** Fiche d'une espèce : comestibilité, sosies dangereux, saison, habitat, description. */
@Composable
fun FicheEspeceScreen(nav: NavController, speciesId: String) {
    val db = AppGraph.db
    val species by remember(speciesId) { db.speciesDao().flow(speciesId) }.collectAsState(initial = null)
    val lookalikeIds = species?.lookalikes?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val lookalikes by remember(lookalikeIds) {
        if (lookalikeIds.isEmpty()) flowOf(emptyList<Species>()) else db.speciesDao().all()
    }.collectAsState(initial = emptyList())
    val sosies = lookalikes.filter { it.id in lookalikeIds }

    ChampiScreen(title = species?.nameFr ?: "Espèce", onBack = { nav.popBackStack() }) {
        val s = species
        if (s == null) {
            Text(champiText("Espèce introuvable."), Modifier.padding(16.dp))
            return@ChampiScreen
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChampiCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeciesDot(s, 20)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(champiText(s.nameFr), style = MaterialTheme.typography.titleLarge)
                        Text(s.nameLatin, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    EdibilityChip(s.edibility)
                }
                s.otherNames?.takeIf { it.isNotBlank() }?.let {
                    Text(champiText("Aussi appelé : $it"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // Avertissement : espèce dangereuse elle-même, ou sosies dangereux.
            val dangerous = s.edibility == Edibility.MORTEL || s.edibility == Edibility.TOXIQUE
            if (dangerous || sosies.isNotEmpty() || !s.lookalikeWarning.isNullOrBlank()) {
                WarningCard(s, sosies) { nav.navigate(Routes.ficheEspece(it)) }
            }

            ChampiCard(Modifier.fillMaxWidth()) {
                formatMonths(s.months)?.let {
                    SectionTitle("Saison")
                    Text(champiText(it.replaceFirstChar { c -> c.uppercase() }))
                }
                s.habitat?.takeIf { it.isNotBlank() }?.let {
                    SectionTitle("Habitat")
                    Text(champiText(it))
                }
                s.description?.takeIf { it.isNotBlank() }?.let {
                    SectionTitle("Description")
                    Text(champiText(it))
                }
            }

            Text(
                champiText(RAPPEL_SECURITE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(24.dp)
        }
    }
}

/** Encadré d'avertissement (sosies dangereux, toxicité). Couleurs fixes, lisibles dans tous les thèmes. */
@Composable
private fun WarningCard(s: Species, sosies: List<Species>, onOpen: (String) -> Unit) {
    ChampiCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = ChampiColors.Urgence)
            Spacer(Modifier.width(8.dp))
            Text(champiText("Attention"), style = MaterialTheme.typography.titleMedium, color = ChampiColors.Urgence)
        }
        when (s.edibility) {
            Edibility.MORTEL -> Text(champiText("Espèce mortelle : ne jamais consommer, même en petite quantité."))
            Edibility.TOXIQUE -> Text(champiText("Espèce toxique : ne pas consommer."))
            else -> {}
        }
        s.lookalikeWarning?.takeIf { it.isNotBlank() }?.let { Text(champiText(it), Modifier.padding(top = 6.dp)) }
        if (sosies.isNotEmpty()) {
            SectionTitle("Sosies à ne pas confondre")
            sosies.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(o.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpeciesDot(o, 12)
                    Spacer(Modifier.width(8.dp))
                    Text(champiText(o.nameFr), modifier = Modifier.weight(1f))
                    EdibilityChip(o.edibility)
                }
            }
        }
    }
}
