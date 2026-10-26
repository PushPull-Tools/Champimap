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
