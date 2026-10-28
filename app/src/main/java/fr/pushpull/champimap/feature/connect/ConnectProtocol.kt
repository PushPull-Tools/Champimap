package fr.pushpull.champimap.feature.connect

import fr.pushpull.champimap.core.util.pluriel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Copie, côté appli principale, du protocole d'échange avec Champimap Connect.
 * RÉFÉRENCE : connect/src/main/java/fr/pushpull/champimap/connect/SyncProtocol.kt
 * Les constantes sont recopiées À L'IDENTIQUE (aucune dépendance au module connect) :
 * toute modification doit être faite des deux côtés.
 *
 * Demande : EXTRA_DEPARTEMENTS = départements dont le contour et les urgences sont à (re)télécharger,
 * EXTRA_MAP_DEPARTEMENTS = départements dont la carte est à (re)télécharger. L'appli n'y met que ce qui
 * manque (synchro normale) ou tout (« Actualiser les cartes ») : voir ConnectClient.buildSyncIntent.
 *
 * Fichiers renvoyés par Connect :
 *   map_<code>.pmtiles · contour_<code>.geojson · hospitals_<code>.json · weather.json · antipoison.json
 *   ia_manifest.json (modèle d'identification : version, empreinte, liste des fichiers avec leur SHA-256 ;
 *   les fichiers eux-mêmes sont lus sous IA_BASE_URI/<version>/<chemin>, lecture accordée par préfixe).
 *
 * Modèle d'identification (ajout compatible) : EXTRA_INCLUDE_IA, EXTRA_IA_VERSION_INSTALLED, EXTRA_IA_FORCE,
 * EXTRA_IA_VERSION_WANTED (version fixée), EXTRA_IA_CHOOSE (Connect affiche la liste des versions),
 * EXTRA_IA_LIST_ONLY (Connect 3 : seulement la liste des versions). Connect 3 renvoie ia_versions.json
 * (versions publiées et statistiques) à chaque demande incluant le modèle.
 * Résultat EXTRA_WAIT_WIFI = true : l'utilisateur a choisi « Attendre le Wi-Fi » (rien n'a été lancé).
 *
 * Liste des tâches (Connect 4, snapshot.format = 2) : snapshot.items = les vraies tâches dans l'ordre réel
 * d'exécution, avec "title" (libellé court), "group" (« 87 · Haute-Vienne » ou « Général »), "bytes" (taille
 * réelle si connue), états WAITING / RUNNING / DONE / SKIPPED (déjà à jour) / ERROR / CANCELLED (pas fait) ;
 * percent pondéré par les octets et monotone. Identifiants : contour_<code>, urgences_<code>, carte_<code>,
 * antipoison, meteo, ia_versions, ia (servent à « Réessayer » une tâche précise).
 * Ancien Connect (pas de "format") : l'appli garde l'ancien affichage par famille.
 */
object ConnectProtocol {
    /** Paquet de l'appli Champimap Connect (seule à avoir Internet). */
    const val CONNECT_PACKAGE = "fr.pushpull.champimap.connect"
    /**
     * Version minimale de Connect comprenant ce protocole : sinon « Mettre à jour ».
     * 2 = modèle d'identification ; 3 = liste des versions du modèle (ia_versions.json, EXTRA_IA_LIST_ONLY) ;
     * 4 = liste des vraies tâches (snapshot.format = 2).
     */
    const val MIN_CONNECT_VERSION_CODE = 4L

    /** Format d'instantané à partir duquel les tâches détaillées (title, group, bytes) sont fournies. */
    const val TASKS_FORMAT = 2
    /** Nom du groupe des tâches qui ne dépendent pas d'un département. */
    const val GROUP_GENERAL = "Général"

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
    const val EXTRA_INCLUDE_IA = "includeIa"
    const val EXTRA_IA_VERSION_INSTALLED = "iaVersionInstalled"
    const val EXTRA_IA_FORCE = "iaForce"
    const val EXTRA_IA_VERSION_WANTED = "iaVersionWanted"
    const val EXTRA_IA_CHOOSE = "iaChoose"
    const val EXTRA_IA_LIST_ONLY = "iaListOnly"

