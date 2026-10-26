package fr.pushpull.champimap.connect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Protocole d'échange entre Champimap (SANS Internet) et Champimap Connect.
 * Une copie identique des constantes et des modèles existe côté appli principale
 * (fr.pushpull.champimap.feature.connect.ConnectProtocol) : toute modification doit être faite des deux côtés.
 *
 * 1. Champimap lance ConnectActivity (action SYNC, Intent explicite) avec :
 *    - EXTRA_DEPARTEMENTS : codes des départements cochés (contours + urgences) ;
 *    - EXTRA_MAP_DEPARTEMENTS : codes dont la carte doit être (re)téléchargée ;
 *    - EXTRA_WEATHER_ZONES : « zoneId|AAAA-MM-JJ|AAAA-MM-JJ » (carrés de 0,25°, JAMAIS une position de spot) ;
 *    - EXTRA_POISON_CENTERS : true pour recevoir la liste à jour des centres antipoison.
 * 2. Connect télécharge, écrit les fichiers dans son cacheDir/sync/ et les expose par SON FileProvider.
 * 3. Avancement : diffusion ACTION_PROGRESS (réservée à la permission « signature » CONNECT) contenant
 *    un instantané JSON (SyncSnapshot). Chaque fichier terminé y figure avec son URI, déjà autorisé en
 *    lecture pour Champimap (grantUriPermission) : l'appli peut l'importer tout de suite.
 * 4. Fin : résultat d'activité avec les URI en ClipData + FLAG_GRANT_READ_URI_PERMISSION.
 */
object SyncProtocol {
    const val MAIN_PACKAGE = "fr.pushpull.champimap"
    const val PERMISSION = "fr.pushpull.champimap.permission.CONNECT"
    const val FILES_AUTHORITY = "fr.pushpull.champimap.connect.files"

    const val ACTION_SYNC = "fr.pushpull.champimap.connect.SYNC"
    const val ACTION_PROGRESS = "fr.pushpull.champimap.connect.PROGRESS"

    // Demande
    const val EXTRA_DEPARTEMENTS = "departements"
    const val EXTRA_MAP_DEPARTEMENTS = "mapDepartements"
    const val EXTRA_WEATHER_ZONES = "weatherZones"
    const val EXTRA_POISON_CENTERS = "poisonCenters"

    // Réponse (résultat d'activité et diffusion)
    const val EXTRA_SNAPSHOT = "snapshot"
    const val EXTRA_FILE_NAMES = "fileNames"
    const val EXTRA_BACKGROUND = "background"

    /** Format d'une zone météo : coin sud-ouest d'un carré de 0,25°, ex. « 45.75_1.25 ». */
    val ZONE_REGEX = Regex("^(-?\\d{1,2}\\.(?:00|25|50|75))_(-?\\d{1,3}\\.(?:00|25|50|75))$")
    val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/** État d'un élément de la liste d'avancement. */
enum class ItemStatus { WAITING, RUNNING, DONE, ERROR, SKIPPED }

/** Nature d'un élément (sert à l'icône côté appli). */
enum class ItemKind { CONTOUR, URGENCES, CARTE, METEO, ANTIPOISON }

/** Un élément de la liste dynamique (« Carte · Haute-Vienne », « Urgences · Corrèze »…). */
@Serializable
data class SyncItem(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val departement: String? = null,
    val status: ItemStatus = ItemStatus.WAITING,
    val percent: Int = 0,
    val detail: String? = null,
)

/** Avancement d'un département (moyenne pondérée de ses éléments). */
@Serializable
data class DeptProgress(val code: String, val name: String, val percent: Int, val status: ItemStatus)

/** Fichier prêt, déjà autorisé en lecture pour Champimap. */
@Serializable
data class SyncFile(val name: String, val uri: String)

/** Instantané complet de la synchronisation (diffusé et renvoyé à l'appli). */
@Serializable
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
)

/** Demande reçue de Champimap, déjà validée. */
data class SyncRequest(
    val departements: List<String>,
    val mapDepartements: List<String>,
    val zones: List<WeatherZoneRequest>,
    val poisonCenters: Boolean,
)

/** Une zone météo : carré de 0,25° et période (dates locales AAAA-MM-JJ). */
data class WeatherZoneRequest(val zoneId: String, val from: String, val to: String) {
    /** Centre du carré (seul point envoyé à Open-Meteo). */
    val centerLat: Double get() = zoneId.substringBefore('_').toDouble() + 0.125
    val centerLon: Double get() = zoneId.substringAfter('_').toDouble() + 0.125
}
