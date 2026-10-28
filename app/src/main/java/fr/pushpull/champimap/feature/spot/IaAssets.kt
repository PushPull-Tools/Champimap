package fr.pushpull.champimap.feature.spot

import android.content.Context
import fr.pushpull.champimap.core.ErrorLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/*
 * Fichiers du modèle d'identification (paquet « ia-vN ») :
 *   champi-ia.onnx   modèle (EfficientNet-B2, entrée [1,3,288,288], sortie [1,N] logits)
 *   labels.json      { inputSize, mean, std, species: [ids] } — sortie i = species[i]
 *   especes_ia.json  espèces connues du modèle (noms latins + français iNaturalist)
 *   fiches.json + fiches/   photos des fiches espèces (licence et crédit)
 *   credits.csv.gz   crédits de toutes les photos d'entraînement
 *   rapport.txt      précision mesurée (top-1, top-5)
 *   stats.json       statistiques publiées avec la release (ajouté par Champimap Connect)
 * Origine, dans cet ordre :
 *   1. filesDir/ia/ : modèle livré par Champimap Connect (voir IaModel) ;
 *   2. assets/ia/ de l'APK (anciennes versions qui embarquaient le modèle : compatibilité) ;
 *   3. sinon « modèle absent » : l'appli fonctionne normalement, sans identification.
 * Les chemins restent écrits « ia/xxx » (constantes ci-dessous) quelle que soit l'origine.
 * `revision` augmente à chaque changement de modèle : les lecteurs (session ONNX, catalogue, photos
 * des fiches) s'en servent pour recharger.
 */

/** Réglages de prétraitement et liste des sorties du modèle (labels.json). */
@Serializable
internal data class IaLabels(
    val inputSize: Int = 288,
    val mean: List<Float> = listOf(0.485f, 0.456f, 0.406f),
    val std: List<Float> = listOf(0.229f, 0.224f, 0.225f),
    val species: List<String> = emptyList(),
)

/** Accès aux fichiers du modèle (lectures mises en cache, invalidées à chaque nouvelle version). */
object IaAssets {
    const val DIR = "ia"
    const val MODEL = "ia/champi-ia.onnx"
    const val LABELS = "ia/labels.json"
    const val SPECIES = "ia/especes_ia.json"
    const val FICHES_JSON = "ia/fiches.json"
    const val FICHES_DIR = "ia/fiches"
    const val CREDITS = "ia/credits.csv.gz"
    const val RAPPORT = "ia/rapport.txt"
    const val STATS = "ia/stats.json"

    /** Adresse Coil des fichiers d'assets. */
    private const val ASSET_URI = "file:///android_asset/"

    /** Copie du modèle des assets (ONNX Runtime lit un fichier, pas un flux d'assets). */
    private const val APK_COPY_DIR = "ia_apk"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Origine des fichiers. */
    private sealed interface Source {
        data class Folder(val root: File) : Source
        data object Apk : Source
        data object None : Source
    }

    private val _revision = MutableStateFlow(0)

    /** Numéro de version interne du modèle chargé : change à chaque installation / retour arrière. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile private var sourceCache: Source? = null
    @Volatile private var apkListing: Set<String>? = null
    @Volatile private var labelsCache: IaLabels? = null

    /** À appeler après un changement de modèle : vide les caches et prévient les lecteurs. */
    fun invalidate() {
        sourceCache = null
        labelsCache = null
        _revision.update { it + 1 }
    }

    // ---------------------------------------------------------------------------------------
    // Origine des fichiers
    // ---------------------------------------------------------------------------------------

    /** Chemin relatif au dossier du modèle (« ia/labels.json » → « labels.json »). */
    private fun rel(path: String): String = path.removePrefix("$DIR/")

