package fr.pushpull.champimap.feature.sortie

import android.content.Context
import fr.pushpull.champimap.core.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Style de carte 100 % hors ligne, construit à la volée.
 *
 * Les cartes vectorielles (une par département) sont déposées par Champimap Connect dans
 * `filesDir/maps/<code>.pmtiles` (extraits des builds Protomaps, schéma « basemap » :
 * couches earth, water, landuse, landcover, roads, buildings…).
 * Aucune police (glyphs) ni image (sprite) distante : pas de texte sur la carte.
 * Sans aucun fichier : fond uni (une grille légère est ajoutée par ChampiMapView).
 */
object OfflineMapStyle {

    /** Dossier des cartes déposées par Champimap Connect. */
    fun mapsDir(context: Context): File = File(context.filesDir, "maps")

    /** Fichiers de carte présents (triés par nom, donc par code de département). */
    fun mapFiles(context: Context): List<File> =
        mapsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") && f.length() > 127 }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Palette de la charte, déclinée en clair et en sombre. */
    private class Palette(dark: Boolean) {
        val land = if (dark) "#1B211D" else "#EEF0E6"
        val forest = if (dark) "#22392A" else "#CFE3C8"
        val grass = if (dark) "#25302A" else "#E2EBD3"
        val farm = if (dark) "#23281F" else "#F1EEDC"
        val water = if (dark) "#1E3442" else "#B9D7E8"
        val building = if (dark) "#2C332E" else "#DAD8D0"
        val minorRoad = if (dark) "#4A544D" else "#FFFFFF"
        val minorCasing = if (dark) "#2A302C" else "#D4D2C8"
        val majorRoad = if (dark) "#8A6A3E" else "#F6D7A7"
        val path = if (dark) "#B48A5A" else "#9A6A42"
        val grid = if (dark) "#2C352F" else "#DDDBD2"
    }

    /** Couleur des lignes de la grille (utilisée quand il n'y a aucune carte). */
    fun gridColor(dark: Boolean): String = Palette(dark).grid

