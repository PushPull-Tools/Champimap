package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Écrans « espèces » du module spot :
 *  - EspecesPickerScreen : « Complète ta cueillette » (choisir l'espèce ajoutée à une récolte) ;
 *  - CatalogueScreen : onglet Espèces (toutes les espèces, recherche, filtres, index A–Z) ;
 *  - FicheEspeceScreen : fiche d'une espèce (photos, comestibilité, sosies dangereux, saison, habitat).
 * La liste elle-même (recherche rapide, sections, index A–Z) est dans SpeciesBrowser.kt.
 */

/** Rappel de sécurité affiché sur les fiches. */
private const val RAPPEL_SECURITE = "En cas de doute, fais vérifier par un pharmacien ou une société mycologique."

/**
 * « Complète ta cueillette » : choix d'une espèce pour la récolte `recolteId`.
 * En haut, la photo prise avec « Trouvé ! → Photo » (photo « en attente » rattachée à la récolte)
 * avec une info fixe tant qu'il n'y a pas de modèle d'identification (plus tard : ses propositions).
 * Puis recherche, filtres, « Récentes », « De saison » et toutes les espèces avec l'index A–Z.
 */
@Composable
fun EspecesPickerScreen(nav: NavController, recolteId: String) {
    val context = LocalContext.current
    val db = AppGraph.db
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SpeciesLoader.ensureLoaded(context) }

    // Lectures uniques (flux Room) : la recherche travaille ensuite en mémoire.
    val all by remember { db.speciesDao().all() }.collectAsState(initial = emptyList())
    val recentIds by remember { db.recolteDao().recentSpeciesIds(6) }.collectAsState(initial = emptyList())
    val recolte by remember(recolteId) { db.recolteDao().flow(recolteId) }.collectAsState(initial = null)
    val pendingPhoto by remember(recolteId) { db.photoDao().latestOf(recolteId) }.collectAsState(initial = null)
    var busy by remember { mutableStateOf(false) }

    /** Ajoute l'espèce à la récolte (la photo en attente passe sur elle) puis revient à la fiche spot. */
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
        SpeciesBrowser(
            all = all,
            recentIds = recentIds,
            onPick = { pick(it) },
            header = {
                pendingPhoto?.let { p -> PendingPhotoHeader(File(p.path), onPick = { pick(it) }) }
            },
        )
    }
}

/**
 * Bandeau du haut après « Trouvé ! → Photo » : vignette de la photo et, à côté,
 * une info FIXE (pas de message éphémère) tant qu'aucun modèle n'est installé,
 * ou les propositions du modèle quand il existera (même emplacement).
 */
@Composable
private fun PendingPhotoHeader(photo: File, onPick: (Species) -> Unit) {
    val hasModel = CurrentIdentifier !is NoModelIdentifier
    // null = analyse en cours (seulement avec un vrai modèle).
    val proposals by produceState<List<Pair<Species, Float>>?>(initialValue = null, photo, hasModel) {
        value = if (!hasModel) emptyList() else withContext(Dispatchers.Default) {
            runCatching { CurrentIdentifier.identify(photo) }.getOrDefault(emptyList())
        }
    }
    ChampiCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = photo,
                contentDescription = "Photo prise",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            val list = proposals
            val message = when {
                !hasModel -> "Identification automatique bientôt disponible : choisis l'espèce dans la liste."
                list == null -> "Analyse de la photo…"
                list.isEmpty() -> "Pas de proposition : choisis l'espèce dans la liste."
                else -> "C'est peut-être… (vérifie toujours toi-même)"
            }
            Text(
                champiText(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        // Propositions du modèle (quand il existera) : appui = choisir l'espèce.
        proposals?.take(3)?.forEach { (sp, score) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(sp) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeciesDot(sp)
                Spacer(Modifier.width(10.dp))
                Text(champiText(sp.nameFr), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(score * 100).toInt()} %", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Onglet Espèces : catalogue complet (recherche, filtres, index A–Z). */
@Composable
fun CatalogueScreen(nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SpeciesLoader.ensureLoaded(context) }
    val all by remember { AppGraph.db.speciesDao().all() }.collectAsState(initial = emptyList())

    ChampiScreen(title = "Espèces") {
        SpeciesBrowser(
            all = all,
            recentIds = emptyList(),
            onPick = { s -> nav.navigate(Routes.ficheEspece(s.id)) },
            header = {
                Text(
                    champiText(RAPPEL_SECURITE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            },
        )
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
            // Photos de la fiche (1 à 3, avec crédit) ; rien si elles ne sont pas encore livrées.
            FichePhotosCarousel(s.id, Modifier.fillMaxWidth())
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

            // Saison toujours visible ; habitat et description repliés (contenu secondaire).
            val habitat = s.habitat?.takeIf { it.isNotBlank() }
            val description = s.description?.takeIf { it.isNotBlank() }
            val saison = formatMonths(s.months)
            if (saison != null || habitat != null || description != null) {
                ChampiCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    saison?.let {
                        SectionTitle("Saison")
                        Text(champiText(it.replaceFirstChar { c -> c.uppercase() }), modifier = Modifier.padding(bottom = 4.dp))
                    }
                    habitat?.let { Collapsible("Habitat") { Text(champiText(it), Modifier.padding(bottom = 8.dp)) } }
                    description?.let { Collapsible("Description") { Text(champiText(it), Modifier.padding(bottom = 8.dp)) } }
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
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(champiText("Attention"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
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
