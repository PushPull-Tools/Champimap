package fr.pushpull.champimap.feature.groupe

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Photo
import fr.pushpull.champimap.core.db.PhotoOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Partage d'un spot entre téléphones, sans Internet :
 *  - par QR code : le lieu seul, sans photos (texte « CHAMPIMAP-S1:{json} ») ;
 *  - par fichier `.champi` : ZIP contenant `champi.json` + `photos/<id>.jpg`, chiffré
 *    (AES-GCM, code court donné de vive voix, voir ChampiCrypto) ou non, envoyé par le
 *    partage Android (Quick Share, Bluetooth, messagerie…).
 *  - import : lecture du fichier ou du QR, aperçu, puis fusion (« la plus récente gagne »).
 *
 * Les spots gardent leur identifiant : un spot partagé deux fois est mis à jour, pas dupliqué.
 * Les récoltes et visites partagées sont détachées des sorties (le destinataire ne les a pas).
 */

/** Contenu d'un partage de spot (le JSON du fichier ou du QR code). */
@Serializable
data class SharedSpot(
    val v: Int = 1,
    val spotId: String,
    val withHistory: Boolean = false,
    val createdAt: Long = 0L,
    /** Qui partage (prénom). */
    val from: PersonDto? = null,
    val bundle: SyncBundle,
)

/** Un partage prêt à importer : contenu + photos extraites (identifiant → fichier temporaire). */
class SharedPackage(val shared: SharedSpot, val photoFiles: Map<String, File>) {
    /** Le spot partagé (toujours présent dans un partage valide). */
    val spot: SpotDto? get() = shared.bundle.spots.firstOrNull { it.id == shared.spotId }
}

object SpotShare {

    private const val QR_PREFIX = "CHAMPIMAP-S1:"
    private const val JSON_ENTRY = "champi.json"
    private const val PHOTO_DIR = "photos/"

    /** Limites de sécurité à l'import (fichier abîmé ou piégé). */
    private const val MAX_JSON = 20L * 1024 * 1024
    private const val MAX_PHOTO = 30L * 1024 * 1024
    private const val MAX_TOTAL = 400L * 1024 * 1024

    private val json get() = Proto.json

    /** Dossier des fichiers partagés (déclaré dans le FileProvider : cache/partages/). */
    private fun sharesDir(context: Context) = File(context.cacheDir, "partages").apply { mkdirs() }

    /** Dossier de travail de l'import (vidé à chaque nouvel import). */
    private fun importDir(context: Context) = File(context.cacheDir, "import")

    // =============================================================================== Préparation

    /** Données à partager pour un spot : lieu seul, ou lieu + historique ; et ses photos. */
    private class Prepared(val shared: SharedSpot, val photos: List<Photo>)

    /** Rassemble le spot (et son historique si demandé) depuis la base. */
    private suspend fun prepare(spotId: String, withHistory: Boolean): Prepared? {
        val dao = AppGraph.db.syncDao()
        val spot = dao.spot(spotId) ?: return null
        val me = GroupRepository.me()

        val positions = if (withHistory) dao.positionsOfSpots(listOf(spotId)) else emptyList()
        val visits = if (withHistory) dao.visitsOfSpot(spotId) else emptyList()
        val recoltes = if (withHistory) dao.recoltesOfSpot(spotId).filter { it.deletedAt == null } else emptyList()
        val items = if (withHistory && recoltes.isNotEmpty()) {
            dao.itemsOfRecoltes(recoltes.map { it.id }).filter { it.deletedAt == null }
        } else emptyList()
        val photoOwners = listOf(spotId) + items.map { it.id }
        val photos = dao.photosOfOwners(photoOwners)
            .filter { it.deletedAt == null && (withHistory || it.ownerType == PhotoOwner.SPOT) }
            .filter { File(it.path).exists() }

        // Prénoms des auteurs (pour « saisi par… » chez le destinataire).
        val authorIds = (listOf(spot.authorId, me.id) + recoltes.map { it.authorId } +
            items.map { it.authorId } + visits.map { it.authorId } + photos.map { it.authorId }).distinct()
        val persons = dao.persons(authorIds)

        val bundle = SyncBundle(
            persons = persons.map { it.toDto() },
            spots = listOf(spot.toDto().copy(deletedAt = null)),
            positions = positions.map { it.toDto() },
            visits = visits.map { it.toDto().copy(sortieId = "") },
            recoltes = recoltes.map { it.toDto().copy(sortieId = "") },
            items = items.map { it.toDto() },
            photos = photos.map { it.toDto() },
        )
        val shared = SharedSpot(
            spotId = spotId, withHistory = withHistory, createdAt = System.currentTimeMillis(),
            from = me.toDto(), bundle = bundle,
        )
        return Prepared(shared, photos)
    }

