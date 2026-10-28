package fr.pushpull.champimap.connect.download

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/*
 * Lecteur minimal d'archives .tar.gz (aucune dépendance) pour le paquet du modèle d'identification.
 * Formats gérés : ustar (préfixe de chemin), en-têtes PAX « x » (chemin long ou accentué), noms longs GNU « L ».
 * Seuls les FICHIERS ordinaires et les dossiers sont créés : liens, périphériques… sont ignorés.
 * Sécurité : un chemin absolu, avec « .. », ou qui sortirait du dossier cible fait échouer l'extraction.
 * Garde-fous : 512 Mo par fichier, 2 Go et 50 000 entrées au total.
 */
object TarGzExtractor {
    private const val BLOCK = 512
    private const val MAX_FILE = 512L * 1024 * 1024
    private const val MAX_TOTAL = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 50_000

    /** Fichier extrait : chemin relatif (« fiches/x.webp »), taille et empreinte SHA-256 (hexadécimal). */
    data class Entry(val path: String, val size: Long, val sha256: String)

    /**
     * Extrait `archive` dans `target` (créé si besoin).
     * `onProgress(octets compressés lus, taille de l'archive)` : avancement réel dans le fichier.
     */
    fun extract(archive: File, target: File, onProgress: (Long, Long) -> Unit): List<Entry> {
        target.mkdirs()
        val root = target.canonicalFile
        val total = archive.length()
        val counter = CountingInputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024))
        val entries = ArrayList<Entry>()
        var written = 0L
        GZIPInputStream(counter, 64 * 1024).use { gz ->
            val header = ByteArray(BLOCK)
            var longName: String? = null // nom fourni par un en-tête PAX ou GNU, pour l'entrée suivante
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Extraction annulée")
                if (!readBlock(gz, header)) break // fin du flux sans bloc de fin : toléré
                if (header.all { it.toInt() == 0 }) break // bloc de fin d'archive
                val size = parseSize(header)
                val type = header[156].toInt().toChar()
                when (type) {
                    'x' -> { longName = paxPath(readData(gz, size)) ?: longName; continue }
                    'g' -> { skipData(gz, size); continue }
                    'L' -> { longName = String(readData(gz, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                }
                val name = longName ?: headerName(header)
                longName = null
                when (type) {
                    '0', '\u0000', '7' -> {
                        if (size > MAX_FILE) throw IOException("Fichier trop gros dans l'archive")
                        written += size
                        if (written > MAX_TOTAL) throw IOException("Archive trop volumineuse")
                        if (entries.size >= MAX_ENTRIES) throw IOException("Trop de fichiers dans l'archive")
                        val rel = safePath(name) ?: throw IOException("Chemin refusé dans l'archive : $name")
                        val out = File(root, rel)
                        checkInside(root, out)
                        out.parentFile?.mkdirs()
                        val sha = copyData(gz, size, out)
                        entries += Entry(rel, size, sha)
                    }
                    '5' -> {
                        val rel = safePath(name)
                        if (rel != null) File(root, rel).also { checkInside(root, it) }.mkdirs()
                        skipData(gz, size)
                    }
                    else -> skipData(gz, size) // liens et types spéciaux : jamais créés
                }
                onProgress(counter.count, total)
            }
        }
        onProgress(total, total)
        return entries
    }

    // ------------------------------------------------------------------ chemins

    /**
     * Chemin relatif propre (« ./a/b » → « a/b »), ou null s'il est vide, absolu, contient « .. »,
     * « \ » ou des caractères de contrôle.
     */
    private fun safePath(raw: String): String? {
        var p = raw.trim()
        while (p.startsWith("./")) p = p.substring(2)
        p = p.trimEnd('/')
        if (p.isEmpty() || p == "." || p.startsWith("/") || '\\' in p || p.any { it < ' ' }) return null
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Vérifie que `file` reste dans `root` (dernier rempart contre les chemins piégés). */
    private fun checkInside(root: File, file: File) {
        val c = file.canonicalPath
        if (!c.startsWith(root.path + File.separator)) throw IOException("Chemin hors du dossier cible refusé")
    }

    /** Nom ustar : préfixe (345..500) + « / » + nom (0..100). */
    private fun headerName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val ustar = String(h, 257, 5, Charsets.US_ASCII) == "ustar"
        val prefix = if (ustar) cString(h, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    /** Champ texte terminé par un octet nul. */
    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    /** Taille : octal ASCII (cas courant) ou binaire « base 256 » (bit de poids fort du 1er octet). */
    private fun parseSize(h: ByteArray): Long {
        if (h[124].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v
        }
        val s = cString(h, 124, 12).trim().trim('\u0000')
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: throw IOException("En-tête tar invalide")
    }

    /** Chemin d'un en-tête PAX : enregistrements « <longueur> clé=valeur\n » (longueur en octets). */
    private fun paxPath(data: ByteArray): String? {
        var i = 0
        var path: String? = null
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > data.size) break
            // Contenu « clé=valeur » sans le « \n » final.
            val record = String(data, sp + 1, i + len - sp - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == "path") path = record.substring(eq + 1)
            i += len
        }
        return path
    }

    // ------------------------------------------------------------------ données

    /** Lit un bloc de 512 octets ; faux si le flux est terminé avant le début du bloc. */
    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < BLOCK) {
            val n = input.read(buf, off, BLOCK - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("Archive tronquée")
            }
            off += n
        }
        return true
    }

    /** Lit les données d'une entrée (petits en-têtes PAX / GNU uniquement) + bourrage. */
    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size > 1024 * 1024) throw IOException("En-tête étendu trop gros")
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n < 0) throw EOFException("Archive tronquée")
            off += n
        }
        skipBytes(input, padding(size))
        return data
    }

    /** Copie `size` octets vers `out` en calculant l'empreinte SHA-256, puis saute le bourrage. */
    private fun copyData(input: InputStream, size: Long, out: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var left = size
        FileOutputStream(out).use { fos ->
            while (left > 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Extraction annulée")
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("Archive tronquée")
                fos.write(buf, 0, n)
                md.update(buf, 0, n)
                left -= n
            }
        }
        skipBytes(input, padding(size))
        return IaModelDownloader.hex(md.digest())
    }

    /** Saute les données d'une entrée et leur bourrage. */
    private fun skipData(input: InputStream, size: Long) = skipBytes(input, size + padding(size))

    /** Bourrage jusqu'au bloc de 512 suivant. */
    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    /** Saute `n` octets en les lisant (skip() d'un flux gzip peut s'arrêter en route). */
    private fun skipBytes(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("Archive tronquée")
            left -= r
        }
    }

    /** Compte les octets lus (avancement dans l'archive compressée). */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
