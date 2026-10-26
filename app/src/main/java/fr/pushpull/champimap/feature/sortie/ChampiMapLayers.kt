package fr.pushpull.champimap.feature.sortie

import android.content.Context
import android.graphics.RectF
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.db.SpotMarker
import fr.pushpull.champimap.core.db.TrackPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.pow

/*
 * Repères dessinés par-dessus la carte, en couches GeoJSON (pas d'API Marker dépréciée) :
 * trace GPS en pointillés, cercle de précision + point de position, départ, épingles des spots.
 * Réutilisable par d'autres écrans (carte globale, carte aux trésors…).
 *
 * À créer dans `onMapReady` de ChampiMapView (le style est alors chargé), puis appeler les `set…`
 * depuis le fil principal (LaunchedEffect) à chaque changement des données.
 */
class ChampiMapLayers(private val context: Context, private val map: MapLibreMap) {

    private companion object {
        const val SRC_TRACK = "champi-trace"
        const val SRC_POS = "champi-position"
        const val SRC_DEPART = "champi-depart"
        const val SRC_SPOTS = "champi-spots"
        const val LYR_TRACK = "champi-trace-ligne"
        const val LYR_ACC = "champi-position-precision"
        const val LYR_POS = "champi-position-point"
        const val LYR_DEPART = "champi-depart-icone"
        const val LYR_SPOTS = "champi-spots-epingles"
        const val PROP_ID = "id"
        const val PROP_ICON = "icone"
    }

    /** Images déjà ajoutées au style (évite de redessiner les Bitmap). */
    private val images = mutableSetOf<String>()

    init {
        map.getStyle { style -> setup(style) }
    }