    // Réponse (résultat d'activité et diffusion)
    const val EXTRA_SNAPSHOT = "snapshot"
    const val EXTRA_WAIT_WIFI = "waitWifi"

    /** Zone météo : carré de 0,25°, ex. « 45.75_1.25 » (aucune autre précision acceptée). */
    val ZONE_REGEX = Regex("^-?\\d{1,2}\\.(00|25|50|75)_-?\\d{1,3}\\.(00|25|50|75)$")
    val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    val DEPT_REGEX = Regex("^(\\d{2}|2A|2B|97[1-6])$")

    // Préfixes des noms de fichiers renvoyés
    const val FILE_MAP_PREFIX = "map_"
    const val FILE_CONTOUR_PREFIX = "contour_"
    const val FILE_HOSPITALS_PREFIX = "hospitals_"
    const val FILE_WEATHER = "weather.json"
    const val FILE_ANTIPOISON = "antipoison.json"
    const val FILE_IA_MANIFEST = "ia_manifest.json"
    const val FILE_IA_VERSIONS = "ia_versions.json"

    /** Dossier des fichiers du modèle chez Connect : IA_BASE_URI + "<version>/<chemin>". */
    const val IA_BASE_URI = "content://$FILES_AUTHORITY/sync/ia/"
    /** Version d'un modèle : « ia-v1 », « ia-v2 »… */
    val IA_VERSION_REGEX = Regex("^ia-v(\\d{1,6})$")

