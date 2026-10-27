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
 * `filesDir/maps/<code>.pmtiles` (extraits des builds Protomaps, schéma « basemap » v4 :
 * couches earth, water, landuse, landcover, roads, buildings, places, pois…).
 *
 * Textes (noms de communes, lieux-dits, routes, chemins, cours d'eau, sommets) : les polices
 * sont EMBARQUÉES dans l'APK (assets/glyphs/<police>/<plage>.pbf, Noto Sans, licence OFL),
 * aucune police n'est jamais téléchargée. Pas d'image (sprite) : uniquement du texte avec halo.
 * Sans aucun fichier de carte : fond uni (une grille légère est ajoutée par ChampiMapView).
 */
object OfflineMapStyle {

    /** Dossier des cartes déposées par Champimap Connect. */
    fun mapsDir(context: Context): File = File(context.filesDir, "maps")

    /** Fichiers de carte présents (triés par nom, donc par code de département). */
    fun mapFiles(context: Context): List<File> =
        mapsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") && f.length() > 127 }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Polices embarquées : le nom de police (« fontstack ») est aussi le nom du dossier dans
     * assets/glyphs/. Noms SANS espace pour éviter tout souci d'encodage d'URL (%20).
     */
    private const val GLYPHS_URL = "asset://glyphs/{fontstack}/{range}.pbf"
    private const val FONT_REGULAR = "NotoSans-Regular"
    private const val FONT_MEDIUM = "NotoSans-Medium"

    /** Palette de la charte, déclinée en clair et en sombre. */
    private class Palette(dark: Boolean) {
        // Surfaces
        val land = if (dark) "#1B211D" else "#EEF0E6"
        val forest = if (dark) "#22392A" else "#CFE3C8"
        val grass = if (dark) "#25302A" else "#E2EBD3"
        val farm = if (dark) "#23281F" else "#F1EEDC"
        val water = if (dark) "#1E3442" else "#B9D7E8"
        val building = if (dark) "#2C332E" else "#DAD8D0"
        // Routes
        val minorRoad = if (dark) "#4A544D" else "#FFFFFF"
        val minorCasing = if (dark) "#2A302C" else "#D4D2C8"
        val majorRoad = if (dark) "#8A6A3E" else "#F6D7A7"
        val majorCasing = if (dark) "#3A2E1E" else "#D9B57E"
        val highway = if (dark) "#A0703A" else "#F0B26A"
        val rail = if (dark) "#6A706B" else "#A3A39C"
        // Chemins : pistes (marron) et sentiers (brun-rouge), avec un liseré pour ressortir sur la forêt
        val path = if (dark) "#C99A62" else "#8A5A32"
        val trail = if (dark) "#E39072" else "#B2452B"
        val pathHalo = if (dark) "#101511" else "#FFFFFF"
        // Textes et halos (halo = couleur du fond, pour rester lisible sur la forêt comme sur l'eau)
        val label = if (dark) "#E6E9E2" else "#2E2F2A"
        val labelHalo = if (dark) "#141915" else "#FFFFFF"
        val labelMinor = if (dark) "#BFC5BC" else "#55574F"
        val lieuDit = if (dark) "#A9C79A" else "#3F6B35"
        val roadLabel = if (dark) "#D2C3A8" else "#5B4B33"
        val trailLabel = if (dark) "#E8B08F" else "#8C3A22"
        val waterLabel = if (dark) "#8FC3E0" else "#2D6A93"
        val waterHalo = if (dark) "#122029" else "#EAF4FA"
        val grid = if (dark) "#2C352F" else "#DDDBD2"
    }

    /** Couleur des lignes de la grille (utilisée quand il n'y a aucune carte). */
    fun gridColor(dark: Boolean): String = Palette(dark).grid

    /**
     * Construit le style JSON complet (sources PMTiles locales + couches).
     * Ordre : toutes les surfaces et lignes de TOUTES les sources d'abord, puis tous les textes,
     * pour qu'une carte voisine (emprises qui se chevauchent) ne recouvre jamais les noms.
     */
    fun build(context: Context, dark: Boolean): String {
        val p = Palette(dark)
        val sources = JSONObject()
        val layers = JSONArray()
        layers.put(JSONObject().put("id", "fond").put("type", "background").put("paint", JSONObject().put("background-color", p.land)))

        val srcs = mapFiles(context).mapIndexed { i, file ->
            val src = "carte$i"
            sources.put(src, JSONObject().put("type", "vector").put("url", "pmtiles://file://${file.absolutePath}"))
            src
        }
        srcs.forEach { addLayers(layers, it, p) }
        srcs.forEach { addLabelLayers(layers, it, p) }

        return JSONObject()
            .put("version", 8)
            .put("name", "Champimap hors ligne")
            .put("glyphs", GLYPHS_URL)
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /** Filtre « kind parmi … ». */
    private fun kindIn(vararg kinds: String) = arr("in", arr("get", "kind"), arr("literal", JSONArray(kinds.toList())))

    /** Filtre « kind_detail parmi … » (absent = chaîne vide, jamais d'erreur d'expression). */
    private fun detailIn(vararg details: String) =
        arr("in", arr("coalesce", arr("get", "kind_detail"), ""), arr("literal", JSONArray(details.toList())))

    /** Ajoute les couches d'une source : végétation, eau, bâtiments, routes, voies ferrées, chemins. */
    private fun addLayers(layers: JSONArray, src: String, p: Palette) {
        // « geometry-type » peut valoir Polygon ou MultiPolygon selon la version du moteur : on accepte les deux.
        val polygon = arr("in", arr("geometry-type"), arr("literal", JSONArray(listOf("Polygon", "MultiPolygon"))))
        val line = arr("in", arr("geometry-type"), arr("literal", JSONArray(listOf("LineString", "MultiLineString"))))

        // Végétation (landcover = grandes zones, landuse = parcelles détaillées).
        layers.put(fill("$src-landcover-foret", src, "landcover", p.forest, kindIn("forest", "wood")))
        layers.put(fill("$src-landcover-prairie", src, "landcover", p.grass, kindIn("grassland", "scrub", "barren")))
        layers.put(fill("$src-landcover-champs", src, "landcover", p.farm, kindIn("farmland")))
        layers.put(fill("$src-landuse-champs", src, "landuse", p.farm, kindIn("farmland", "orchard", "vineyard", "allotments")))
        layers.put(fill("$src-landuse-prairie", src, "landuse", p.grass, kindIn("grass", "meadow", "scrub", "heath", "park", "village_green", "recreation_ground")))
        layers.put(fill("$src-landuse-foret", src, "landuse", p.forest, kindIn("forest", "wood", "nature_reserve", "national_park", "protected_area")))

        // Eau : surfaces puis cours d'eau (ruisseaux plus fins).
        layers.put(fill("$src-eau", src, "water", p.water, polygon))
        layers.put(lineLayer("$src-rivieres", src, "water", p.water, arr("all", line, kindIn("river", "canal")), width(9, 0.8, 16, 4.0)))
        layers.put(lineLayer("$src-ruisseaux", src, "water", p.water, arr("all", line, arr("!", kindIn("river", "canal"))), width(12, 0.5, 16, 2.0)))

        // Bâtiments (à partir du zoom 13).
        layers.put(fill("$src-batiments", src, "buildings", p.building, null).put("minzoom", 13))

        // Voies ferrées : trait gris fin.
        layers.put(lineLayer("$src-rail", src, "roads", p.rail, kindIn("rail"), width(11, 0.6, 17, 2.0)).put("minzoom", 11))

        // Petites routes (dont « service ») : bordure + intérieur blanc.
        val minor = kindIn("minor_road", "other")
        layers.put(lineLayer("$src-routes-bord", src, "roads", p.minorCasing, minor, width(12, 1.6, 17, 9.0)).put("minzoom", 12))
        layers.put(lineLayer("$src-routes", src, "roads", p.minorRoad, minor, width(11, 0.7, 17, 7.0)).put("minzoom", 11))

        // Routes principales (départementales, nationales) puis autoroutes, avec bordure.
        val major = kindIn("major_road", "medium_road")
        layers.put(lineLayer("$src-axes-bord", src, "roads", p.majorCasing, major, width(10, 1.4, 17, 12.0)).put("minzoom", 10))
        layers.put(lineLayer("$src-axes", src, "roads", p.majorRoad, major, width(7, 0.6, 17, 10.0)))
        val highway = kindIn("highway")
        layers.put(lineLayer("$src-autoroutes-bord", src, "roads", p.majorCasing, highway, width(9, 1.8, 17, 14.0)).put("minzoom", 9))
        layers.put(lineLayer("$src-autoroutes", src, "roads", p.highway, highway, width(5, 0.6, 17, 12.0)))

        // Chemins (kind = path) : essentiels en forêt, dessinés au-dessus des routes.
        // Pistes forestières et agricoles (highway=track) : tirets longs marron, assez épais.
        val track = arr("all", kindIn("path"), detailIn("track"))
        layers.put(lineLayer("$src-pistes-lisere", src, "roads", p.pathHalo, track, width(12, 2.4, 17, 6.5)).put("minzoom", 12)
            .also { it.getJSONObject("paint").put("line-opacity", 0.55) })
        layers.put(dashed("$src-pistes", src, p.path, track, width(12, 1.3, 17, 3.6), listOf(2.5, 1.2), 12))
        // Sentiers, chemins piétons, cavaliers, cyclables, escaliers… : tirets courts brun-rouge.
        val trail = arr("all", kindIn("path"), arr("!", detailIn("track", "sidewalk", "crossing", "corridor", "pier")))
        layers.put(lineLayer("$src-sentiers-lisere", src, "roads", p.pathHalo, trail, width(12, 2.0, 17, 5.0)).put("minzoom", 12)
            .also { it.getJSONObject("paint").put("line-opacity", 0.5) })
        layers.put(dashed("$src-sentiers", src, p.trail, trail, width(12, 1.0, 17, 2.6), listOf(1.6, 1.1), 12))
        // Trottoirs, passages, couloirs, pontons : simple trait fin, seulement de près.
        val urbanPath = arr("all", kindIn("path"), detailIn("sidewalk", "crossing", "corridor", "pier"))
        layers.put(lineLayer("$src-trottoirs", src, "roads", p.minorCasing, urbanPath, width(15, 0.6, 17, 1.5)).put("minzoom", 15))
    }

    /**
     * Couches de texte d'une source (ordre = priorité croissante : la dernière couche place
     * ses textes en premier). Le nom français est préféré s'il existe (« name:fr »).
     */
    private fun addLabelLayers(layers: JSONArray, src: String, p: Palette) {
        val hasName = arr("has", "name")
        val point = arr("==", arr("geometry-type"), "Point")
        val line = arr("in", arr("geometry-type"), arr("literal", JSONArray(listOf("LineString", "MultiLineString"))))
        // Lieux-dits et habitat isolé (place=hamlet, isolated_dwelling, farm, locality…).
        val lieuxDits = arrayOf("hamlet", "isolated_dwelling", "farm", "locality", "allotments")

        // Cours d'eau : rivières dès 12, ruisseaux dès 13 (texte qui suit la ligne).
        layers.put(lineLabel("$src-nom-rivieres", src, "water", arr("all", line, hasName, kindIn("river", "canal")),
            p.waterLabel, p.waterHalo, size(12, 11.0, 16, 13.5), 12))
        layers.put(lineLabel("$src-nom-ruisseaux", src, "water", arr("all", line, hasName, kindIn("stream")),
            p.waterLabel, p.waterHalo, size(13, 10.0, 16, 12.5), 13))
        // Lacs et étangs : point d'étiquette fourni par Protomaps.
        layers.put(pointLabel("$src-nom-lacs", src, "water", arr("all", point, hasName), FONT_REGULAR,
            p.waterLabel, p.waterHalo, size(13, 10.5, 16, 13.0), 13))

        // Noms des routes principales (dès 12) puis des petites routes et chemins (dès 14).
        layers.put(lineLabel("$src-nom-axes", src, "roads", arr("all", hasName, kindIn("highway", "major_road", "medium_road")),
            p.roadLabel, p.labelHalo, size(12, 10.5, 17, 13.5), 12))
        layers.put(lineLabel("$src-nom-chemins", src, "roads", arr("all", hasName, kindIn("minor_road", "other", "path")),
            arr("case", arr("==", arr("get", "kind"), "path"), p.trailLabel, p.roadLabel),
            p.labelHalo, size(14, 10.0, 17, 12.5), 14))

        // Repères utiles en forêt : sommets (avec altitude), points de vue, abris, croix, aires de pique-nique.
        val poiText = arr(
            "case",
            arr("all", arr("==", arr("get", "kind"), "peak"), arr("has", "elevation")),
            arr("concat", name(), "\n", arr("to-string", arr("get", "elevation")), " m"),
            name(),
        )
        layers.put(
            pointLabel("$src-nom-reperes", src, "pois",
                arr("all", hasName, kindIn("peak", "viewpoint", "shelter", "alpine_hut", "wilderness_hut", "picnic_site", "wayside_cross")),
                FONT_REGULAR, p.labelMinor, p.labelHalo, size(14, 10.0, 17, 12.0), 14)
                .also { it.getJSONObject("layout").put("text-field", poiText) },
        )

        // Quartiers, hameaux et lieux-dits (dès 12) ; lieux-dits non habités en vert.
        val lieuDitFilter = arr("any",
            kindIn("neighbourhood", "macrohood"),
            arr("all", kindIn("locality"), detailIn(*lieuxDits)),
        )
        layers.put(
            pointLabel("$src-nom-lieuxdits", src, "places", arr("all", hasName, lieuDitFilter), FONT_REGULAR,
                p.labelMinor, p.labelHalo, size(12, 10.5, 16, 13.5), 12)
                .also {
                    it.getJSONObject("paint").put("text-color",
                        arr("case", arr("==", arr("coalesce", arr("get", "kind_detail"), ""), "locality"), p.lieuDit, p.labelMinor))
                },
        )

        // Communes (villes, bourgs, villages) : en gras, taille selon l'importance, dès le zoom 8.
        val communeFilter = arr("all", hasName, kindIn("locality"), arr("!", detailIn(*lieuxDits)))
        val communeSize = arr(
            "interpolate", arr("linear"), arr("zoom"),
            8, arr("match", arr("coalesce", arr("get", "kind_detail"), ""), "city", 14, "town", 12, 10.5),
            12, arr("match", arr("coalesce", arr("get", "kind_detail"), ""), "city", 18, "town", 15.5, 13.5),
            16, arr("match", arr("coalesce", arr("get", "kind_detail"), ""), "city", 22, "town", 19, 16.5),
        )
        layers.put(pointLabel("$src-nom-communes", src, "places", communeFilter, FONT_MEDIUM, p.label, p.labelHalo, communeSize, 8))
    }

    /** Nom affiché : français si disponible, sinon le nom local. */
    private fun name(): JSONArray = arr("coalesce", arr("get", "name:fr"), arr("get", "name"))

    /** Priorité de placement : clé de tri Protomaps (plus petite = plus importante), sinon zoom d'apparition. */
    private fun sortKey(): JSONArray = arr("coalesce", arr("get", "sort_key"), arr("get", "min_zoom"), 99)

    /** Texte posé sur un point (commune, lieu-dit, lac, repère). */
    private fun pointLabel(
        id: String, src: String, sourceLayer: String, filter: JSONArray, font: String,
        color: Any, halo: String, size: JSONArray, minZoom: Int,
    ): JSONObject =
        JSONObject().put("id", id).put("type", "symbol").put("source", src).put("source-layer", sourceLayer)
            .put("minzoom", minZoom)
            .put("filter", filter)
            .put(
                "layout", JSONObject()
                    .put("text-field", name())
                    .put("text-font", JSONArray(listOf(font)))
                    .put("text-size", size)
                    .put("text-max-width", 8)
                    .put("text-padding", 3)
                    .put("symbol-sort-key", sortKey()),
            )
            .put("paint", textPaint(color, halo))

    /** Texte qui suit une ligne (route, chemin, rivière). */
    private fun lineLabel(
        id: String, src: String, sourceLayer: String, filter: JSONArray,
        color: Any, halo: String, size: JSONArray, minZoom: Int,
    ): JSONObject =
        JSONObject().put("id", id).put("type", "symbol").put("source", src).put("source-layer", sourceLayer)
            .put("minzoom", minZoom)
            .put("filter", filter)
            .put(
                "layout", JSONObject()
                    .put("symbol-placement", "line")
                    .put("text-field", name())
                    .put("text-font", JSONArray(listOf(FONT_REGULAR)))
                    .put("text-size", size)
                    .put("text-max-angle", 35)
                    .put("symbol-spacing", 300)
                    .put("text-letter-spacing", 0.05)
                    .put("symbol-sort-key", sortKey()),
            )
            .put("paint", textPaint(color, halo))

    /** Couleur du texte + halo épais (lisible sur forêt, champs et eau, en clair comme en sombre). */
    private fun textPaint(color: Any, halo: String): JSONObject =
        JSONObject().put("text-color", color).put("text-halo-color", halo)
            .put("text-halo-width", 1.6).put("text-halo-blur", 0.4)

    /** Couche de surface. */
    private fun fill(id: String, src: String, sourceLayer: String, color: String, filter: JSONArray?): JSONObject {
        val o = JSONObject().put("id", id).put("type", "fill").put("source", src).put("source-layer", sourceLayer)
            .put("paint", JSONObject().put("fill-color", color))
        if (filter != null) o.put("filter", filter)
        return o
    }

    /** Couche de lignes (bouts arrondis). */
    private fun lineLayer(id: String, src: String, sourceLayer: String, color: String, filter: JSONArray, width: JSONArray): JSONObject =
        JSONObject().put("id", id).put("type", "line").put("source", src).put("source-layer", sourceLayer)
            .put("filter", filter)
            .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
            .put("paint", JSONObject().put("line-color", color).put("line-width", width))

    /**
     * Ligne en tirets (couche « roads »). Bouts PLATS obligatoires : avec des bouts arrondis,
     * chaque tiret déborde sur l'espace suivant et les tirets se fondent en trait continu.
     */
    private fun dashed(id: String, src: String, color: String, filter: JSONArray, width: JSONArray, dashes: List<Double>, minZoom: Int): JSONObject =
        lineLayer(id, src, "roads", color, filter, width)
            .put("minzoom", minZoom)
            .put("layout", JSONObject().put("line-cap", "butt").put("line-join", "round"))
            .also { it.getJSONObject("paint").put("line-dasharray", JSONArray(dashes)) }

    /** Épaisseur qui grossit avec le zoom. */
    private fun width(z1: Int, w1: Double, z2: Int, w2: Double): JSONArray =
        arr("interpolate", arr("linear"), arr("zoom"), z1, w1, z2, w2)

    /** Taille de texte qui grossit avec le zoom. */
    private fun size(z1: Int, s1: Double, z2: Int, s2: Double): JSONArray = width(z1, s1, z2, s2)

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
