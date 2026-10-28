package fr.pushpull.champimap.feature.spot

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.pushpull.champimap.core.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/*
 * Modèle d'identification livré par Champimap Connect (plus dans l'APK) :
 *   filesDir/ia/            modèle actif (lu en priorité par IaAssets)
 *   filesDir/ia_tmp/        réception d'une nouvelle version (copie + vérifications)
 *   filesDir/ia_precedent/  version précédente gardée en secours (une seule ; la plus ancienne est supprimée)
 * Les bascules se font par renommage de dossiers (atomique) : jamais de modèle à moitié copié.
 * Le DataStore « ia_model » mémorise : interrupteur « Identification par photo », version active et
 * précédente (empreinte, nombre d'espèces, taille) et la version FIXÉE (choisie à la main ou restaurée :
 * la synchro normale ne la remplace pas par une plus récente).
 */

/** Ce que l'appli sait du modèle (versions, interrupteur). */
data class IaModelInfo(
    /** Interrupteur « Identification par photo » de l'écran Connect (activé par défaut). */
    val enabled: Boolean = true,
    val version: String? = null,
    val sha256: String? = null,
    val speciesCount: Int = 0,
    val previousVersion: String? = null,
    val previousSpeciesCount: Int = 0,
    val previousBytes: Long = 0,
    /** Version fixée (null = suivre la plus récente). */
    val pinnedVersion: String? = null,
)

