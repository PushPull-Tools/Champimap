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
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/*
 * Écrans « espèces » du module spot :
 *  - EspecesPickerScreen : « Complète ta cueillette » (choisir l'espèce ajoutée à une récolte) ;
 *  - CatalogueScreen : onglet Espèces (toutes les espèces, recherche, filtres, index A–Z) ;
 *  - FicheEspeceScreen : fiche d'une espèce (photos, comestibilité, sosies dangereux, saison, habitat).
 * L'écran de résultat de l'identification par IA est dans IaResults.kt.
 * La liste elle-même (recherche rapide, sections, index A–Z) est dans SpeciesBrowser.kt.
 */

/** Rappel de sécurité affiché sur les fiches. */
private const val RAPPEL_SECURITE = "En cas de doute, fais vérifier par un pharmacien ou une société mycologique."

/**
 * « Complète ta cueillette » : choix d'une espèce pour la récolte `recolteId`.
 * Après « Trouvé ! → Photo » (photo « en attente » rattachée à la récolte) et si le modèle est disponible :
 * écran de résultat de l'IA (IaResultsPanel) avec ses 5 pistes ; « Aucune ne correspond » ou une
 * analyse sans résultat (erreur comprise) ouvrent la liste complète, la photo restant en haut.
 * Sans modèle : liste directe, avec une ligne d'info et un bouton vers l'écran Champimap Connect.
 * Liste : recherche, filtres, « Récentes », « De saison » et toutes les espèces avec l'index A–Z.
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

    // Chemin de la photo à identifier, gardé même quand elle passe sur l'espèce choisie
    // (évite un clignotement de la liste juste avant le retour à la fiche spot).
    var photoPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPhoto?.path) { pendingPhoto?.path?.let { photoPath = it } }

    // Réévalué quand un modèle arrive (ou change) par Champimap Connect.
    val iaRevision by IaAssets.revision.collectAsState()
    val hasModel = remember(iaRevision) { CurrentIdentifier !is NoModelIdentifier }
    // Résultat de l'analyse, rattaché à la photo analysée (évite d'afficher celui d'une autre photo).
    // Toute erreur donne une liste vide (repli silencieux sur la liste).
    val analysis by produceState<Pair<String?, List<Pair<Species, Float>>>?>(initialValue = null, photoPath, hasModel) {
        val path = photoPath
        value = path to (
            if (path == null || !hasModel) emptyList()
            else runCatching { CurrentIdentifier.identify(File(path)) }.getOrDefault(emptyList())
        )
    }
    // null = analyse en cours pour la photo actuelle.
    val proposals: List<Pair<Species, Float>>? = analysis?.takeIf { it.first == photoPath }?.second
    var showList by remember(photoPath) { mutableStateOf(false) }

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
        val path = photoPath
        val list = proposals
        // Écran de résultat de l'IA : pendant l'analyse, puis s'il y a des pistes.
        if (path != null && hasModel && !showList && (list == null || list.isNotEmpty())) {
            IaResultsPanel(
                photo = File(path),
                proposals = list,
                all = all,
                onPick = { pick(it) },
                onOpenSpecies = { nav.navigate(Routes.ficheEspece(it)) },
                onShowList = { showList = true },
                modifier = Modifier.fillMaxSize(),
            )
            return@ChampiScreen
        }
        SpeciesBrowser(
            all = all,
            recentIds = recentIds,
            onPick = { pick(it) },
            header = {
                if (path != null) {
                    val message = when {
                        !hasModel -> "Identification par photo : télécharge le modèle avec Champimap Connect (≈ ${IaModel.APPROX_SIZE_MO} Mo)."
                        list.isNullOrEmpty() -> "Pas de piste de l'IA pour cette photo : choisis l'espèce dans la liste."
                        else -> "Choisis l'espèce dans la liste."
                    }
                    PendingPhotoHeader(
                        photo = File(path),
                        message = message,
                        onShowProposals = if (hasModel && !list.isNullOrEmpty()) ({ showList = false }) else null,
                        onOpenConnect = if (!hasModel) ({ nav.navigate(Routes.CONNECT) }) else null,
                    )
                }
            },
        )
    }
}

/**
 * Bandeau du haut de la liste après « Trouvé ! → Photo » : vignette de la photo, info FIXE
 * (pas de message éphémère) et, s'il y a des pistes de l'IA, un lien pour les revoir ;
 * sans modèle, un bouton vers l'écran Champimap Connect.
 */
@Composable
private fun PendingPhotoHeader(photo: File, message: String, onShowProposals: (() -> Unit)?, onOpenConnect: (() -> Unit)? = null) {
    ChampiCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = photo,
                contentDescription = "Photo prise",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    champiText(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onShowProposals != null) {
                    TextButton(onClick = onShowProposals, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                        Text(champiText("Revoir les pistes de l'IA"))
                    }
                }
                if (onOpenConnect != null) {
                    TextButton(onClick = onOpenConnect, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                        Text(champiText("Ouvrir Champimap Connect"))
                    }
                }
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

            // Avertissement : espèce dangereuse elle-même, ou sosies dangereux.
            val dangerous = s.edibility == Edibility.MORTEL || s.edibility == Edibility.TOXIQUE
            val unknown = s.edibility == Edibility.INCONNU
            if (dangerous || unknown || sosies.isNotEmpty() || !s.lookalikeWarning.isNullOrBlank()) {
                WarningCard(s, sosies) { nav.navigate(Routes.ficheEspece(it)) }
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

/** Avertissement compact : une ligne (danger de l'espèce ou sosies), dépliable pour les détails. */
@Composable
private fun WarningCard(s: Species, sosies: List<Species>, onOpen: (String) -> Unit) {
    val danger = s.edibility == Edibility.MORTEL || s.edibility == Edibility.TOXIQUE || s.edibility == Edibility.INCONNU
    val titre = when (s.edibility) {
        Edibility.MORTEL -> "Mortelle : ne jamais consommer"
        Edibility.TOXIQUE -> "Toxique : ne pas consommer"
        Edibility.INCONNU -> "Comestibilité inconnue : ne pas consommer"
        else -> if (sosies.isNotEmpty()) "Attention aux sosies (${sosies.size})" else "Attention aux confusions"
    }
    val texte = listOfNotNull(
        s.lookalikeWarning?.takeIf { it.isNotBlank() },
        if (danger && sosies.isNotEmpty()) "Sosies à ne pas confondre :" else null,
    ).joinToString("\n").ifBlank { null }
    SosiesRepliables(titre, texte, sosies, onOpen)
}
