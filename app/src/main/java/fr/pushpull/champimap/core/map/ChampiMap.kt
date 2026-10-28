package fr.pushpull.champimap.core.map

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle
import fr.pushpull.champimap.feature.sortie.OfflineMapStyle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/*
 * Carte hors ligne partagée par tous les écrans (sortie, carte globale, carte aux trésors).
 *
 * - MapLibre, sans aucun accès réseau : style construit localement (OfflineMapStyle) à partir
 *   des fichiers PMTiles déposés par Champimap Connect dans filesDir/maps/.
 * - Sans fichier de carte : fond uni + grille légère (les spots, la trace et la flèche restent utilisables).
 * - Cycle de vie de la MapView relié à celui de l'écran (onStart/onResume/onPause/onStop/onDestroy).
 *
 * `onMapReady` est appelé UNE fois, quand le style est chargé : `map.style` est alors utilisable
 * pour ajouter des sources et des couches (GeoJSON).
 */
@Composable
fun ChampiMapView(
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {},
) {
    // Captures d'écran automatiques (tests sur la JVM, sans GPU ni bibliothèque native) :
    // zone neutre à la place de la carte. Jamais le cas dans l'appli (AppGraph.testMode faux).
    if (AppGraph.testMode) {
        MapPlaceholder(modifier)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = LocalChampiStyle.current.dark
    val hasMaps = remember { OfflineMapStyle.mapFiles(context).isNotEmpty() }
    val currentOnReady by rememberUpdatedState(onMapReady)

    // Création unique de la MapView (MapLibre doit être initialisé AVANT).
    val mapView = remember {
        initMapLibre(context)
        MapView(context).apply { onCreate(null) }
    }

    // Cycle de vie : suit l'écran, puis libère tout quand la carte quitte l'écran.
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Chargement du style puis remise de la carte à l'écran appelant.
    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            configureMap(map)
            val json = OfflineMapStyle.build(context, dark)
            map.setStyle(Style.Builder().fromJson(json)) { style ->
                if (!hasMaps) GridOverlay.install(map, style, OfflineMapStyle.gridColor(dark))
                runCatching { currentOnReady(map) }
                    .onFailure { ErrorLogger.log("Carte", "Erreur à l'affichage de la carte", it) }
            }
        }
        onDispose { }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Attribution des données OpenStreetMap (obligatoire quand une carte est affichée).
        if (hasMaps) {
            Text(
                "© OpenStreetMap",
                fontSize = 9.sp,
                color = Color(0xFF3A3A3A),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0xB3FFFFFF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Zone neutre affichée à la place de la carte pendant les captures d'écran automatiques. */
@Composable
private fun MapPlaceholder(modifier: Modifier) {
    val dark = LocalChampiStyle.current.dark
    Box(modifier.background(if (dark) Color(0xFF2A322D) else Color(0xFFE4E8DF)), contentAlignment = Alignment.Center) {
        Text(
            "Carte (non rendue dans les captures)",
            fontSize = 12.sp,
            color = if (dark) Color(0xFF8C968F) else Color(0xFF7A8279),
        )
    }
}

/**
 * Initialise MapLibre sans réseau. L'appli n'a pas la permission ACCESS_NETWORK_STATE :
 * on déclare donc « pas de connexion » pour que MapLibre n'interroge jamais l'état du réseau.
 */
private fun initMapLibre(context: Context) {
    MapLibre.getInstance(context.applicationContext)
    runCatching { MapLibre.setConnected(false) }
}

/** Réglages communs : pas de logo ni de bouton d'attribution (on affiche « © OpenStreetMap »). */
private fun configureMap(map: MapLibreMap) {
    map.uiSettings.isLogoEnabled = false
    map.uiSettings.isAttributionEnabled = false
    map.uiSettings.isCompassEnabled = true
    map.setMinZoomPreference(4.0)
    map.setMaxZoomPreference(19.0)
    // Position de départ : la France entière (chaque écran recadre ensuite).
    map.cameraPosition = CameraPosition.Builder().target(LatLng(46.5, 2.4)).zoom(4.8).build()
}

/**
 * Grille légère affichée quand aucune carte n'est installée : aide à juger les distances.
 * Recalculée quand la carte s'arrête de bouger, avec un pas adapté au zoom.
 */
private object GridOverlay {
    private const val SOURCE = "champi-grille"
    private const val LAYER = "champi-grille-lignes"

    fun install(map: MapLibreMap, style: Style, color: String) {
        style.addSource(GeoJsonSource(SOURCE))
        // Sous toutes les autres couches ajoutées ensuite (spots, trace…).
        style.addLayer(
            LineLayer(LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(1f),
            ),
        )
        update(map, style)
        map.addOnCameraIdleListener { map.style?.let { update(map, it) } }
    }

    /** Lignes de la grille couvrant la zone visible (au plus ~40 lignes par direction). */
    private fun update(map: MapLibreMap, style: Style) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE) ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        val zoom = map.cameraPosition.zoom
        // Pas en degrés : environ un quart de la largeur d'une tuile au zoom courant.
        val step = (360.0 / 2.0.pow(zoom)) / 4.0
        val south = floor(bounds.latitudeSouth / step) * step
        val north = ceil(bounds.latitudeNorth / step) * step
        val west = floor(bounds.longitudeWest / step) * step
        val east = ceil(bounds.longitudeEast / step) * step
        val features = mutableListOf<Feature>()
        var lat = south
        var n = 0
        while (lat <= north && n < 60) {
            features += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(west, lat), Point.fromLngLat(east, lat))))
            lat += step; n++
        }
        var lon = west
        n = 0
        while (lon <= east && n < 60) {
            features += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(lon, south), Point.fromLngLat(lon, north))))
            lon += step; n++
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}
