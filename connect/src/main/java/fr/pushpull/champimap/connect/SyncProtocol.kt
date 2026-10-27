package fr.pushpull.champimap.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Protocole d'échange entre Champimap (SANS Internet) et Champimap Connect.
 * Une copie des constantes et du décodage existe côté appli principale
 * (fr.pushpull.champimap.feature.connect.ConnectProtocol) : toute modification doit être faite des deux côtés.
 *
 * 1. Champimap lance ConnectActivity (Intent explicite, action SYNC) avec :
 *    - EXTRA_DEPARTEMENTS : codes des départements cochés (contours + urgences) ;
 *    - EXTRA_MAP_DEPARTEMENTS : codes dont la carte doit être (re)téléchargée ;
 *    - EXTRA_WEATHER_ZONES : « zoneId|AAAA-MM-JJ|AAAA-MM-JJ » (carrés de 0,25°, JAMAIS une position de spot) ;
 *    - EXTRA_POISON_CENTERS : true pour recevoir la liste à jour des centres antipoison ;
 *    - EXTRA_BACKGROUND : true = démarrer et rendre la main tout de suite (avancement par diffusion).
 * 2. Connect télécharge, écrit les fichiers dans son cacheDir/sync/ et les expose par SON FileProvider.
 * 3. Avancement : diffusion ACTION_PROGRESS (réservée à la permission « signature » CONNECT) contenant
 *    un instantané JSON. Chaque fichier terminé y figure avec son URI, déjà autorisé en lecture pour
 *    Champimap (grantUriPermission) : l'appli peut l'importer sans attendre la fin.
 *    Le même instantané est écrit dans sync/state.json (URI fixe STATE_URI, autorisé lui aussi) pour
 *    que l'appli rattrape une synchro terminée pendant qu'elle était fermée.
 * 4. Fin : résultat d'activité avec les URI en ClipData + FLAG_GRANT_READ_URI_PERMISSION.
 *
 * Noms des fichiers renvoyés :
 *   map_<code>.pmtiles · contour_<code>.geojson · hospitals_<code>.json · weather.json · antipoison.json
 *
 * ---------------------------------------------------------------- Ajouts (module connect, compatibles)
 * Types des extras de demande : tableaux de texte (String[], putExtra(nom, arrayOf(...))) ;
 *   ArrayList<String> (putStringArrayListExtra) est accepté aussi. Booléens : putExtra(nom, true).
 * EXTRA_MAP_MAX_ZOOM (Int, facultatif) : zoom maximal des cartes extraites, 10 à 15, défaut 15.
 *   Le zoom 15 est le niveau le plus détaillé des builds Protomaps : sentiers (highway=path/footway/
 *   bridleway, dès z13), lieux-dits et fermes isolées (dès z13–14), noms des petites routes et chemins,
 *   sommets et repères. Taille estimée : environ 2 à 2,5 fois celle d'une extraction limitée à z14
 *   (le niveau 15 compte 4 fois plus de tuiles que le 14), soit en ordre de grandeur 2 à 3 Mo
 *   par 400 km² de campagne (≈ 30 à 40 Mo pour la Haute-Vienne), davantage en zone urbaine dense.
 *
 * Résultat d'activité :
 *   - RESULT_OK : au moins un fichier prêt (des erreurs partielles peuvent figurer dans snapshot.errors) ;
 *     en mode arrière-plan (ou si l'utilisateur fait « Retour »), RESULT_OK est renvoyé tout de suite avec
 *     un instantané `running = true` : la suite arrive par ACTION_PROGRESS et state.json ;
 *   - RESULT_CANCELED : annulé ou aucun fichier obtenu (raisons dans snapshot.errors) ;
 *   - dans tous les cas l'extra EXTRA_SNAPSHOT (texte JSON de SyncSnapshot) est présent.
 *
 * Formats des fichiers JSON (FIGÉS, lus par l'appli principale) : UTF-8, TABLEAU d'objets à la racine.
 *   weather.json : [ { "zoneId": "45.75_1.25", "date": "AAAA-MM-JJ", "rainMm": 3.2, "tMinC": 4.1, "tMaxC": 13.8 } ]
 *     (une ligne par zone et par jour ; jours incomplets omis ; dates futures ignorées ; heure de Paris ;
 *      les zones en échec ne figurent pas : elles sont signalées dans snapshot.errors)
 *   hospitals_<code>.json : [ { "id": "way/123", "name": "CHU …", "lat": 45.81, "lon": 1.23,
 *                               "phone": "05 55 05 55 55" ou null, "departement": "87" } ]
 *     (emprise rectangulaire du département : des hôpitaux voisins peuvent y figurer)
 *   antipoison.json : [ { "name": "Centre antipoison de Bordeaux", "phone": "05 56 96 40 80",
 *                         "departements": ["33", …] } ]
 *     (champs en plus, ignorés si inutiles : "id", "city", "lat", "lon", "regions", "verified")
 *   contour_<code>.geojson : GeoJSON du département (dépôt france-geojson, tel quel).
 */
object SyncProtocol {
    const val MAIN_PACKAGE = "fr.pushpull.champimap"
    const val PERMISSION = "fr.pushpull.champimap.permission.CONNECT"
    const val FILES_AUTHORITY = "fr.pushpull.champimap.connect.files"
    const val STATE_URI = "content://$FILES_AUTHORITY/sync/state.json"

    const val ACTION_SYNC = "fr.pushpull.champimap.connect.SYNC"
    const val ACTION_PROGRESS = "fr.pushpull.champimap.connect.PROGRESS"

    // Demande
    const val EXTRA_DEPARTEMENTS = "departements"
    const val EXTRA_MAP_DEPARTEMENTS = "mapDepartements"
    const val EXTRA_WEATHER_ZONES = "weatherZones"
    const val EXTRA_POISON_CENTERS = "poisonCenters"
    const val EXTRA_BACKGROUND = "background"
    /** Ajout : zoom maximal des cartes (Int, 10..15, défaut MAP_MAX_ZOOM_DEFAULT). */
    const val EXTRA_MAP_MAX_ZOOM = "mapMaxZoom"
    /** 15 = niveau maximal Protomaps, nécessaire pour avoir tous les chemins et lieux-dits (voir plus haut). */
    const val MAP_MAX_ZOOM_DEFAULT = 15

    // Réponse (résultat d'activité et diffusion)
    const val EXTRA_SNAPSHOT = "snapshot"

    /** Zone météo : coin sud-ouest d'un carré de 0,25°, ex. « 45.75_1.25 » (aucune autre précision acceptée). */
    val ZONE_REGEX = Regex("^-?\\d{1,2}\\.(00|25|50|75)_-?\\d{1,3}\\.(00|25|50|75)$")
    val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    val DEPT_REGEX = Regex("^(\\d{2}|2A|2B|97[1-6])$")

    val json = Json { ignoreUnknownKeys = true }
}

/** État d'un élément de la liste d'avancement. */
enum class ItemStatus { WAITING, RUNNING, DONE, ERROR, SKIPPED }

/** Nature d'un élément (sert à l'icône côté appli). */
enum class ItemKind { CONTOUR, URGENCES, CARTE, METEO, ANTIPOISON }

/** Un élément de la liste dynamique (« Carte · Haute-Vienne », « Urgences · Corrèze »…). */
data class SyncItem(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val departement: String? = null,
    val status: ItemStatus = ItemStatus.WAITING,
    val percent: Int = 0,
    val detail: String? = null,
    /** Poids relatif dans le pourcentage global (une carte pèse bien plus qu'un contour). */
    val weight: Int = 1,
)

/** Avancement d'un département (moyenne pondérée de ses éléments). */
data class DeptProgress(val code: String, val name: String, val percent: Int, val status: ItemStatus)

/** Fichier prêt, déjà autorisé en lecture pour Champimap. */
data class SyncFile(val name: String, val uri: String, val size: Long)

/** Instantané complet de la synchronisation (diffusé, écrit dans state.json et renvoyé à l'appli). */
data class SyncSnapshot(
    val syncId: String = "",
    val running: Boolean = false,
    val finished: Boolean = false,
    val percent: Int = 0,
    val step: String = "",
    val departements: List<DeptProgress> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val files: List<SyncFile> = emptyList(),
    val errors: List<String> = emptyList(),
    val startedAt: Long = 0,
    val endedAt: Long = 0,
) {
    /** Sérialisation JSON (format lu par l'appli principale). */
    fun toJson(): String = SyncProtocol.json.encodeToString(JsonElement.serializer(), toElement())

    private fun toElement(): JsonObject = jsonObj(
        "syncId" to syncId, "running" to running, "finished" to finished, "percent" to percent,
        "step" to step, "startedAt" to startedAt, "endedAt" to endedAt,
        "departements" to JsonArray(departements.map {
            jsonObj("code" to it.code, "name" to it.name, "percent" to it.percent, "status" to it.status.name)
        }),
        "items" to JsonArray(items.map {
            jsonObj(
                "id" to it.id, "kind" to it.kind.name, "label" to it.label, "departement" to it.departement,
                "status" to it.status.name, "percent" to it.percent, "detail" to it.detail,
            )
        }),
        "files" to JsonArray(files.map { jsonObj("name" to it.name, "uri" to it.uri, "size" to it.size) }),
        "errors" to JsonArray(errors.map { JsonPrimitive(it) }),
    )
}

/** Demande reçue de Champimap, déjà validée. */
data class SyncRequest(
    val departements: List<String>,
    val mapDepartements: List<String>,
    val zones: List<WeatherZoneRequest>,
    val poisonCenters: Boolean,
)

/** Une zone météo : carré de 0,25° et période (dates locales AAAA-MM-JJ). */
data class WeatherZoneRequest(val zoneId: String, val from: String, val to: String) {
    /** Centre du carré : seul point envoyé à Open-Meteo. */
    val centerLat: Double get() = zoneId.substringBefore('_').toDouble() + 0.125
    val centerLon: Double get() = zoneId.substringAfter('_').toDouble() + 0.125
}

/** Petit constructeur d'objet JSON (valeurs : texte, nombre, booléen, null ou JsonElement). */
fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)
