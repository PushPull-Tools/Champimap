package fr.pushpull.champimap.feature.spot

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Identification hors ligne avec le modèle ONNX (ONNX Runtime, sur le téléphone).
 *  1. Session chargée une fois par version du modèle (IaAssets.revision), en arrière-plan : modèle
 *     livré par Champimap Connect (filesDir/ia/), sinon celui des assets de l'APK (compatibilité).
 *     Nouvelle version reçue → session fermée et rechargée. Échec de chargement d'un modèle reçu
 *     (session impossible, labels incohérents) → retour automatique au modèle précédent (journal).
 *  2. Prétraitement IDENTIQUE à l'entraînement : rotation EXIF, petit côté à 316 px,
 *     recadrage central 288 × 288, normalisation ImageNet (mean/std), ordre NCHW (RGB).
 *  3. Softmax sur les scores, puis les 5 meilleures espèces présentes dans le catalogue
 *     (les genres masqués — lichens, rouilles… — ne sont pas en base, donc jamais proposés).
 * Toute erreur est inscrite au journal (sans donnée personnelle) et donne une liste vide :
 * l'écran retombe alors silencieusement sur la liste des espèces.
 */
object OnnxSpeciesIdentifier : SpeciesIdentifier {
    /** Côté court après redimensionnement (Resize(316) de l'entraînement). */
    private const val RESIZE_SHORT = 316

    /** Nombre de propositions renvoyées. */
    private const val TOP = 5

    /** Candidats examinés pour garder 5 espèces affichables (certaines sont masquées). */
    private const val CANDIDATS = 25

    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var labels: IaLabels? = null

    /** Vrai après un échec de chargement : on ne réessaie pas à chaque photo (jusqu'au prochain modèle). */
    @Volatile private var loadFailed = false

    /** Version du modèle (IaAssets.revision) à laquelle correspondent session / labels / loadFailed. */
    private var loadedRevision = -1

    /** Vrai si un modèle est disponible (Connect ou APK). */
    fun isAvailable(context: Context): Boolean = IaAssets.modelAvailable(context)

    /**
     * Charge la session tout de suite (après réception d'un modèle) : vérifie qu'il fonctionne,
     * avec retour automatique au précédent sinon. Vrai si un modèle utilisable est chargé.
     */
    suspend fun preload(context: Context): Boolean = withContext(Dispatchers.Default) { ensureSession(context) != null }

