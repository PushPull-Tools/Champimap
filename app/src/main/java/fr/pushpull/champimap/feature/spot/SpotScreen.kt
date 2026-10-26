package fr.pushpull.champimap.feature.spot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.PhotoOwner
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.feature.bonus.ForestIllustration
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Fiche spot (module spot) : photo ou illustration, nom et coordonnées, « Trouvé ! »,
 * « Spot vide », « Je suis au pied », « Ajuster la position », récolte du jour,
 * photos, détails du spot et historique des positions.
 * Tout est enregistré automatiquement (300 ms après la dernière modification).
 */

/**
 * Écran d'un spot.
 * @param sortieId sortie en cours (la récolte du jour y est rattachée), null hors sortie.
 */
@Composable
fun SpotScreen(nav: NavController, spotId: String, sortieId: String?) {
    val db = AppGraph.db
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spot by remember(spotId) { db.spotDao().flow(spotId) }.collectAsState(initial = null)

    // --- État de l'écran ---------------------------------------------------------------
    var recolteId by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf(0L) }
    var undo by remember { mutableStateOf<UndoAction?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var auPied by remember { mutableStateOf(false) }
    var identifying by remember { mutableStateOf(false) }
    var proposals by remember { mutableStateOf<Pair<String, List<Pair<Species, Float>>>?>(null) }
    val saver = remember { AutoSaver { savedAt = System.currentTimeMillis() } }
    val camera = rememberCamera { info = it }
    val locationPerm = rememberLocationPermission { granted ->
        if (granted) auPied = true else info = "Autorise la position pour mesurer le spot."
    }

    // Récolte affichée à l'ouverture (sans en créer) + nom par défaut si le spot n'en a pas.
    LaunchedEffect(spotId, sortieId) {
        recolteId = SpotRepository.findRecolte(spotId, sortieId)?.id
        val s = db.spotDao().get(spotId)
        if (s != null && s.name.isBlank()) {
            val n = db.spotDao().all().first().size
            SpotRepository.updateSpot(spotId) { it.copy(name = defaultSpotName(n, s.lat, s.lon)) }
        }
    }
    // Les bandeaux disparaissent seuls : 10 s pour « Annuler », 3 s pour une information.
    LaunchedEffect(undo) { if (undo != null) { delay(10_000); undo = null } }
    LaunchedEffect(info) { if (info != null) { delay(3_000); info = null } }

    /**
     * Récolte qui reçoit une nouvelle espèce (« Trouvé ! ») :
     * en sortie → celle de la sortie (créée à la volée) ; hors sortie → celle affichée si elle
     * est d'aujourd'hui et non verrouillée, sinon une nouvelle récolte hors sortie.
     * Null si la récolte de la sortie est verrouillée.
     */
    suspend fun recolteForFound(): Recolte? {
        val r = if (sortieId != null) {
            SpotRepository.ensureRecolte(spotId, sortieId)
        } else {
            val current = recolteId?.let { db.recolteDao().get(it) }
            if (current != null && current.deletedAt == null && !current.locked && SpotRepository.isToday(current.time)) current
            else SpotRepository.ensureRecolte(spotId, null, forceNew = true)
        }
        recolteId = r.id
        return if (r.locked) null else r
    }

    val lockedMsg = "Récolte verrouillée : ouvre le cadenas pour ajouter une espèce."

    /** « Trouvé ! → Liste ». */
    fun foundByList() = scope.launch {
        val r = recolteForFound() ?: run { info = lockedMsg; return@launch }
        nav.navigate(Routes.especesPicker(r.id))
    }

    /** « Trouvé ! → Photo » : photo, identification (provisoire : aucune), puis liste. */
    fun foundByPhoto() = camera { file ->
        scope.launch {
            val r = recolteForFound() ?: run { info = lockedMsg; return@launch }
            // Photo « en attente » rattachée à la récolte : elle passera sur l'espèce choisie.
            SpotRepository.addPhoto(PhotoOwner.RECOLTE_ITEM, r.id, file)
            val found = withContext(Dispatchers.Default) {
                runCatching { CurrentIdentifier.identify(file) }.getOrDefault(emptyList())
            }
            if (found.isEmpty()) {
                identifying = true
                delay(1_500)
                identifying = false
                nav.navigate(Routes.especesPicker(r.id))
            } else {
                proposals = r.id to found
            }
        }
    }

    // --- Écran « Ajuster la position » (remplace la fiche) ---------------------------------
    val s = spot
    BackHandler(enabled = adjusting) { adjusting = false }
    if (adjusting && s != null) {
        AdjustPositionScreen(
            spot = s,
            onCancel = { adjusting = false },
            onConfirm = { lat, lon ->
                adjusting = false
                scope.launch { SpotRepository.moveOnMap(spotId, lat, lon); savedAt = System.currentTimeMillis() }
                info = "Position ajustée. L'ancienne reste dans l'historique."
            },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        ChampiScreen(
            title = "Spot",
            onBack = { nav.popBackStack() },
            actions = {
                SavedIndicator(savedAt)
                IconButton(onClick = { nav.navigate(Routes.partageSpot(spotId)) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Partager")
                }
            },
        ) {
            if (s == null) {
                Text(champiText("Spot introuvable."), Modifier.padding(16.dp))
                return@ChampiScreen
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpotHero(spotId) {
                    camera { file -> scope.launch { SpotRepository.addPhoto(PhotoOwner.SPOT, spotId, file); savedAt = System.currentTimeMillis() } }
                }
                SpotIdentityCard(s, saver)

                // Actions principales : « Trouvé ! » (menu Photo / Liste) et « Spot vide ».
                Row(horizontalArrangement = ROW_GAP) {
                    Box(Modifier.weight(1f)) {
                        var menu by remember { mutableStateOf(false) }
                        ChampiButton("Trouvé !", onClick = { menu = true }, color = ChampiColors.Girolle)
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(champiText("Photo")) },
                                leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                                onClick = { menu = false; foundByPhoto() },
                                modifier = Modifier.heightIn(min = 56.dp),
                            )
                            DropdownMenuItem(
                                text = { Text(champiText("Liste")) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                onClick = { menu = false; foundByList() },
                                modifier = Modifier.heightIn(min = 56.dp),
                            )
                        }
                    }
                    ChampiOutlinedButton(
                        "Spot vide",
                        onClick = {
                            scope.launch {
                                val v = SpotRepository.markEmpty(spotId, sortieId)
                                undo = UndoAction("Visite « spot vide » notée") { SpotRepository.undoVisit(v.id) }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    )
                }
                Row(horizontalArrangement = ROW_GAP) {
                    ChampiOutlinedButton(
                        "Je suis au pied",
                        onClick = { if (hasFineLocation(context)) auPied = true else locationPerm() },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    )
                    ChampiOutlinedButton(
                        "Ajuster la position",
                        onClick = { adjusting = true },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    )
                }

                RecolteCard(
                    recolteId = recolteId,
                    saver = saver,
                    onUndo = { undo = it },
                    onInfo = { info = it },
                    onItemPhoto = { itemId ->
                        camera { file -> scope.launch { SpotRepository.addPhoto(PhotoOwner.RECOLTE_ITEM, itemId, file) } }
                    },
                    onOpenSpecies = { nav.navigate(Routes.ficheEspece(it)) },
                )
                PhotosCard(spotId, onInfo = { info = it })
                SpotDetailsCard(s, saver, onInfo = { info = it })
                PositionsCard(s, onReverted = { info = "Position rétablie." ; savedAt = System.currentTimeMillis() })
                VSpace(96.dp) // place pour les bandeaux du bas
            }
        }

        // --- Bandeaux du bas ---------------------------------------------------------------
        val u = undo
        if (u != null) {
            UndoBanner(
                u,
                onUndo = { undo = null; AppGraph.appScope.launch { u.undo() } },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else info?.let { InfoBanner(it, Modifier.align(Alignment.BottomCenter)) }
    }

    // --- Boîtes de dialogue -------------------------------------------------------------
    if (auPied) {
        AuPiedDialog(
            onDismiss = { auPied = false },
            onResult = { fix ->
                auPied = false
                scope.launch { SpotRepository.addAuPied(spotId, fix); savedAt = System.currentTimeMillis() }
                info = "Nouvelle mesure ajoutée à la moyenne."
            },
        )
    }
    if (identifying) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(champiText("Identification automatique bientôt disponible")) },
            text = { Text(champiText("Choisis l'espèce dans la liste.")) },
            confirmButton = {},
        )
    }
    proposals?.let { (rid, list) ->
        ProposalsDialog(
            list = list,
            onPick = { sp -> proposals = null; scope.launch { SpotRepository.addItem(rid, sp.id) } },
            onList = { proposals = null; nav.navigate(Routes.especesPicker(rid)) },
            onDismiss = { proposals = null },
        )
    }
}

/** Nom par défaut d'un spot : « Spot N (lat ; lon) ». Utilisable par les autres modules. */
fun defaultSpotName(n: Int, lat: Double, lon: Double): String = "Spot $n (${formatCoords(lat, lon)})"

/** Photo du spot (la plus récente) ou illustration de forêt, avec le bouton « Photo du spot ». */
@Composable
private fun SpotHero(spotId: String, onTakePhoto: () -> Unit) {
    val photo by remember(spotId) { AppGraph.db.photoDao().latestOf(spotId) }.collectAsState(initial = null)
    Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(18.dp))) {
        val p = photo
        if (p != null && File(p.path).exists()) {
            AsyncImage(
                model = File(p.path),
                contentDescription = "Photo du spot",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ForestIllustration(Modifier.fillMaxSize())
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                .clip(RoundedCornerShape(14.dp)).clickable(onClick = onTakePhoto),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(champiText("Photo du spot"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Petit « ✓ Enregistré » discret, visible 2 s après chaque enregistrement. */
@Composable
private fun SavedIndicator(savedAt: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(savedAt) {
        if (savedAt > 0) { visible = true; delay(2_000); visible = false }
    }
    if (visible) {
        Text(
            champiText("✓ Enregistré"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/** Propositions de l'identification automatique (quand un modèle sera disponible). */
@Composable
private fun ProposalsDialog(
    list: List<Pair<Species, Float>>,
    onPick: (Species) -> Unit,
    onList: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(champiText("C'est peut-être…")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.take(5).forEach { (sp, score) ->
                    TextButton(onClick = { onPick(sp) }, modifier = Modifier.fillMaxWidth()) {
                        SpeciesDot(sp)
                        Spacer(Modifier.width(10.dp))
                        Text(champiText(sp.nameFr), modifier = Modifier.weight(1f))
                        Text("${(score * 100).toInt()} %")
                    }
                }
                Text(
                    champiText("Vérifie toujours toi-même : une photo ne suffit pas à identifier un champignon."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onList) { Text(champiText("Voir la liste")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