    /**
     * filesDir/ia/ s'il contient un modèle complet, sinon null. Un dossier incomplet est un reste
     * de l'ancienne copie du modèle des assets (champi-ia.onnx seul) : il est supprimé.
     */
    fun installedDir(context: Context): File? {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory) return null
        if (File(dir, rel(MODEL)).isFile && File(dir, rel(LABELS)).isFile) return dir
        dir.deleteRecursively()
        return null
    }

    /** Origine actuelle (mise en cache jusqu'au prochain invalidate()). */
    private fun source(context: Context): Source {
        sourceCache?.let { return it }
        val dir = installedDir(context)
        val s = when {
            dir != null -> Source.Folder(dir)
            apkHas(context, MODEL) && apkHas(context, LABELS) -> Source.Apk
            else -> Source.None
        }
        sourceCache = s
        return s
    }

    /** Noms des fichiers présents dans assets/ia/ (lu une seule fois : l'APK ne change pas). */
    private fun apkHas(context: Context, path: String): Boolean {
        val listing = apkListing ?: runCatching { context.assets.list(DIR)?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
            .also { apkListing = it }
        return rel(path) in listing
    }

    /** Vrai si un modèle est disponible (Connect ou APK). */
    fun modelAvailable(context: Context): Boolean = source(context) != Source.None

    /** Vrai si le modèle actif vient de Champimap Connect (filesDir/ia/). */
    fun isFromConnect(context: Context): Boolean = source(context) is Source.Folder

    /** Vrai si l'APK embarque encore un modèle (anciennes versions). */
    fun apkHasModel(context: Context): Boolean = apkHas(context, MODEL) && apkHas(context, LABELS)

    // ---------------------------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------------------------

    /** Vrai si un fichier du modèle existe (chemin complet « ia/xxx »). */
    fun exists(context: Context, path: String): Boolean = when (val s = source(context)) {
        is Source.Folder -> File(s.root, rel(path)).isFile
        Source.Apk -> if ('/' in rel(path)) runCatching { context.assets.open(path).use { true } }.getOrDefault(false) else apkHas(context, path)
        Source.None -> false
    }

    /** Ouvre un fichier du modèle (null s'il est absent). L'appelant ferme le flux. */
    fun open(context: Context, path: String): InputStream? = try {
        when (val s = source(context)) {
            is Source.Folder -> File(s.root, rel(path)).takeIf { it.isFile }?.inputStream()
            Source.Apk -> if (exists(context, path)) context.assets.open(path) else null
            Source.None -> null
        }
    } catch (e: Exception) {
        ErrorLogger.log("IA", "Ouverture de $path impossible", e)
        null
    }

    /** Lit un fichier texte du modèle (null s'il est absent ou illisible). */
    fun readText(context: Context, path: String): String? {
        val input = open(context, path) ?: return null
        return try {
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ErrorLogger.log("IA", "Lecture de $path impossible", e)
            null
        }
    }

    /** Noms des fichiers d'un sous-dossier du modèle (ex. FICHES_DIR). */
    fun list(context: Context, dirPath: String): Set<String> = when (val s = source(context)) {
        is Source.Folder -> File(s.root, rel(dirPath)).list()?.toSet() ?: emptySet()
        Source.Apk -> runCatching { context.assets.list(dirPath)?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        Source.None -> emptySet()
    }

    /** Ce que Coil affiche pour un fichier du modèle : un File, ou une adresse « file:///android_asset/… ». */
    fun imageModel(context: Context, path: String): Any? = when (val s = source(context)) {
        is Source.Folder -> File(s.root, rel(path))
        Source.Apk -> "$ASSET_URI$path"
        Source.None -> null
    }

    /**
     * Fichier .onnx lisible par ONNX Runtime (null sans modèle). Pour l'APK : copie dans filesDir/ia_apk/
     * si elle manque ou a changé de taille (fichier temporaire puis renommage : jamais de modèle tronqué).
     * À appeler hors du fil principal.
     */
    fun modelFile(context: Context): File? = when (val s = source(context)) {
        is Source.Folder -> File(s.root, rel(MODEL)).takeIf { it.isFile }
        Source.Apk -> copyApkModel(context)
        Source.None -> null
    }

    private fun copyApkModel(context: Context): File {
        val dir = File(context.filesDir, APK_COPY_DIR).apply { mkdirs() }
        val target = File(dir, "champi-ia.onnx")
        // Taille dans l'APK (le .onnx n'est pas compressé : openFd fonctionne) ; -1 si inconnue.
        val assetSize = runCatching { context.assets.openFd(MODEL).use { it.length } }.getOrDefault(-1L)
        if (target.exists() && target.length() > 0 && (assetSize < 0 || target.length() == assetSize)) return target
        val tmp = File(dir, "champi-ia.onnx.tmp")
        context.assets.open(MODEL).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("Renommage du modèle impossible")
        return target
    }

    /** labels.json décodé (mis en cache), null si absent ou invalide. À appeler hors du fil principal. */
    internal fun labels(context: Context): IaLabels? {
        labelsCache?.let { return it }
        val text = readText(context, LABELS) ?: return null
        return try {
            json.decodeFromString(IaLabels.serializer(), text).also { labelsCache = it }
        } catch (e: Exception) {
            ErrorLogger.log("IA", "labels.json invalide", e)
            null
        }
    }

    /** Identifiants des espèces que le modèle sait reconnaître (vide sans modèle). */
    fun labelIds(context: Context): Set<String> = labels(context)?.species?.toSet() ?: emptySet()

    /** Ligne « Top-1 : … · Top-5 : … » du rapport d'entraînement, si présente. */
    fun accuracyLine(context: Context): String? =
        readText(context, RAPPORT)?.lineSequence()?.firstOrNull { it.contains("Top-1", ignoreCase = true) }?.trim()

    /**
     * Statistiques du modèle actif : stats.json (Connect), sinon rapport.txt + labels.json.
     * Null sans modèle. À appeler hors du fil principal.
     */
    fun stats(context: Context, version: String?): IaStats? {
        if (!modelAvailable(context)) return null
        val modelBytes = (source(context) as? Source.Folder)?.let { File(it.root, rel(MODEL)).length() }
        return IaStats.from(readText(context, STATS), readText(context, RAPPORT), labels(context)?.species?.size, modelBytes, version)
    }
}
