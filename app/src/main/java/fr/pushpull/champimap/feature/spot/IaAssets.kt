package fr.pushpull.champimap.feature.spot

import android.content.Context
import fr.pushpull.champimap.core.ErrorLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Fichiers du modèle d'identification, livrés dans les assets sous assets/ia/ :
 *   champi-ia.onnx   modèle (EfficientNet-B2, entrée [1,3,288,288], sortie [1,N] logits)
 *   labels.json      { inputSize, mean, std, species: [ids] } — sortie i = species[i]
 *   especes_ia.json  espèces connues du modèle (noms latins + français iNaturalist)
 *   fiches.json + fiches/   photos des fiches espèces (licence et crédit)
 *   credits.csv.gz   crédits de toutes les photos d'entraînement
 *   rapport.txt      précision mesurée (top-1, top-5)
 * Ces fichiers ne sont PAS dans git : la CI les télécharge depuis la release GitHub « ia-vN ».
 * Sans eux (compilation locale), l'appli fonctionne normalement, sans identification.
 */

/** Réglages de prétraitement et liste des sorties du modèle (labels.json). */
@Serializable
internal data class IaLabels(
    val inputSize: Int = 288,
    val mean: List<Float> = listOf(0.485f, 0.456f, 0.406f),
    val std: List<Float> = listOf(0.229f, 0.224f, 0.225f),
    val species: List<String> = emptyList(),
)

/** Accès aux fichiers du modèle dans les assets (lectures mises en cache). */
object IaAssets {
    const val DIR = "ia"
    const val MODEL = "ia/champi-ia.onnx"
    const val LABELS = "ia/labels.json"
    const val SPECIES = "ia/especes_ia.json"
    const val FICHES_JSON = "ia/fiches.json"
    const val FICHES_DIR = "ia/fiches"
    const val CREDITS = "ia/credits.csv.gz"
    const val RAPPORT = "ia/rapport.txt"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var dirListing: Set<String>? = null
    @Volatile private var labelsCache: IaLabels? = null

    /** Noms des fichiers présents dans assets/ia/ (lu une seule fois). */
    private fun listing(context: Context): Set<String> =
        dirListing ?: runCatching { context.assets.list(DIR)?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
            .also { dirListing = it }

    /** Vrai si un fichier de assets/ia/ existe (chemin complet « ia/xxx »). */
    fun exists(context: Context, path: String): Boolean = path.substringAfter("$DIR/") in listing(context)

    /** Vrai si le modèle et sa liste de sorties sont livrés avec l'appli. */
    fun modelAvailable(context: Context): Boolean = exists(context, MODEL) && exists(context, LABELS)

    /** Lit un fichier texte des assets (null s'il est absent ou illisible). */
    fun readText(context: Context, path: String): String? {
        if (!exists(context, path)) return null
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ErrorLogger.log("IA", "Lecture de $path impossible", e)
            null
        }
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
}