    /** Lecteur JSON tolérant (champs inconnus ignorés). */
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

/**
 * État d'une tâche (mêmes noms que côté Connect). SKIPPED = ignorée car déjà à jour ;
 * CANCELLED = pas faite (annulation ou arrêt). Valeur inconnue = WAITING.
 */
enum class ItemStatus { WAITING, RUNNING, DONE, ERROR, SKIPPED, CANCELLED }

/** Nature d'un élément (sert à l'icône et au regroupement). */
enum class ItemKind { CONTOUR, URGENCES, CARTE, METEO, ANTIPOISON, IA }

/** Une tâche de la synchro (« Carte » du groupe « 87 · Haute-Vienne »…). */
data class SyncItem(
    val id: String,
    val kind: ItemKind,
    /** Libellé complet, lisible seul (« Carte · Haute-Vienne »). */
    val label: String,
    val departement: String?,
    val status: ItemStatus,
    val percent: Int,
    /** Détail (avancement, résultat) ou message d'erreur court. */
    val detail: String?,
    /** Libellé court affiché sous le nom du groupe (« Carte ») ; = label avec un ancien Connect. */
    val title: String = label,
    /** Groupe : « 87 · Haute-Vienne » ou « Général ». */
    val group: String = ConnectProtocol.GROUP_GENERAL,
    /** Taille réelle en octets, si connue. */
    val sizeBytes: Long? = null,
) {
    /** Vrai si la tâche n'est plus à faire (faite, déjà à jour ou en erreur). */
    val isProcessed: Boolean
        get() = status == ItemStatus.DONE || status == ItemStatus.SKIPPED || status == ItemStatus.ERROR
}

/** Avancement d'un département. */
data class DeptProgress(val code: String, val name: String, val percent: Int, val status: ItemStatus)

/** Fichier prêt, déjà autorisé en lecture pour Champimap. */
data class SyncFile(val name: String, val uri: String, val size: Long)

/** Instantané complet de la synchronisation, tel que reçu de Connect. */
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
    /** 1 = ancien Connect (affichage par famille) ; 2 = vraies tâches (ConnectProtocol.TASKS_FORMAT). */
    val format: Int = 1,
) {
    /** Vrai si Connect décrit ses vraies tâches (title, group, états détaillés). */
    val hasTasks: Boolean get() = format >= ConnectProtocol.TASKS_FORMAT

    /** Nombre de tâches traitées (faites, déjà à jour ou en erreur). */
    val processedCount: Int get() = items.count { it.isProcessed }

    /** Tâche en cours (la première, Connect n'en mène qu'une à la fois). */
    val currentItem: SyncItem? get() = items.firstOrNull { it.status == ItemStatus.RUNNING }

    /**
     * Ligne de résumé : « 3 tâches sur 9 · 42 % · Carte · Haute-Vienne ».
     * [percent] : pourcentage à afficher ; [fallback] : fin de ligne sans tâche en cours.
     */
    fun summary(percent: Int, fallback: String): String = buildString {
        if (items.isNotEmpty()) append("${pluriel(processedCount, "tâche")} sur ${items.size} · ")
        append("$percent %")
        val tail = currentItem?.label ?: fallback
        if (tail.isNotBlank()) append(" · $tail")
    }

    companion object {
        /** Décode le JSON envoyé par Connect (null si illisible). Les valeurs inconnues sont tolérées. */
        fun parse(text: String?): SyncSnapshot? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val o = ConnectProtocol.json.parseToJsonElement(text) as JsonObject
                SyncSnapshot(
                    syncId = o.str("syncId") ?: "",
                    running = o.bool("running") ?: false,
                    finished = o.bool("finished") ?: false,
                    percent = (o.int("percent") ?: 0).coerceIn(0, 100),
                    step = o.str("step") ?: "",
                    departements = o.objects("departements").map {
                        DeptProgress(
                            code = it.str("code") ?: "",
                            name = it.str("name") ?: "",
                            percent = (it.int("percent") ?: 0).coerceIn(0, 100),
                            status = statusOf(it.str("status")),
                        )
                    },
                    items = o.objects("items").map {
                        SyncItem(
                            id = it.str("id") ?: "",
                            kind = kindOf(it.str("kind")),
                            label = it.str("label") ?: "",
                            departement = it.str("departement"),
                            status = statusOf(it.str("status")),
                            percent = (it.int("percent") ?: 0).coerceIn(0, 100),
                            detail = it.str("detail"),
                            title = it.str("title") ?: it.str("label") ?: "",
                            group = it.str("group") ?: ConnectProtocol.GROUP_GENERAL,
                            sizeBytes = it.long("bytes")?.takeIf { b -> b > 0 },
                        )
                    },
                    files = o.objects("files").mapNotNull {
                        val name = it.str("name") ?: return@mapNotNull null
                        val uri = it.str("uri") ?: return@mapNotNull null
                        SyncFile(name, uri, it.long("size") ?: 0L)
                    },
                    errors = (o["errors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    startedAt = o.long("startedAt") ?: 0L,
                    endedAt = o.long("endedAt") ?: 0L,
                    format = o.int("format") ?: 1,
                )
            }.getOrNull()
        }

        private fun statusOf(s: String?): ItemStatus = ItemStatus.entries.firstOrNull { it.name == s } ?: ItemStatus.WAITING
        private fun kindOf(s: String?): ItemKind = ItemKind.entries.firstOrNull { it.name == s } ?: ItemKind.CARTE
    }
}

// ---------------------------------------------------------------------------------------------
// Petits lecteurs JSON tolérants (utilisés aussi pour les fichiers importés)
// ---------------------------------------------------------------------------------------------

internal fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
internal fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull?.takeIf { it != "null" }
internal fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull ?: prim(key)?.doubleOrNull?.toInt()
internal fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull ?: prim(key)?.doubleOrNull?.toLong()
internal fun JsonObject.dbl(key: String): Double? = prim(key)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
internal fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Première valeur texte trouvée parmi plusieurs noms de champ possibles. */
internal fun JsonObject.strOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { str(it) }
internal fun JsonObject.dblOf(vararg keys: String): Double? = keys.firstNotNullOfOrNull { dbl(it) }

/**
 * Liste d'objets d'un fichier : soit un tableau à la racine, soit le premier tableau
 * trouvé sous l'une des clés proposées (ex. {"hospitals": [...]}).
 */
internal fun JsonElement.objectList(vararg keys: String): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> keys.firstNotNullOfOrNull { k -> (this[k] as? JsonArray) }
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    else -> emptyList()
}
