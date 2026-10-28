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
 *    - EXTRA_DEPARTEMENTS : codes dont le contour et les urgences doivent être (re)téléchargés ;
 *    - EXTRA_MAP_DEPARTEMENTS : codes dont la carte doit être (re)téléchargée ;
 *      C'est l'appli qui choisit : synchro normale = seulement les départements sans données (nouveau
 *      département coché) ; « Actualiser les cartes » = tous les départements cochés. Connect télécharge
 *      exactement ce qui est demandé : aucun drapeau supplémentaire n'est nécessaire (rétrocompatible).
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
 * ---------------------------------------------------------------- Ajout : modèle d'identification (compatible)
 * Demande (facultative ; absente = comportement d'avant, rien n'est téléchargé pour l'IA) :
 *   EXTRA_INCLUDE_IA (Boolean) : vérifier / télécharger le modèle d'identification ;
 *   EXTRA_IA_VERSION_INSTALLED (String, « ia-vN », vide = aucun) : version déjà installée dans l'appli ;
 *   EXTRA_IA_FORCE (Boolean) : « Actualiser » complet = retélécharger même si la version est à jour ;
 *   EXTRA_IA_VERSION_WANTED (String, « ia-vN », facultatif) : version précise voulue (sinon la plus récente) ;
 *   EXTRA_IA_CHOOSE (Boolean) : Connect affiche la liste des versions publiées pour que l'utilisateur choisisse
 *     (« La plus récente » ou une version précise, pour réinstaller une ancienne version si besoin).
 *   EXTRA_IA_LIST_ONLY (Boolean, Connect versionCode 3) : seulement la liste des versions publiées
 *     (fichier ia_versions.json), rien n'est téléchargé d'autre ; pas de fenêtre « données mobiles » (quelques Ko).
 * Connect lit la liste PUBLIQUE des releases GitHub (GET non authentifié, aucune donnée envoyée), garde
 * les releases non brouillon « ia-vN » ayant l'asset champi-ia-resultat.tar.gz et prend le plus grand N.
 * Téléchargement reprenable, empreinte SHA-256 vérifiée (champ « digest » de l'API s'il existe), extraction
 * dans cacheDir/sync/ia/<version>/ ; lecture accordée à Champimap pour tout ce dossier (préfixe d'URI :
 *   content://fr.pushpull.champimap.connect.files/sync/ia/<version>/<chemin>).
 * Réponse : snapshot.ia = { version, upToDate, sha256, digestVerified, fileCount, totalBytes } et le fichier
 *   ia_manifest.json (dans snapshot.files, annoncé quand TOUT est extrait) :
 *   { "version": "ia-v2", "sha256": "<archive>", "digestVerified": true, "archiveSize": 143386734,
 *     "exact": false,   (true = version précise choisie, false = « la plus récente »)
 *     "files": [ { "path": "champi-ia.onnx", "size": 36591017, "sha256": "<fichier>" }, … ] }
 *   (liste dans un fichier : ~3 300 photos de fiches, trop pour l'instantané diffusé toutes les 400 ms).
 * Liste des versions (Connect versionCode 3) : à CHAQUE demande avec EXTRA_INCLUDE_IA, dès la liste lue,
 *   le fichier ia_versions.json (TABLEAU, de la plus récente à la plus ancienne) est annoncé :
 *   [ { "version": "ia-v1", "especes": 1032, "top1": 73.8, "top5": 92.7, "tailleModeleMo": 35.0,
 *       "size": 143386734, "date": "2026-10-26" } ]   (champs de statistiques absents = inconnus)
 * Résultat d'activité EXTRA_WAIT_WIFI = true (RESULT_CANCELED) : l'utilisateur a choisi « Attendre le Wi-Fi ».
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

    /** Ajout : modèle d'identification (voir l'en-tête). */
    const val EXTRA_INCLUDE_IA = "includeIa"
    const val EXTRA_IA_VERSION_INSTALLED = "iaVersionInstalled"
    const val EXTRA_IA_FORCE = "iaForce"
    const val EXTRA_IA_VERSION_WANTED = "iaVersionWanted"
    const val EXTRA_IA_CHOOSE = "iaChoose"
    /** Ajout (versionCode 3) : seulement la liste des versions (ia_versions.json), aucun téléchargement de modèle. */
    const val EXTRA_IA_LIST_ONLY = "iaListOnly"
    /** Nom de la liste des versions publiées renvoyée dans snapshot.files. */
    const val IA_VERSIONS = "ia_versions.json"
    /** Nom du manifeste du modèle renvoyé dans snapshot.files. */
    const val IA_MANIFEST = "ia_manifest.json"
    /** Version d'un modèle : « ia-v1 », « ia-v2 »… */
    val IA_VERSION_REGEX = Regex("^ia-v(\\d{1,6})$")

    // Réponse (résultat d'activité et diffusion)
    const val EXTRA_SNAPSHOT = "snapshot"
    /** Résultat : true = synchro non lancée, l'utilisateur attend d'être en Wi-Fi. */
    const val EXTRA_WAIT_WIFI = "waitWifi"

    /** Zone météo : coin sud-ouest d'un carré de 0,25°, ex. « 45.75_1.25 » (aucune autre précision acceptée). */
    val ZONE_REGEX = Regex("^-?\\d{1,2}\\.(00|25|50|75)_-?\\d{1,3}\\.(00|25|50|75)$")
    val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    val DEPT_REGEX = Regex("^(\\d{2}|2A|2B|97[1-6])$")

    val json = Json { ignoreUnknownKeys = true }
}

/** État d'un élément de la liste d'avancement. */
enum class ItemStatus { WAITING, RUNNING, DONE, ERROR, SKIPPED }

/** Nature d'un élément (sert à l'icône côté appli). */
enum class ItemKind { CONTOUR, URGENCES, CARTE, METEO, ANTIPOISON, IA }

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

/** Résumé du modèle d'identification dans l'instantané (null = non demandé ou pas encore traité). */
data class IaSyncInfo(
    val version: String,
    /** Vrai si la version installée dans l'appli est déjà la plus récente (rien de téléchargé). */
    val upToDate: Boolean,
    val sha256: String? = null,
    /** Vrai si l'empreinte a été comparée à celle publiée par GitHub (sinon : calculée et transmise). */
    val digestVerified: Boolean = false,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
)

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
    val ia: IaSyncInfo? = null,
) {
    /** Vrai si la synchro a donné quelque chose : au moins un fichier, ou un modèle déjà à jour. */
    val hasResult: Boolean get() = files.isNotEmpty() || ia?.upToDate == true

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
        "ia" to ia?.let {
            jsonObj(
                "version" to it.version, "upToDate" to it.upToDate, "sha256" to it.sha256,
                "digestVerified" to it.digestVerified, "fileCount" to it.fileCount, "totalBytes" to it.totalBytes,
            )
        },
    )
}

/** Demande reçue de Champimap, déjà validée. */
data class SyncRequest(
    val departements: List<String>,
    val mapDepartements: List<String>,
    val zones: List<WeatherZoneRequest>,
    val poisonCenters: Boolean,
    /** Modèle d'identification à vérifier (null = non demandé). */
    val ia: IaRequest? = null,
)

/**
 * Demande « modèle d'identification ».
 * @param installed version installée dans l'appli (null = aucune)
 * @param force retélécharger même si la version est à jour
 * @param wanted version précise voulue (null = la plus récente)
 * @param choose afficher la liste des versions publiées avant de lancer la synchro
 * @param listOnly seulement renvoyer la liste des versions (ia_versions.json), sans télécharger de modèle
 */
data class IaRequest(
    val installed: String?,
    val force: Boolean,
    val wanted: String? = null,
    val choose: Boolean = false,
    val listOnly: Boolean = false,
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
