package fr.pushpull.champimap.feature.connect

import android.content.Context
import android.net.Uri
import fr.pushpull.champimap.feature.spot.IaAssets
import fr.pushpull.champimap.feature.spot.IaModel
import fr.pushpull.champimap.feature.spot.OnnxSpeciesIdentifier
import fr.pushpull.champimap.feature.spot.SpeciesLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/*
 * Réception du modèle d'identification envoyé par Champimap Connect (aucun accès réseau ici).
 *  1. ia_manifest.json : version « ia-vN », empreinte de l'archive, liste des fichiers (chemin, taille, SHA-256) ;
 *  2. chaque fichier est lu chez Connect (IA_BASE_URI/<version>/<chemin>) et copié dans filesDir/ia_tmp/,
 *     taille et SHA-256 vérifiées au fil de la copie ;
 *  3. fichiers obligatoires présents, labels.json lisible → bascule (IaModel.commitNew : ia → ia_precedent,
 *     ia_tmp → ia), version enregistrée ;
 *  4. test immédiat du nouveau modèle (session ONNX + inférence de contrôle sur une image neutre) :
 *     réussite → ancien modèle supprimé (IaModel.dropBackup) ; échec → nouveau supprimé, ancien remis
 *     (IaModel.rejectNew, journal, message dans l'écran Connect). Puis catalogue rejoué.
 * En cas de problème pendant la copie, rien n'est basculé : le modèle actuel reste en place et ia_tmp est supprimé.
 */
internal object IaModelImport {
    /** Fichiers obligatoires du paquet. */
    private val REQUIRED = listOf("champi-ia.onnx", "labels.json", "especes_ia.json")

    /** Chemin relatif accepté : segments simples (lettres, chiffres, « . _ - »), jamais « .. ». */
    private val SAFE_PATH = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** Garde-fous : 50 000 fichiers, 2 Go au total. */
    private const val MAX_FILES = 50_000
    private const val MAX_TOTAL = 2L * 1024 * 1024 * 1024

    /** Un fichier annoncé par le manifeste. */
    private class ManifestFile(val path: String, val size: Long, val sha256: String)

    /**
     * Installe le modèle décrit par le manifeste. `onStep(texte)` : étape affichée à l'écran.
     * Vrai si le paquet a été reçu (ou était déjà installé). Lève une exception si le paquet est refusé.
     */
    suspend fun install(context: Context, manifestText: String, onStep: (String) -> Unit): Boolean {
        val app = context.applicationContext
        val m = ConnectProtocol.json.parseToJsonElement(manifestText) as? JsonObject ?: throw IOException("Manifeste illisible")
        val version = m.str("version")?.takeIf { ConnectProtocol.IA_VERSION_REGEX.matches(it) }
            ?: throw IOException("Version du modèle invalide")
        val archiveSha = m.str("sha256")?.takeIf { HEX64.matches(it) } ?: throw IOException("Empreinte du modèle absente")
        val exact = m.bool("exact") ?: false
        val files = (m["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { f ->
            val path = f.str("path")?.takeIf { p -> SAFE_PATH.matches(p) && p.split('/').none { it == "." || it == ".." } }
                ?: throw IOException("Chemin refusé dans le manifeste")
            val sha = f.str("sha256")?.takeIf { HEX64.matches(it) } ?: throw IOException("Empreinte absente : $path")
            ManifestFile(path, f.long("size") ?: -1L, sha.lowercase())
        } ?: throw IOException("Liste des fichiers absente")
        if (files.size > MAX_FILES || files.sumOf { it.size.coerceAtLeast(0) } > MAX_TOTAL) throw IOException("Paquet trop volumineux")
        for (r in REQUIRED) if (files.none { it.path == r }) throw IOException("Fichier $r absent du modèle $version")

        // Déjà installé (même version, même empreinte) : rien à copier.
        val current = IaModel.info(app)
        if (current.version == version && current.sha256 == archiveSha && withContext(Dispatchers.IO) { IaAssets.installedDir(app) } != null) {
            // Bascule interrompue (appli fermée pendant le test) : on la termine maintenant.
            if (IaModel.hasPreviousFiles(app)) checkNewModel(app, version, onStep)
            return true
        }

        withContext(Dispatchers.IO) {
            val tmp = IaModel.tmpDir(app)
            tmp.deleteRecursively()
            tmp.mkdirs()
            val root = tmp.canonicalPath + File.separator
            try {
                var lastPct = -1
                files.forEachIndexed { i, f ->
                    val pct = (i + 1) * 100 / files.size
                    if (pct != lastPct) {
                        lastPct = pct
                        onStep("Installation du modèle d'identification $version · $pct %")
                    }
                    val target = File(tmp, f.path)
                    if (!target.canonicalPath.startsWith(root)) throw IOException("Chemin hors du dossier refusé")
                    target.parentFile?.mkdirs()
                    copyVerified(app, fileUri(version, f.path), target, f)
                }
                // labels.json doit être lisible et non vide (vérifié avant la bascule).
                val labels = File(tmp, "labels.json").readText()
                val speciesCount = (ConnectProtocol.json.parseToJsonElement(labels) as? JsonObject)
                    ?.let { it["species"] as? JsonArray }?.size ?: 0
                if (speciesCount == 0) throw IOException("labels.json sans espèce")
                IaModel.commitNew(app, version, archiveSha.lowercase(), speciesCount, exact)
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw e
            }
        }

        // Nouveau modèle testé tout de suite (ancien supprimé ou remis en place), catalogue rejoué.
        checkNewModel(app, version, onStep)
        SpeciesLoader.ensureLoaded(app)
        // Vrai même si le test échoue : le paquet est bien reçu (pas de nouvelle copie à chaque instantané).
        return true
    }

    /**
     * Teste le modèle qui vient d'être basculé. Réussite → ancien modèle supprimé (place libérée).
     * Échec → nouveau supprimé, ancien remis en place, entrée au journal et message dans l'écran Connect.
     */
    private suspend fun checkNewModel(app: Context, version: String, onStep: (String) -> Unit) {
        onStep("Vérification du modèle d'identification $version…")
        val failure = OnnxSpeciesIdentifier.validateInstalled(app)
        if (failure == null) {
            IaModel.dropBackup(app)
            onStep("Modèle d'identification $version installé")
        } else {
            val keptOld = IaModel.rejectNew(app, version, failure)
            onStep(
                if (keptOld) "Le nouveau modèle n'a pas pu être chargé : l'ancien est conservé"
                else "Le nouveau modèle n'a pas pu être chargé",
            )
        }
    }

    /** URI d'un fichier du modèle chez Connect (lecture accordée par préfixe). */
    private fun fileUri(version: String, path: String): Uri =
        Uri.parse(ConnectProtocol.IA_BASE_URI + version + "/" + Uri.encode(path, "/"))

    /** Copie un fichier en vérifiant sa taille et son SHA-256 (fichier refusé sinon). */
    private fun copyVerified(context: Context, uri: Uri, target: File, f: ManifestFile) {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Fichier illisible : ${f.path}")
        input.use { ins ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    total += n
                }
            }
        }
        if (f.size >= 0 && total != f.size) throw IOException("Taille incorrecte : ${f.path}")
        val sha = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (sha != f.sha256) throw IOException("Empreinte incorrecte : ${f.path}")
    }
}