/** Statistiques d'un modèle (toutes facultatives) : stats.json, sinon rapport.txt + labels.json. */
data class IaStats(
    val version: String? = null,
    val especes: Int? = null,
    val top1: Double? = null,
    val top5: Double? = null,
    val tailleModeleMo: Double? = null,
    /** Date de publication « AAAA-MM-JJ ». */
    val date: String? = null,
) {
    /** « ia-v1 · 1 032 espèces · réussite 73,8 % (top-5 92,7 %) · 35 Mo · 26/10/2026 ». */
    fun summary(): String = buildList {
        version?.let { add(it) }
        especes?.let { add("${String.format(Locale.FRANCE, "%,d", it).replace(' ', ' ').replace(' ', ' ')} espèces") }
        top1?.let { t1 ->
            add("réussite ${pct(t1)}" + (top5?.let { " (top-5 ${pct(it)})" } ?: ""))
        }
        tailleModeleMo?.let { add(String.format(Locale.FRANCE, "%.0f Mo", it)) }
        date?.takeIf { DATE.matches(it) }?.let { add("${it.substring(8, 10)}/${it.substring(5, 7)}/${it.substring(0, 4)}") }
    }.joinToString(" · ")

    private fun pct(v: Double) = String.format(Locale.FRANCE, "%.1f %%", v)

    companion object {
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val R_ESPECES = Regex("Espèces\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val R_TOP1 = Regex("Top-1\\s*:\\s*([\\d.,]+)", RegexOption.IGNORE_CASE)
        private val R_TOP5 = Regex("Top-5\\s*:\\s*([\\d.,]+)", RegexOption.IGNORE_CASE)

        /**
         * Assemble les statistiques : stats.json (publié avec la release) en priorité, complété par
         * rapport.txt, le nombre d'espèces de labels.json et la taille du fichier du modèle.
         */
        fun from(statsJson: String?, rapport: String?, labelsCount: Int?, modelBytes: Long?, fallbackVersion: String?): IaStats {
            val o = statsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            fun num(k: String): Double? = o?.optDouble(k, Double.NaN)?.takeIf { !it.isNaN() }
            fun str(k: String): String? = o?.optString(k, "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            fun rx(r: Regex): Double? = rapport?.let { r.find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
            return IaStats(
                version = str("version") ?: fallbackVersion,
                especes = num("especes")?.toInt() ?: rx(R_ESPECES)?.toInt() ?: labelsCount?.takeIf { it > 0 },
                top1 = num("top1") ?: rx(R_TOP1),
                top5 = num("top5") ?: rx(R_TOP5),
                tailleModeleMo = num("tailleModeleMo") ?: modelBytes?.takeIf { it > 0 }?.let { it / 1048576.0 },
                date = str("datePublication") ?: str("date"),
            )
        }
    }
}

/** Petit stockage du modèle d'identification. */
private val Context.iaModelStore: DataStore<Preferences> by preferencesDataStore(name = "ia_model")

object IaModel {
    /** Taille approximative du paquet téléchargé par Connect (affichée avant le téléchargement). */
    const val APPROX_SIZE_MO = 140

    private const val DIR_ACTIVE = "ia"
    private const val DIR_TMP = "ia_tmp"
    private const val DIR_PREVIOUS = "ia_precedent"
    private const val DIR_SWAP = "ia_bascule"

    private val K_ENABLED = booleanPreferencesKey("enabled")
    private val K_VERSION = stringPreferencesKey("version")
    private val K_SHA = stringPreferencesKey("sha256")
    private val K_COUNT = intPreferencesKey("speciesCount")
    private val K_PREV_VERSION = stringPreferencesKey("previousVersion")
    private val K_PREV_SHA = stringPreferencesKey("previousSha256")
    private val K_PREV_COUNT = intPreferencesKey("previousSpeciesCount")
    private val K_PREV_BYTES = longPreferencesKey("previousBytes")
    private val K_BYTES = longPreferencesKey("bytes")
    private val K_PINNED = stringPreferencesKey("pinnedVersion")

    /** Une seule bascule à la fois (réception, retour arrière, suppression). */
    private val mutex = Mutex()

    fun activeDir(context: Context): File = File(context.filesDir, DIR_ACTIVE)
    fun tmpDir(context: Context): File = File(context.filesDir, DIR_TMP)
    fun previousDir(context: Context): File = File(context.filesDir, DIR_PREVIOUS)

    /** Informations à jour (flux du DataStore). */
    fun flow(context: Context): Flow<IaModelInfo> = context.applicationContext.iaModelStore.data.map { p ->
        IaModelInfo(
            enabled = p[K_ENABLED] ?: true,
            version = p[K_VERSION],
            sha256 = p[K_SHA],
            speciesCount = p[K_COUNT] ?: 0,
            previousVersion = p[K_PREV_VERSION],
            previousSpeciesCount = p[K_PREV_COUNT] ?: 0,
            previousBytes = p[K_PREV_BYTES] ?: 0L,
            pinnedVersion = p[K_PINNED],
        )
    }

    /** Lecture ponctuelle (valeurs par défaut en cas d'erreur). */
    suspend fun info(context: Context): IaModelInfo = runCatching { flow(context).first() }.getOrDefault(IaModelInfo())

    /** Version installée par Connect (null si aucune, ou si filesDir/ia/ est absent ou incomplet). */
    suspend fun installedVersion(context: Context): String? {
        val v = info(context).version ?: return null
        return v.takeIf { withContext(Dispatchers.IO) { IaAssets.installedDir(context) } != null }
    }

    /** Interrupteur « Identification par photo ». */
    suspend fun setEnabled(context: Context, on: Boolean) {
        runCatching { context.applicationContext.iaModelStore.edit { it[K_ENABLED] = on } }
    }

    /** Ne plus fixer de version : la prochaine synchro installera la plus récente. */
    suspend fun unpin(context: Context) {
        runCatching { context.applicationContext.iaModelStore.edit { it.remove(K_PINNED) } }
    }

    /**
     * Bascule vers la version reçue dans filesDir/ia_tmp/ (déjà vérifiée) :
     * ia_precedent supprimé, ia → ia_precedent, ia_tmp → ia, puis versions mémorisées.
     * @param exact true = version choisie à la main (elle devient la version fixée)
     */
    suspend fun commitNew(context: Context, version: String, sha256: String, speciesCount: Int, exact: Boolean) = mutex.withLock {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            val active = activeDir(app)
            val previous = previousDir(app)
            val tmp = tmpDir(app)
            val old = info(app)
            // Une seule version précédente : la plus ancienne disparaît.
            previous.deleteRecursively()
            val keepOld = old.version != null && IaAssets.installedDir(app) != null
            if (active.exists() && (!keepOld || !active.renameTo(previous))) active.deleteRecursively()
            if (!tmp.renameTo(active)) throw IOException("Bascule vers le nouveau modèle impossible")
            val prevBytes = if (keepOld) dirSize(previous) else 0L
            app.iaModelStore.edit { p ->
                if (keepOld) {
                    p[K_PREV_VERSION] = old.version!!
                    val oldSha = old.sha256
                    if (oldSha != null) p[K_PREV_SHA] = oldSha else p.remove(K_PREV_SHA)
                    p[K_PREV_COUNT] = old.speciesCount
                    p[K_PREV_BYTES] = prevBytes
                } else {
                    p.remove(K_PREV_VERSION); p.remove(K_PREV_SHA); p.remove(K_PREV_COUNT); p.remove(K_PREV_BYTES)
                }
                p[K_VERSION] = version
                p[K_SHA] = sha256
                p[K_COUNT] = speciesCount
                p[K_BYTES] = dirSize(active)
                if (exact) p[K_PINNED] = version else p.remove(K_PINNED)
            }
        }
        IaAssets.invalidate()
    }

    /**
     * Revient au modèle précédent (échange des dossiers ia ↔ ia_precedent : le modèle quitté reste en secours).
     * La version restaurée devient la version fixée (la synchro ne la remplace pas toute seule).
     * @param reason motif inscrit au journal (retour automatique), null = demande de l'utilisateur
     * @return true si la bascule a eu lieu
     */
    suspend fun restorePrevious(context: Context, reason: String?): Boolean = mutex.withLock {
        val app = context.applicationContext
        val ok = withContext(Dispatchers.IO) {
            val old = info(app)
            val prevVersion = old.previousVersion ?: return@withContext false
            val active = activeDir(app)
            val previous = previousDir(app)
            if (!File(previous, "champi-ia.onnx").isFile || !File(previous, "labels.json").isFile) return@withContext false
            val swap = File(app.filesDir, DIR_SWAP).apply { deleteRecursively() }
            val hadActive = active.exists() && old.version != null
            if (active.exists() && !active.renameTo(swap)) return@withContext false
            if (!previous.renameTo(active)) {
                swap.renameTo(active) // remise en place
                return@withContext false
            }
            if (hadActive) swap.renameTo(previous) else swap.deleteRecursively()
            app.iaModelStore.edit { p ->
                // Valeurs lues AVANT l'échange.
                val curSha = p[K_SHA]
                val curBytes = p[K_BYTES] ?: 0L
                val prevSha = p[K_PREV_SHA]
                // La précédente devient l'active (et la version fixée).
                p[K_VERSION] = prevVersion
                if (prevSha != null) p[K_SHA] = prevSha else p.remove(K_SHA)
                p[K_COUNT] = old.previousSpeciesCount
                p[K_BYTES] = old.previousBytes
                p[K_PINNED] = prevVersion
                // L'active quittée devient la précédente.
                if (hadActive) {
                    p[K_PREV_VERSION] = old.version!!
                    if (curSha != null) p[K_PREV_SHA] = curSha else p.remove(K_PREV_SHA)
                    p[K_PREV_COUNT] = old.speciesCount
                    p[K_PREV_BYTES] = curBytes
                } else {
                    p.remove(K_PREV_VERSION); p.remove(K_PREV_SHA); p.remove(K_PREV_COUNT); p.remove(K_PREV_BYTES)
                }
            }
            true
        }
        if (ok) {
            if (reason != null) ErrorLogger.log("IA", "Retour automatique au modèle précédent : $reason")
            IaAssets.invalidate()
        }
        ok
    }

    /** Supprime le modèle précédent (libère la place). */
    suspend fun deletePrevious(context: Context) = mutex.withLock {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            previousDir(app).deleteRecursively()
            app.iaModelStore.edit { p ->
                p.remove(K_PREV_VERSION); p.remove(K_PREV_SHA); p.remove(K_PREV_COUNT); p.remove(K_PREV_BYTES)
            }
        }
    }

    /** Vrai si un modèle précédent complet est présent sur le téléphone. */
    fun hasPreviousFiles(context: Context): Boolean =
        previousDir(context).let { File(it, "champi-ia.onnx").isFile && File(it, "labels.json").isFile }

    /** Statistiques du modèle précédent (stats.json, sinon rapport.txt). À appeler hors du fil principal. */
    fun previousStats(context: Context, version: String?): IaStats? {
        val dir = previousDir(context)
        if (!dir.isDirectory) return null
        fun text(name: String) = runCatching { File(dir, name).takeIf { it.isFile }?.readText() }.getOrNull()
        return IaStats.from(text("stats.json"), text("rapport.txt"), null, File(dir, "champi-ia.onnx").length(), version)
    }

    /** Taille totale d'un dossier (octets). */
    fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
