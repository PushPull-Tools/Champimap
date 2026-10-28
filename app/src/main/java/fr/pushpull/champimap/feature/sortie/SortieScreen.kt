package fr.pushpull.champimap.feature.sortie

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.SpotMarker
import fr.pushpull.champimap.core.map.ChampiMapView
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.CircularReveal
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.core.ui.RevealMode
import fr.pushpull.champimap.feature.bonus.SortieTransitions
import fr.pushpull.champimap.feature.bonus.StartTransitionOverlay
import fr.pushpull.champimap.feature.bonus.animationsEnabled
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/*
 * Écran « Sortie en cours » : carte (trace, position, départ, spots), barre haute (durée, spots,
 * distance, Terminer), pastilles (groupe, GPS, batterie), bandeaux (chasse, nuit, batterie),
 * panneau bas (flèche vers la voiture, nuit en forêt, Marquer un spot, Spot existant, Je suis perdu).
 * Transitions : page de démarrage par-dessus la carte (nouvelle sortie), fermeture circulaire
 * vers le récap animé à la fin de la sortie.
 */

/** Durée de la fermeture circulaire vers le récap. */
private const val CLOSE_MS = 650

@Composable
fun SortieScreen(nav: NavController, sortieId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppGraph.db

    // --- Données observées -------------------------------------------------------------------
    val sortie by remember(sortieId) { db.sortieDao().flow(sortieId) }.collectAsState(initial = null)
    val track by remember(sortieId) { db.sortieDao().track(sortieId) }.collectAsState(initial = emptyList())
    val spots by remember { db.sortieDao().spotMarkers() }.collectAsState(initial = emptyList())
    val spotCount by remember(sortieId) { db.sortieDao().spotCount(sortieId) }.collectAsState(initial = 0)
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    val groupId = sortie?.groupId
    val companions by remember(groupId) {
        if (groupId != null) db.personDao().companionsOfGroup(groupId) else flowOf(emptyList<Person>())
    }.collectAsState(initial = emptyList())
    val fix by SortieTracker.lastFix.collectAsState()
    val paused by SortieTracker.gpsPaused.collectAsState()
    val economy by SortieTracker.economy.collectAsState()
    val battery by SortieTracker.batteryPct.collectAsState()
    val syncStatus by SortieTracker.groupSyncStatus.collectAsState()
    val running by SortieTracker.running.collectAsState()
    val pendingMark by SortieIntents.pendingMarkSpot.collectAsState()
    val now by rememberNow(15_000)

    // L'écran n'est actif (boussole, réveil du GPS) que lorsqu'il est réellement visible.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // --- État local ------------------------------------------------------------------------------
    var layers by remember { mutableStateOf<ChampiMapLayers?>(null) }
    var follow by remember { mutableStateOf(true) }
    var showMark by remember { mutableStateOf(false) }
    var showExisting by remember { mutableStateOf(false) }
    var longPressAt by remember { mutableStateOf<LatLng?>(null) }
    var target by remember { mutableStateOf<SpotMarker?>(null) }
    var huntHidden by remember { mutableStateOf(false) }
    var nightHidden by remember { mutableStateOf(false) }
    var batteryHidden by remember { mutableStateOf(false) }
    // Fermeture circulaire de fin de sortie : 0 = carte visible, 1 = écran couvert.
    val anim = animationsEnabled()
    val closing = remember { Animatable(0f) }

    SortieIntentHandler(nav, currentSortieId = sortieId)

    // --- Effets ---------------------------------------------------------------------------------
    // Le suivi GPS doit tourner tant que la sortie est en cours (relance après un redémarrage, etc.).
    LaunchedEffect(sortie?.id, sortie?.endedAt, running) {
        val s = sortie ?: return@LaunchedEffect
        if (s.endedAt == null && running != s.id) SortieActions.startTracking(context, s.id)
    }
    // Sortie terminée (ex. depuis la notification) → fermeture en cercle, puis récap animé.
    LaunchedEffect(sortie?.endedAt) {
        if (sortie?.endedAt == null) return@LaunchedEffect
        SortieTransitions.end.request(sortieId)
        // Écran caché (fin depuis la notification) : pas d'animation, on passe directement au récap.
        if (anim && visible) closing.animateTo(1f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
        nav.navigate(Routes.fin(sortieId)) { popUpTo(Routes.SORTIE) { inclusive = true } }
    }
    // Écran visible : on réveille le GPS s'il était en pause.
    LaunchedEffect(visible) { if (visible) SortieTracker.wakeGps() }
    // « Marquer un spot » demandé depuis la notification.
    LaunchedEffect(pendingMark) {
        if (pendingMark == sortieId) {
            SortieIntents.pendingMarkSpot.value = null
            showMark = true
        }
    }
    // Données → couches de la carte.
    LaunchedEffect(layers, spots, settings?.easterUnlocked, sortie?.markerStyle) {
        // TODO(bonus) : si easterUnlocked et markerStyle = MUSHROOMS ou TREES, passer la clé de picto
        //  (voir MapIcons) ; pour l'instant les épingles restent colorées.
        layers?.setSpots(spots)
    }
    LaunchedEffect(layers, track) { layers?.setTrack(track) }
    LaunchedEffect(layers, sortie?.startLat, sortie?.startLon, groupId) {
        val s = sortie ?: return@LaunchedEffect
        layers?.setDepart(s.startLat, s.startLon, s.departType, groupId != null)
    }
    LaunchedEffect(layers, fix, follow) {
        val l = layers ?: return@LaunchedEffect
        l.setPosition(fix)
        val f = fix ?: return@LaunchedEffect
        if (follow) l.centerOn(f.lat, f.lon)
    }

    // --- Valeurs dérivées -----------------------------------------------------------------------
    val start = sortie?.let { s -> if (s.startLat != null && s.startLon != null) s.startLat to s.startLon else null }
    val distance = remember(track) { Geo.trackLengthM(track) }
    val nightMin = fix?.let { minutesBeforeForestDark(it.lat, it.lon, now) }

    /** Ouvre la fiche d'un spot pendant la sortie. */
    fun openSpot(id: String) = nav.navigate(Routes.spot(id, sortieId))

    ChampiScreen {
        Box(Modifier.fillMaxSize()) {
            ChampiMapView(Modifier.fillMaxSize(), onMapReady = { map: MapLibreMap ->
                val l = ChampiMapLayers(context, map)
                l.onSpotClick { openSpot(it) }
                l.onLongPress { longPressAt = it }
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
                }
                // Premier cadrage : position actuelle, sinon départ.
                val f = SortieTracker.lastFix.value
                when {
                    f != null -> l.centerOn(f.lat, f.lon, zoom = 16.0, animate = false)
                    start != null -> l.centerOn(start.first, start.second, zoom = 16.0, animate = false)
                }
                layers = l
            })

            // Haut : barre d'état, pastilles, bandeaux.
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            ) {
                SortieTopBar(
                    startedAt = sortie?.startedAt ?: now,
                    now = now,
                    spotCount = spotCount,
                    distanceM = distance,
                    onEnd = {
                        scope.launch {
                            runCatching { SortieActions.endSortie(context, sortieId) }
                                .onFailure { ErrorLogger.log("Sortie", "Fin de sortie impossible", it) }
                        }
                    },
                )
                SortiePills(
                    companions = companions.map { it.name },
                    shared = groupId != null,
                    syncStatus = syncStatus,
                    fix = fix,
                    paused = paused,
                    battery = battery,
                )
                SortieBanners(
                    hunting = isHuntingSeason() && !huntHidden,
                    onHideHunting = { huntHidden = true },
                    nightMin = nightMin.takeIf { !nightHidden },
                    distanceToCar = if (fix != null && start != null) Geo.distanceM(fix!!.lat, fix!!.lon, start.first, start.second) else null,
                    onHideNight = { nightHidden = true },
                    economy = economy && !batteryHidden,
                    battery = battery,
                    onHideBattery = { batteryHidden = true },
                )
            }

            // Bas : flèche, nuit, boutons.
            SortieBottomPanel(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                fix = fix,
                start = start,
                departIsCar = sortie?.departType == DepartType.CAR,
                target = target,
                compassActive = visible,
                nightMin = nightMin,
                follow = follow,
                onRecenter = { follow = true },
                onMark = { showMark = true },
                onExisting = { showExisting = true },
                onLost = { nav.navigate(Routes.perdu(sortieId)) },
                onCancelTarget = { target = null },
                onArrived = { spot ->
                    scope.launch {
                        SortieActions.addVisit(spot.id, sortieId)
                        target = null
                        openSpot(spot.id)
                    }
                },
            )
        }
    }

    // Page de transition de démarrage par-dessus tout l'écran (seulement au lancement d'une nouvelle sortie).
    StartTransitionOverlay(sortieId)

    // Fermeture circulaire vers le récap (le trou se referme sur le centre).
    if (closing.value > 0f) {
        CircularReveal(progress = { 1f - closing.value }, mode = RevealMode.HOLE)
    }

    // --- Fenêtres --------------------------------------------------------------------------------
    if (showMark) {
        PrecisePositionDialog(
            onValidate = { pos ->
                showMark = false
                scope.launch {
                    try {
                        val id = SortieActions.createSpot(pos.lat, pos.lon, pos.accuracyM, sortieId, PositionSource.GPS_PRECIS, placedManually = false)
                        openSpot(id)
                    } catch (e: Exception) {
                        ErrorLogger.log("Sortie", "Création du spot impossible", e)
                    }
                }
            },
            onDismiss = { showMark = false },
        )
    }

    if (showExisting) {
        ExistingSpotDialog(
            spots = spots,
            fix = fix,
            onDismiss = { showExisting = false },
            onGuide = { spot -> showExisting = false; target = spot; follow = true },
            onHere = { spot ->
                showExisting = false
                scope.launch {
                    SortieActions.addVisit(spot.id, sortieId)
                    openSpot(spot.id)
                }
            },
        )
    }

    longPressAt?.let { at ->
        ConfirmDialog(
            title = "Créer un spot ici ?",
            text = "Le spot sera placé à la main à cet endroit de la carte. Tu pourras ajuster sa position plus tard.",
            confirmLabel = "Créer le spot",
            onConfirm = {
                longPressAt = null
                scope.launch {
                    try {
                        val id = SortieActions.createSpot(at.latitude, at.longitude, null, sortieId, PositionSource.CARTE, placedManually = true)
                        openSpot(id)
                    } catch (e: Exception) {
                        ErrorLogger.log("Sortie", "Création du spot impossible", e)
                    }
                }
            },
            onDismiss = { longPressAt = null },
        )
    }
}

/** Fond semi-opaque des panneaux posés sur la carte. */
@Composable
internal fun Modifier.panelBackground(): Modifier =
    this.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