    /** Nombre de photos qui partiraient avec le fichier (pour l'écran de partage). */
    suspend fun photoCount(spotId: String, withHistory: Boolean): Int =
        withContext(Dispatchers.IO) { prepare(spotId, withHistory)?.photos?.size ?: 0 }

    // =============================================================================== QR code

    /** Texte du QR code d'un spot : lieu seul, sans photos, note raccourcie. Null si spot introuvable. */
    suspend fun qrText(spotId: String): String? = withContext(Dispatchers.IO) {
        val p = prepare(spotId, withHistory = false) ?: return@withContext null
        val b = p.shared.bundle
        val small = p.shared.copy(
            bundle = SyncBundle(
                persons = b.persons.filter { it.id == p.shared.from?.id },
                spots = b.spots.map { it.copy(note = it.note?.take(200), repere = it.repere?.take(200)) },
            ),
        )
        QR_PREFIX + json.encodeToString(SharedSpot.serializer(), small)
    }

    /** Lit un QR code de spot ; null si ce n'en est pas un. */
    fun parseQr(text: String?): SharedPackage? {
        if (text == null || !text.startsWith(QR_PREFIX)) return null
        val shared = runCatching { json.decodeFromString(SharedSpot.serializer(), text.removePrefix(QR_PREFIX)) }.getOrNull()
            ?: return null
        // Un QR ne transporte jamais de photos.
        val clean = shared.copy(bundle = shared.bundle.copy(photos = emptyList()))
        return SharedPackage(clean, emptyMap()).takeIf { it.spot != null }
    }

    // =============================================================================== Fichier .champi

    /**
     * Fabrique le fichier `.champi` à partager.
     * @param keepGps false (par défaut) = photos réencodées sans position GPS ni autres métadonnées.
     * @param code code de chiffrement, ou null pour un fichier non chiffré.
     */
    suspend fun buildFile(
        context: Context,
        spotId: String,
        withHistory: Boolean,
        keepGps: Boolean,
        code: String?,
    ): File = withContext(Dispatchers.IO) {
        val p = prepare(spotId, withHistory) ?: throw IOException("Spot introuvable")
        val dir = sharesDir(context)
        // Ménage : les anciens partages n'ont plus d'utilité.
        dir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date())
        val zip = File(dir, "spot-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry(JSON_ENTRY))
            out.write(json.encodeToString(SharedSpot.serializer(), p.shared).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            for (photo in p.photos) {
                val f = File(photo.path)
                val bytes: ByteArray = if (keepGps) {
                    // Choix de l'utilisateur : photo d'origine, métadonnées comprises.
                    runCatching { f.readBytes() }.getOrNull() ?: continue
                } else {
                    // Réencodage sans métadonnées. Si l'image est illisible, on ne l'envoie PAS
                    // (l'original contient peut-être la position GPS).
                    val buf = java.io.ByteArrayOutputStream()
                    if (!PhotoClean.writeWithoutMetadata(f, buf)) continue
                    buf.toByteArray()
                }
                out.putNextEntry(ZipEntry("$PHOTO_DIR${photo.id}.jpg"))
                out.write(bytes)
                out.closeEntry()
            }
        }

        val result = File(dir, "champimap-spot-$stamp.champi")
        if (code != null) {
            ChampiCrypto.encryptFile(zip, result, code)
            zip.delete()
        } else if (!zip.renameTo(result)) {
            zip.copyTo(result, overwrite = true)
            zip.delete()
        }
        result
    }

    /** Ouvre le partage Android (Quick Share, Bluetooth, messagerie…) pour ce fichier. */
    fun shareFile(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Spot Champimap")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Partager le spot").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    // =============================================================================== Import

    /** Résultat de la lecture d'un fichier reçu. */
    sealed class Received {
        /** Fichier chiffré : il faut le code (le fichier est copié dans `file`). */
        class NeedsCode(val file: File) : Received()