    /** Crée les sources vides et les couches, dans l'ordre d'empilement. */
    private fun setup(style: Style) {
        if (style.getSource(SRC_TRACK) != null) return
        style.addSource(GeoJsonSource(SRC_TRACK))
        style.addSource(GeoJsonSource(SRC_POS))
        style.addSource(GeoJsonSource(SRC_DEPART))
        style.addSource(GeoJsonSource(SRC_SPOTS))

        style.addLayer(
            LineLayer(LYR_TRACK, SRC_TRACK).withProperties(
                PropertyFactory.lineColor("#E08A2E"),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineDasharray(arrayOf(1.2f, 1.2f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(LYR_ACC, SRC_POS).withProperties(
                PropertyFactory.circleColor("#3B82C4"),
                PropertyFactory.circleOpacity(0.15f),
                PropertyFactory.circleStrokeColor("#3B82C4"),
                PropertyFactory.circleStrokeOpacity(0.4f),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        style.addLayer(
            SymbolLayer(LYR_DEPART, SRC_DEPART).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            SymbolLayer(LYR_SPOTS, SRC_SPOTS).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            CircleLayer(LYR_POS, SRC_POS).withProperties(
                PropertyFactory.circleColor("#3B82C4"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
    }

    /** Ajoute une image au style si elle n'y est pas encore. */
    private fun ensureImage(style: Style, name: String, make: () -> android.graphics.Bitmap) {
        if (name in images) return
        style.addImage(name, make())
        images += name
    }

    /** Épingles des spots, colorées selon l'espèce de la dernière récolte. */
    fun setSpots(spots: List<SpotMarker>, pictoKey: String? = null) {
        val style = map.style ?: return
        val features = spots.map { s ->
            val icon = MapIcons.pinName(s.colorHex, pictoKey)
            ensureImage(style, icon) { MapIcons.pin(context, s.colorHex, pictoKey) }
            Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                addStringProperty(PROP_ID, s.id)
                addStringProperty(PROP_ICON, icon)
            }
        }
        style.getSourceAs<GeoJsonSource>(SRC_SPOTS)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Trace GPS de la sortie (points imprécis ignorés). */
    fun setTrack(points: List<TrackPoint>) {
        val style = map.style ?: return
        val pts = points.filter { it.accuracyM <= 40f }.map { Point.fromLngLat(it.lon, it.lat) }
        val src = style.getSourceAs<GeoJsonSource>(SRC_TRACK) ?: return
        if (pts.size < 2) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    /** Position actuelle + cercle de précision (rayon réel en mètres, recalculé selon le zoom). */
    fun setPosition(fix: GpsFix?) {
        val style = map.style ?: return
        val src = style.getSourceAs<GeoJsonSource>(SRC_POS) ?: return
        if (fix == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)))
        // Mètres par pixel au zoom z : 156543 × cos(lat) / 2^z  →  rayon en pixels interpolé (base 2).
        val mpp0 = 156_543.03 * cos(Math.toRadians(fix.lat))
        val r0 = (fix.accuracyM / mpp0).toFloat()
        val r22 = (fix.accuracyM / mpp0 * 2.0.pow(22)).toFloat()
        style.getLayer(LYR_ACC)?.setProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2f),
                    Expression.zoom(),
                    Expression.stop(0, r0),
                    Expression.stop(22, r22),
                ),
            ),
        )
    }

    /** Repère de départ (voiture ou bottes), avec badge chaîne si sortie partagée. */
    fun setDepart(lat: Double?, lon: Double?, type: DepartType, shared: Boolean) {
        val style = map.style ?: return
        val src = style.getSourceAs<GeoJsonSource>(SRC_DEPART) ?: return
        if (lat == null || lon == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val icon = MapIcons.departName(type, shared)
        ensureImage(style, icon) { MapIcons.depart(context, type, shared) }
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty(PROP_ICON, icon) })
    }

    /** Appui sur une épingle → identifiant du spot. Les appuis ailleurs sont laissés à la carte. */
    fun onSpotClick(handler: (String) -> Unit) {
        map.addOnMapClickListener { latLng ->
            val p = map.projection.toScreenLocation(latLng)
            val d = context.resources.displayMetrics.density
            // Zone d'appui généreuse (gants) : l'épingle est au-dessus de son point d'ancrage.
            val box = RectF(p.x - 22 * d, p.y - 48 * d, p.x + 22 * d, p.y + 10 * d)
            val id = map.queryRenderedFeatures(box, LYR_SPOTS).firstOrNull()?.getStringProperty(PROP_ID)
            if (id != null) handler(id)
            id != null
        }
    }

    /** Appui long sur la carte (créer un spot ici). */
    fun onLongPress(handler: (LatLng) -> Unit) {
        map.addOnMapLongClickListener { latLng -> handler(latLng); true }
    }

    /** Centre la carte sur un point. */
    fun centerOn(lat: Double, lon: Double, zoom: Double? = null, animate: Boolean = true) {
        val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom)
        else CameraUpdateFactory.newLatLng(LatLng(lat, lon))
        if (animate) map.easeCamera(update, 600) else map.moveCamera(update)
    }

    /** Cadre la carte sur un ensemble de points (un seul point → zoom 15). */
    fun fitTo(points: List<LatLng>, paddingDp: Int = 56) {
        if (points.isEmpty()) return
        try {
            if (points.size == 1 || points.distinct().size == 1) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 15.0))
            } else {
                val bounds = LatLngBounds.Builder().includes(points).build()
                val pad = (paddingDp * context.resources.displayMetrics.density).toInt()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad))
                if (map.cameraPosition.zoom > 16.5) map.moveCamera(CameraUpdateFactory.zoomTo(16.5))
            }
        } catch (e: Exception) {
            ErrorLogger.log("Carte", "Cadrage de la carte impossible", e)
        }
    }

    /** Cadre la carte sur une emprise (départements). */
    fun fitBounds(bounds: LatLngBounds, paddingDp: Int = 24) {
        val pad = (paddingDp * context.resources.displayMetrics.density).toInt()
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad)) }
    }
}
