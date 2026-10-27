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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.PhotoOwner
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.ForestIllustration
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/*
 * Fiche spot (module spot), de haut en bas :
 *  1. photo du spot (ou illustration) ;
 *  2. carte du spot : nom, coordonnées, et infos en lecture compacte (crayon = modifier) ;
 *  3. actions : « Trouvé ! » (feuille du bas Photo / Liste), puis Spot vide / Au pied / Ajuster ;
 *  4. récolte du jour (lignes repliables) ;
 *  5. photos ;
 *  6. historique des positions (replié).
 * Tout est enregistré automatiquement (300 ms après la dernière modification).
 * Messages temporaires : 4 s minimum (« Annuler » : 10 s).
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
    var foundSheet by remember { mutableStateOf(false) }
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
    // Les bandeaux disparaissent seuls : 10 s pour « Annuler », 4 s pour une information
    // (jamais moins : un message plus court n'a pas le temps d'être lu).
    LaunchedEffect(undo) { if (undo != null) { delay(10_000); undo = null } }
    LaunchedEffect(info) { if (info != null) { delay(INFO_MS); info = null } }

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

    /**
     * « Trouvé ! → Photo » : la photo est rattachée à la récolte (« en attente »), puis le choix
     * d'espèce s'ouvre directement, avec la photo en vignette et une info fixe en haut
     * (plus tard : les propositions du modèle d'identification, au même endroit).
     */
    fun foundByPhoto() = camera { file ->
        scope.launch {
            val r = recolteForFound() ?: run { info = lockedMsg; return@launch }
            SpotRepository.addPhoto(PhotoOwner.RECOLTE_ITEM, r.id, file)
            nav.navigate(Routes.especesPicker(r.id))
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
                // 1-2. Photo puis carte du spot (identité + infos en lecture compacte).
                SpotHero(spotId) {
                    camera { file -> scope.launch { SpotRepository.addPhoto(PhotoOwner.SPOT, spotId, file); savedAt = System.currentTimeMillis() } }
                }
                SpotHeaderCard(s, saver, onInfo = { info = it })

                // 3. Actions.
                SpotActions(
                    onFound = { foundSheet = true },
                    onEmpty = {
                        scope.launch {
                            val v = SpotRepository.markEmpty(spotId, sortieId)
                            undo = UndoAction("Visite « spot vide » notée") { SpotRepository.undoVisit(v.id) }
                        }
                    },
                    onAuPied = { if (hasFineLocation(context)) auPied = true else locationPerm() },
                    onAdjust = { adjusting = true },
                )

                // 4-6. Récolte du jour, photos, historique des positions (replié).
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
    if (foundSheet) {
        FoundSheet(
            onDismiss = { foundSheet = false },
            onPhoto = { foundByPhoto() },
            onList = { foundByList() },
        )
    }
}

/** Durée minimale d'affichage d'un message temporaire (ms). */
private const val INFO_MS = 4_000L

/** Nom par défaut d'un spot : « Spot N », sans coordonnées. Utilisable par les autres modules. */
@Suppress("UNUSED_PARAMETER") // lat/lon gardés pour ne pas casser les appels existants
fun defaultSpotName(n: Int, lat: Double, lon: Double): String = "Spot $n" // sans coordonnées (elles peuvent être masquées)

/** Photo du spot (la plus récente) ou illustration de forêt, avec le bouton « Photo du spot ». */
@Composable
private fun SpotHero(spotId: String, onTakePhoto: () -> Unit) {
    val photo by remember(spotId) { AppGraph.db.photoDao().latestOf(spotId) }.collectAsState(initial = null)
    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp))) {
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

/** Petit « ✓ Enregistré » discret, visible 4 s après chaque enregistrement. */
@Composable
private fun SavedIndicator(savedAt: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(savedAt) {
        if (savedAt > 0) { visible = true; delay(INFO_MS); visible = false }
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