        /** Fichier lisible : aperçu possible. */
        class Ready(val pkg: SharedPackage) : Received()
    }

    /** Copie le fichier reçu (Uri) dans le dossier d'import et détecte s'il est chiffré. */
    suspend fun receive(context: Context, uri: Uri): Received = withContext(Dispatchers.IO) {
        val dir = importDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val copy = File(dir, "recu.champi")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Fichier illisible")
        input.use { inp ->
            copy.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_TOTAL) throw IOException("Fichier trop gros")
                    out.write(buf, 0, n)
                }
            }
        }
        val head = copy.inputStream().use { ChampiCrypto.peek(it, ChampiCrypto.MAGIC.size) }
        if (ChampiCrypto.isEncrypted(head)) Received.NeedsCode(copy) else Received.Ready(unzip(context, copy))
    }

    /**
     * Déchiffre un fichier reçu avec le code donné.
     * @throws ChampiCrypto.WrongCodeException si le code est faux.
     */
    suspend fun decrypt(context: Context, file: File, code: String): SharedPackage = withContext(Dispatchers.IO) {
        val zip = File(importDir(context), "recu.zip")
        file.inputStream().use { ChampiCrypto.decryptStream(it, zip, code) }
        unzip(context, zip)
    }

    /** Lit le ZIP : le JSON et les photos (seuls les noms attendus sont acceptés). */
    private fun unzip(context: Context, zip: File): SharedPackage {
        val photoDir = File(importDir(context), "photos").apply { mkdirs() }
        var shared: SharedSpot? = null
        val photos = HashMap<String, File>()
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                when {
                    name == JSON_ENTRY -> {
                        val bytes = readLimited(zin, MAX_JSON)
                        total += bytes.size
                        shared = json.decodeFromString(SharedSpot.serializer(), String(bytes, Charsets.UTF_8))
                    }
                    name.startsWith(PHOTO_DIR) && name.endsWith(".jpg") -> {
                        val id = name.removePrefix(PHOTO_DIR).removeSuffix(".jpg")
                        // Identifiant « propre » uniquement (pas de « ../ » : aucune écriture hors du dossier).
                        if (GroupRepository.isSafeId(id)) {
                            val bytes = readLimited(zin, MAX_PHOTO)
                            total += bytes.size
                            val f = File(photoDir, "$id.jpg")
                            f.writeBytes(bytes)
                            photos[id] = f
                        }
                    }
                    // Tout autre contenu est ignoré.
                }
                if (total > MAX_TOTAL) throw IOException("Fichier trop gros")
                zin.closeEntry()
            }
        }
        val s = shared ?: throw IOException("Ce fichier n'est pas un spot Champimap")
        val pkg = SharedPackage(s, photos)
        if (pkg.spot == null) throw IOException("Ce fichier n'est pas un spot Champimap")
        return pkg
    }

    /** Lit une entrée de ZIP en refusant de dépasser `max` octets. */
    private fun readLimited(zin: ZipInputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zin.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Fichier trop gros")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Ajoute le spot reçu à mes spots (fusion « la plus récente gagne ») et copie ses photos.
     * @return l'identifiant du spot, pour l'ouvrir.
     */
    suspend fun importPackage(context: Context, pkg: SharedPackage): String = withContext(Dispatchers.IO) {
        val b = pkg.shared.bundle
        // Les photos sans fichier dans le partage ne sont pas créées (fiche sans image).
        GroupRepository.merge(b.copy(photos = emptyList()))
        for (dto in b.photos) {
            val f = pkg.photoFiles[dto.id] ?: continue
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            GroupRepository.savePhoto(context, dto, bytes)
        }
        // Import volontaire : si ce spot était dans ma corbeille, il en ressort.
        val dao = AppGraph.db.syncDao()
        dao.spot(pkg.shared.spotId)?.let { s ->
            if (s.deletedAt != null) dao.upsertSpot(s.copy(deletedAt = null, updatedAt = System.currentTimeMillis()))
        }
        importDir(context).deleteRecursively()
        pkg.shared.spotId
    }

    /** Efface les fichiers temporaires d'un import abandonné. */
    fun clearImport(context: Context) {
        runCatching { importDir(context).deleteRecursively() }
    }
}

/** Mémoire d'un QR code de spot scanné ailleurs (écran Rejoindre), lu par l'écran d'import. */
object PendingSpotQr {
    @Volatile var text: String? = null
}