    override suspend fun identify(photo: File): List<Pair<Species, Float>> = withContext(Dispatchers.Default) {
        try {
            val context = AppGraph.appContext
            val (s, l) = ensureSession(context) ?: return@withContext emptyList()
            val input = preprocess(photo, l) ?: return@withContext emptyList()
            val probs = infer(s, input, l.inputSize)
            topSpecies(probs, l.species)
        } catch (e: Throwable) {
            ErrorLogger.log("IA", "Identification impossible", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Chargement de la session
    // ---------------------------------------------------------------------------------------

    /**
     * Session et labels de la version actuelle du modèle (null si absent ou inutilisable).
     * Un modèle reçu de Connect qui ne se charge pas est remplacé par le précédent, puis on réessaie une fois.
     */
    private suspend fun ensureSession(context: Context): Pair<OrtSession, IaLabels>? = mutex.withLock {
        for (attempt in 0..1) {
            val rev = IaAssets.revision.value
            if (loadedRevision != rev) {
                // Nouvelle version (ou retour arrière) : l'ancienne session est fermée.
                runCatching { session?.close() }
                session = null
                labels = null
                loadFailed = false
                loadedRevision = rev
            }
            val s = session
            val l = labels
            if (s != null && l != null) return@withLock s to l
            if (loadFailed) return@withLock null
            try {
                val created = load(context)
                session = created.first
                labels = created.second
                return@withLock created
            } catch (e: Throwable) {
                loadFailed = true
                ErrorLogger.log("IA", "Chargement du modèle d'identification impossible", e)
                // Modèle reçu de Connect en échec : retour au précédent (s'il existe), puis nouvel essai.
                val canRestore = attempt == 0 && IaAssets.isFromConnect(context) && IaModel.hasPreviousFiles(context)
                if (!canRestore || !IaModel.restorePrevious(context, e.message ?: e.javaClass.simpleName)) return@withLock null
            }
        }
        null
    }

    /** Charge labels + session et vérifie leur cohérence (nombre de sorties = nombre d'espèces). */
    private suspend fun load(context: Context): Pair<OrtSession, IaLabels> {
        val lab = withContext(Dispatchers.IO) { IaAssets.labels(context) } ?: error("labels.json absent ou invalide")
        require(lab.species.isNotEmpty() && lab.mean.size == 3 && lab.std.size == 3) { "labels.json incomplet" }
        val modelFile = withContext(Dispatchers.IO) { IaAssets.modelFile(context) } ?: error("champi-ia.onnx absent")
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = env.createSession(modelFile.absolutePath, options)
        // Sortie [1, N] : N doit valoir le nombre d'espèces de labels.json (dimension inconnue = acceptée).
        val outputs = (created.outputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape?.lastOrNull() ?: -1L
        if (outputs > 0 && outputs != lab.species.size.toLong()) {
            runCatching { created.close() }
            error("Modèle incohérent : $outputs sorties pour ${lab.species.size} espèces")
        }
        return created to lab
    }

    // ---------------------------------------------------------------------------------------
    // Prétraitement (identique à l'entraînement)
    // ---------------------------------------------------------------------------------------

    /** Photo → tenseur NCHW normalisé [1,3,taille,taille] ; null si la photo est illisible. */
    private fun preprocess(photo: File, l: IaLabels): FloatBuffer? {
        val size = l.inputSize
        val bitmap = decodeOriented(photo, RESIZE_SHORT) ?: return null
        val resized = resizeShortSide(bitmap, RESIZE_SHORT)
        // Recadrage central (CenterCrop) : arrondi comme torchvision.
        val left = ((resized.width - size) / 2.0).roundToInt().coerceAtLeast(0)
        val top = ((resized.height - size) / 2.0).roundToInt().coerceAtLeast(0)
        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, left, top, size, size)
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()

        val plane = size * size
        val buffer = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val (mr, mg, mb) = l.mean
        val (sr, sg, sb) = l.std
        // Plan R, puis plan G, puis plan B (NCHW).
        for (i in 0 until plane) buffer.put(i, (((pixels[i] shr 16) and 0xFF) / 255f - mr) / sr)
        for (i in 0 until plane) buffer.put(plane + i, (((pixels[i] shr 8) and 0xFF) / 255f - mg) / sg)
        for (i in 0 until plane) buffer.put(2 * plane + i, ((pixels[i] and 0xFF) / 255f - mb) / sb)
        buffer.rewind()
        return buffer
    }

    /**
     * Décode la photo en mémoire réduite (sous-échantillonnage du décodeur, petit côté gardé ≥ 2 × cible)
     * puis applique l'orientation EXIF (rotation / miroir) pour voir l'image comme à l'écran.
     */
    private fun decodeOriented(file: File, minShort: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val short = min(bounds.outWidth, bounds.outHeight)
        while (short / (sample * 2) >= minShort * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return raw
        }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /**
     * Redimensionne pour que le petit côté fasse `target` px (l'autre côté proportionnel, tronqué
     * comme torchvision). Réductions successives par 2 d'abord : rendu lissé proche de l'entraînement.
     */
    private fun resizeShortSide(src: Bitmap, target: Int): Bitmap {
        var current = src
        while (min(current.width, current.height) >= target * 4) {
            val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== src) current.recycle()
            current = half
        }
        val short = min(current.width, current.height)
        val long = max(current.width, current.height)
        val newLong = (target.toLong() * long / short).toInt()
        val (w, h) = if (current.width <= current.height) target to newLong else newLong to target
        if (current.width == w && current.height == h) return current
        val out = Bitmap.createScaledBitmap(current, w, h, true)
        if (current !== src && current !== out) current.recycle()
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Inférence et résultats
    // ---------------------------------------------------------------------------------------

    /** Lance le modèle et renvoie les probabilités (softmax des scores). */
    private fun infer(s: OrtSession, input: FloatBuffer, size: Int): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val inputName = s.inputNames.firstOrNull() ?: "image"
        val logits: FloatArray = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            s.run(mapOf(inputName to tensor)).use { result ->
                // Sortie « scores » : float[1][N] côté Java.
                @Suppress("UNCHECKED_CAST")
                (result.get(0).value as Array<FloatArray>)[0]
            }
        }
        return softmax(logits)
    }

    /** Softmax stable (on retire le maximum avant l'exponentielle). */
    private fun softmax(logits: FloatArray): FloatArray {
        val maxV = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { exp((logits[it] - maxV).toDouble()).toFloat() }
        val sum = exps.sum().takeIf { it > 0f } ?: 1f
        return FloatArray(exps.size) { exps[it] / sum }
    }

    /** 5 meilleures espèces présentes dans le catalogue, avec leur probabilité (meilleure en premier). */
    private suspend fun topSpecies(probs: FloatArray, ids: List<String>): List<Pair<Species, Float>> {
        val best = probs.indices.filter { it < ids.size }.sortedByDescending { probs[it] }.take(CANDIDATS)
        val candidateIds = best.map { ids[it] }
        val byId = withContext(Dispatchers.IO) { AppGraph.db.speciesDao().byIds(candidateIds) }.associateBy { it.id }
        return best.mapNotNull { i -> byId[ids[i]]?.let { it to probs[i] } }
            // Sécurité : une espèce ajoutée par le modèle (INCONNU) d'un genre masqué n'est jamais proposée.
            .filter { it.first.edibility != Edibility.INCONNU || !isHiddenGenus(it.first.nameLatin) }
            .take(TOP)
    }
}
