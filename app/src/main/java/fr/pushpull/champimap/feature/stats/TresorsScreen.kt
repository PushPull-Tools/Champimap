package fr.pushpull.champimap.feature.stats

import fr.pushpull.champimap.core.util.pluriel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.HarvestRow
import fr.pushpull.champimap.core.map.ChampiMapView
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/*
 * « Carte aux trésors » : carte de chaleur des spots productifs.
 * Chaque spot = un point pondéré par le poids total récolté (toutes saisons).
 * Couche MapLibre HeatmapLayer ajoutée au-dessus de la carte partagée (ChampiMapView).
 * Sous la carte : classement des spots les plus productifs.
 */

/** Total récolté sur un spot (pour la carte et le classement). */
private data class SpotTotal(val spotId: String, val name: String, val lat: Double, val lon: Double, val grams: Long, val visits: Int)

private const val SOURCE_ID = "stats-tresors-source"
private const val LAYER_ID = "stats-tresors-heatmap"

@Composable
fun TresorsContent(nav: NavController) {
    val data = rememberStatsData()
    val totals = remember(data.harvests) { spotTotals(data.harvests) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var framed by remember { mutableStateOf(false) }

    // À chaque changement de données (ou carte prête) : mise à jour de la couche de chaleur.
    LaunchedEffect(map, totals) {
        val m = map ?: return@LaunchedEffect
        m.getStyle { style ->
            try {
                applyHeatmap(style, totals)
                if (!framed && totals.isNotEmpty()) {
                    frameCamera(m, totals)
                    framed = true
                }
            } catch (e: Exception) {
                ErrorLogger.log("Carte aux trésors", "Couche de chaleur impossible", e)
            }
        }
    }

    ChampiScreen(title = "Carte aux trésors", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            ChampiMapView(
                modifier = Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(18.dp)),
                onMapReady = { map = it },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                champiText("Plus c'est chaud, plus le coin a donné (poids total récolté)."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Spots les plus productifs")
            ChampiCard(Modifier.fillMaxWidth()) {
                if (totals.isEmpty()) {
                    Text(champiText("Aucune récolte pesée pour l'instant."), style = MaterialTheme.typography.bodyMedium)
                } else {
                    totals.take(10).forEachIndexed { i, t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.navigate(Routes.spot(t.spotId)) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RankCircle(i + 1, if (i == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, size = 30.dp)
                            Column(Modifier.weight(1f)) {
                                Text(champiText(t.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    champiText(pluriel(t.visits, "récolte")),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(StatsFormat.kg(t.grams), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** Poids total par spot localisé, du plus productif au moins productif. */
private fun spotTotals(harvests: List<HarvestRow>): List<SpotTotal> =
    harvests
        .filter { it.lat != null && it.lon != null && (it.weightG ?: 0) > 0 }
        .groupBy { it.spotId }
        .map { (id, list) ->
            val h = list.first()
            SpotTotal(id, h.spotName ?: "Spot", h.lat!!, h.lon!!, list.sumOf { (it.weightG ?: 0).toLong() }, list.map { it.recolteId }.distinct().size)
        }
        .sortedByDescending { it.grams }

/** Ajoute (ou met à jour) la source GeoJSON et la couche de chaleur dans le style de la carte. */
private fun applyHeatmap(style: Style, totals: List<SpotTotal>) {
    val features = totals.map { t ->
        Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat)).also { it.addNumberProperty("weight", t.grams) }
    }
    val collection = FeatureCollection.fromFeatures(features)
    val existing = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ID, collection))
    }
    if (style.getLayer(LAYER_ID) == null) {
        val max = (totals.maxOfOrNull { it.grams } ?: 1L).coerceAtLeast(1L).toFloat()
        style.addLayer(buildLayer(max))
    } else {
        // Le maximum a pu changer : on recalcule la pondération.
        val max = (totals.maxOfOrNull { it.grams } ?: 1L).coerceAtLeast(1L).toFloat()
        (style.getLayer(LAYER_ID) as? HeatmapLayer)?.setProperties(PropertyFactory.heatmapWeight(weightExpression(max)))
    }
}

/** Pondération : 0 → 0, poids maximal → 1. */
private fun weightExpression(max: Float): Expression =
    Expression.interpolate(
        Expression.linear(), Expression.get("weight"),
        Expression.stop(0, 0),
        Expression.stop(max, 1),
    )

/** Couche de chaleur : du vert mousse transparent au orange girolle puis au rouge. */
private fun buildLayer(max: Float): HeatmapLayer =
    HeatmapLayer(LAYER_ID, SOURCE_ID).withProperties(
        PropertyFactory.heatmapWeight(weightExpression(max)),
        PropertyFactory.heatmapIntensity(
            Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 1), Expression.stop(16, 3)),
        ),
        PropertyFactory.heatmapRadius(
            Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 12), Expression.stop(16, 40)),
        ),
        PropertyFactory.heatmapColor(
            Expression.interpolate(
                Expression.linear(), Expression.heatmapDensity(),
                Expression.stop(0, Expression.rgba(47, 93, 70, 0)),
                Expression.stop(0.2, Expression.rgba(127, 184, 154, 0.6)),
                Expression.stop(0.5, Expression.rgba(240, 162, 75, 0.8)),
                Expression.stop(0.8, Expression.rgba(224, 138, 46, 0.9)),
                Expression.stop(1, Expression.rgba(179, 38, 30, 0.95)),
            ),
        ),
        PropertyFactory.heatmapOpacity(0.85f),
    )

/** Cadre la carte sur l'ensemble des spots productifs (un seul spot → zoom fixe). */
private fun frameCamera(map: MapLibreMap, totals: List<SpotTotal>) {
    if (totals.size == 1) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(totals[0].lat, totals[0].lon), 14.0))
        return
    }
    val builder = LatLngBounds.Builder()
    totals.forEach { builder.include(LatLng(it.lat, it.lon)) }
    runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80)) }
        .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(totals[0].lat, totals[0].lon), 14.0)) }
}