    /** Construit le style JSON complet (sources PMTiles locales + couches simples). */
    fun build(context: Context, dark: Boolean): String {
        val p = Palette(dark)
        val sources = JSONObject()
        val layers = JSONArray()
        layers.put(JSONObject().put("id", "fond").put("type", "background").put("paint", JSONObject().put("background-color", p.land)))

        mapFiles(context).forEachIndexed { i, file ->
            val src = "carte$i"
            sources.put(src, JSONObject().put("type", "vector").put("url", "pmtiles://file://${file.absolutePath}"))
            addLayers(layers, src, p)
        }

        return JSONObject()
            .put("version", 8)
            .put("name", "Champimap hors ligne")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /** Ajoute les couches d'une source : végétation, eau, bâtiments, routes, chemins. */
    private fun addLayers(layers: JSONArray, src: String, p: Palette) {
        // « geometry-type » peut valoir Polygon ou MultiPolygon selon la version du moteur : on accepte les deux.
        val polygon = arr("in", arr("geometry-type"), arr("literal", JSONArray(listOf("Polygon", "MultiPolygon"))))
        val line = arr("in", arr("geometry-type"), arr("literal", JSONArray(listOf("LineString", "MultiLineString"))))
        fun kindIn(vararg kinds: String) = arr("in", arr("get", "kind"), arr("literal", JSONArray(kinds.toList())))

        // Végétation (landcover = grandes zones, landuse = parcelles détaillées).
        layers.put(fill("$src-landcover-foret", src, "landcover", p.forest, kindIn("forest", "wood")))
        layers.put(fill("$src-landcover-prairie", src, "landcover", p.grass, kindIn("grassland", "scrub", "barren")))
        layers.put(fill("$src-landcover-champs", src, "landcover", p.farm, kindIn("farmland")))
        layers.put(fill("$src-landuse-champs", src, "landuse", p.farm, kindIn("farmland", "orchard", "vineyard", "allotments")))
        layers.put(fill("$src-landuse-prairie", src, "landuse", p.grass, kindIn("grass", "meadow", "scrub", "heath", "park", "village_green", "recreation_ground")))
        layers.put(fill("$src-landuse-foret", src, "landuse", p.forest, kindIn("forest", "wood", "nature_reserve", "national_park", "protected_area")))

        // Eau : surfaces puis cours d'eau.
        layers.put(fill("$src-eau", src, "water", p.water, polygon))
        layers.put(
            lineLayer("$src-rivieres", src, "water", p.water, line, width(10, 0.5, 16, 2.5)),
        )

        // Bâtiments (à partir du zoom 13).
        layers.put(fill("$src-batiments", src, "buildings", p.building, null).put("minzoom", 13))

        // Routes : bordure + intérieur pour les petites routes, orange pâle pour les grands axes.
        val minor = kindIn("minor_road", "other")
        val major = kindIn("major_road", "highway", "medium_road")
        layers.put(lineLayer("$src-routes-bord", src, "roads", p.minorCasing, minor, width(12, 1.5, 17, 9.0)).put("minzoom", 12))
        layers.put(lineLayer("$src-routes", src, "roads", p.minorRoad, minor, width(12, 0.8, 17, 7.0)).put("minzoom", 11))
        layers.put(lineLayer("$src-axes", src, "roads", p.majorRoad, major, width(8, 0.8, 17, 10.0)))

        // Chemins et pistes forestières : pointillés marron (essentiels en forêt).
        val path = arr("any", kindIn("path"), arr("==", arr("get", "kind_detail"), "track"))
        layers.put(
            lineLayer("$src-chemins", src, "roads", p.path, path, width(12, 0.8, 17, 2.5))
                .put("minzoom", 12)
                .also { it.getJSONObject("paint").put("line-dasharray", JSONArray(listOf(2, 1.5))) },
        )
    }

    /** Couche de surface. */
    private fun fill(id: String, src: String, sourceLayer: String, color: String, filter: JSONArray?): JSONObject {
        val o = JSONObject().put("id", id).put("type", "fill").put("source", src).put("source-layer", sourceLayer)
            .put("paint", JSONObject().put("fill-color", color))
        if (filter != null) o.put("filter", filter)
        return o
    }

    /** Couche de lignes. */
    private fun lineLayer(id: String, src: String, sourceLayer: String, color: String, filter: JSONArray, width: JSONArray): JSONObject =
        JSONObject().put("id", id).put("type", "line").put("source", src).put("source-layer", sourceLayer)
            .put("filter", filter)
            .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
            .put("paint", JSONObject().put("line-color", color).put("line-width", width))

    /** Épaisseur qui grossit avec le zoom. */
    private fun width(z1: Int, w1: Double, z2: Int, w2: Double): JSONArray =
        arr("interpolate", arr("linear"), arr("zoom"), z1, w1, z2, w2)

    /** Petit raccourci pour écrire les expressions de style. */
    private fun arr(vararg items: Any): JSONArray = JSONArray().apply { items.forEach { put(it) } }

    /**
     * Emprise d'un fichier PMTiles (v3), lue dans son en-tête de 127 octets, sans rien décompresser.
     * Sert à centrer la carte sur les départements cochés quand il n'y a pas encore de spot.
     */
    fun bounds(file: File): LatLngBounds? {
        return try {
            val header = ByteArray(127)
            RandomAccessFile(file, "r").use { raf -> raf.readFully(header) }
            if (String(header, 0, 7, Charsets.US_ASCII) != "PMTiles") return null
            // Emprise en degrés × 10 000 000, entiers 32 bits petit-boutistes (octets 102 à 117).
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val minLon = b.getInt(102) / 1e7
            val minLat = b.getInt(106) / 1e7
            val maxLon = b.getInt(110) / 1e7
            val maxLat = b.getInt(114) / 1e7
            if (maxLat <= minLat || maxLon <= minLon) null
            else LatLngBounds.Builder().include(LatLng(minLat, minLon)).include(LatLng(maxLat, maxLon)).build()
        } catch (e: Exception) {
            ErrorLogger.log("Carte", "En-tête de carte illisible", e)
            null
        }
    }

    /** Emprise réunie des cartes des départements donnés (ou de toutes les cartes si la liste est vide). */
    fun boundsOf(context: Context, departements: List<String>): LatLngBounds? {
        val files = mapFiles(context).filter { departements.isEmpty() || it.nameWithoutExtension in departements }
            .ifEmpty { mapFiles(context) }
        val all = files.mapNotNull { bounds(it) }
        if (all.isEmpty()) return null
        val builder = LatLngBounds.Builder()
        all.forEach { builder.include(it.northEast).include(it.southWest) }
        return builder.build()
    }
}
