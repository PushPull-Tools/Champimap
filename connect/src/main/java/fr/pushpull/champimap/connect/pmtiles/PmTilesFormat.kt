package fr.pushpull.champimap.connect.pmtiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos

/*
 * Format PMTiles version 3 (spécification : github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 *
 * Un fichier PMTiles est une archive de tuiles cartographiques en UN seul fichier, lisible par
 * morceaux (requêtes HTTP Range). Organisation :
 *
 *   [ en-tête 127 octets ][ répertoire racine ][ métadonnées JSON ][ répertoires feuilles ][ données des tuiles ]
 *
 *  - Chaque tuile (z, x, y) a un identifiant unique « TileID » : position sur une courbe de Hilbert,
 *    niveau par niveau (z=0 d'abord, puis z=1…). Deux tuiles proches sur la carte ont des TileID proches.
 *  - Un répertoire = liste triée d'entrées (TileID, offset, longueur, runLength), sérialisée en
 *    « varints » colonne par colonne, puis compressée (gzip en pratique).
 *  - runLength > 0 : l'entrée désigne des données de tuile ; runLength tuiles consécutives partagent
 *    les mêmes octets (ex. l'océan). runLength = 0 : l'entrée pointe vers un répertoire « feuille »
 *    (offset relatif au début de la section des feuilles).
 *  - Le répertoire racine doit tenir, avec l'en-tête, dans les 16 384 premiers octets.
 *
 * Ce fichier ne dépend d'aucune API Android : il est testable sur un simple ordinateur.
 */

/** Codes de compression du format PMTiles. */
object PmCompression {
    const val UNKNOWN = 0
    const val NONE = 1
    const val GZIP = 2
    const val BROTLI = 3
    const val ZSTD = 4
}

/** En-tête PMTiles v3 (127 octets, petit-boutiste). Les coordonnées sont en degrés × 10 000 000. */
data class PmHeader(
    val rootOffset: Long,
    val rootLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirsOffset: Long,
    val leafDirsLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: Int,
    val tileCompression: Int,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLonE7: Int,
    val minLatE7: Int,
    val maxLonE7: Int,
    val maxLatE7: Int,
    val centerZoom: Int,
    val centerLonE7: Int,
    val centerLatE7: Int,
) {
    /** Sérialise l'en-tête sur exactement 127 octets. */
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(PmTiles.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PMTiles".toByteArray(Charsets.US_ASCII))
        b.put(3.toByte()) // version de la spécification
        b.putLong(rootOffset); b.putLong(rootLength)
        b.putLong(metadataOffset); b.putLong(metadataLength)
        b.putLong(leafDirsOffset); b.putLong(leafDirsLength)
        b.putLong(tileDataOffset); b.putLong(tileDataLength)
        b.putLong(addressedTiles); b.putLong(tileEntries); b.putLong(tileContents)
        b.put(if (clustered) 1.toByte() else 0.toByte())
        b.put(internalCompression.toByte())
        b.put(tileCompression.toByte())
        b.put(tileType.toByte())
        b.put(minZoom.toByte()); b.put(maxZoom.toByte())
        b.putInt(minLonE7); b.putInt(minLatE7); b.putInt(maxLonE7); b.putInt(maxLatE7)
        b.put(centerZoom.toByte())
        b.putInt(centerLonE7); b.putInt(centerLatE7)
        return b.array()
    }

    companion object {
        /** Lit l'en-tête au début de `bytes` ; erreur si ce n'est pas un PMTiles v3. */
        fun decode(bytes: ByteArray): PmHeader {
            if (bytes.size < PmTiles.HEADER_SIZE) throw IOException("En-tête PMTiles incomplet")
            val magic = String(bytes, 0, 7, Charsets.US_ASCII)
            if (magic != "PMTiles") throw IOException("Ce fichier n'est pas une archive PMTiles")
            if (bytes[7].toInt() != 3) throw IOException("Version PMTiles non prise en charge : ${bytes[7]}")
            val b = ByteBuffer.wrap(bytes, 0, PmTiles.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.position(8)
            fun u8(): Int = b.get().toInt() and 0xFF
            return PmHeader(
                rootOffset = b.long, rootLength = b.long,
                metadataOffset = b.long, metadataLength = b.long,
                leafDirsOffset = b.long, leafDirsLength = b.long,
                tileDataOffset = b.long, tileDataLength = b.long,
                addressedTiles = b.long, tileEntries = b.long, tileContents = b.long,
                clustered = u8() == 1,
                internalCompression = u8(),
                tileCompression = u8(),
                tileType = u8(),
                minZoom = u8(), maxZoom = u8(),
                minLonE7 = b.int, minLatE7 = b.int, maxLonE7 = b.int, maxLatE7 = b.int,
                centerZoom = u8(),
                centerLonE7 = b.int, centerLatE7 = b.int,
            )
        }
    }
}

/**
 * Une entrée de répertoire.
 * runLength = 0 → pointeur vers un répertoire feuille ; sinon `runLength` tuiles consécutives
 * (à partir de `tileId`) utilisent les mêmes octets [offset, offset + length[.
 */
class PmEntry(val tileId: Long, val offset: Long, val length: Long, var runLength: Long)

/** Emprise géographique en degrés (WGS84). */
data class BBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

/** Outils du format : identifiants de tuiles, répertoires, compression. */
object PmTiles {
    const val HEADER_SIZE = 127

    /** Taille de la première lecture : l'en-tête et le répertoire racine y tiennent toujours. */
    const val FIRST_FETCH = 16_384

    // ---------------------------------------------------------------- Identifiants de tuiles

    /**
     * (z, x, y) → TileID : nombre de tuiles des niveaux inférieurs + position de (x, y)
     * sur la courbe de Hilbert du niveau z. Portage fidèle de l'implémentation de référence.
     */
    fun zxyToTileId(z: Int, x: Long, y: Long): Long {
        require(z in 0..26) { "Niveau de zoom hors limites : $z" }
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "Tuile hors limites" }
        var acc = ((1L shl (z * 2)) - 1) / 3
        var tx = x
        var ty = y
        var a = z - 1
        while (a >= 0) {
            val s = 1L shl a
            val rx = s and tx
            val ry = s and ty
            acc += ((3 * rx) xor ry) shl a
            // Rotation du quadrant (courbe de Hilbert).
            if (ry == 0L) {
                if (rx != 0L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val t = tx; tx = ty; ty = t
            }
            a--
        }
        return acc
    }

    /** TileID → (z, x, y). Sert aux contrôles et au calcul des niveaux réellement présents. */
    fun tileIdToZxy(tileId: Long): Triple<Int, Long, Long> {
        var acc = 0L
        var z = 0
        while (true) {
            val count = 1L shl (2 * z)
            if (acc + count > tileId) break
            acc += count
            z++
        }
        var pos = tileId - acc
        var x = 0L
        var y = 0L
        var s = 1L
        val n = 1L shl z
        while (s < n) {
            val rx = (pos / 2) and s
            val ry = (pos xor rx) and s
            if (ry == 0L) {
                if (rx != 0L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val t = x; x = y; y = t
            }
            x += rx
            y += ry
            pos = pos shr 1
            s = s shl 1
        }
        return Triple(z, x, y)
    }

    /** Colonne de tuile contenant la longitude, au niveau z (projection Web Mercator). */
    fun lonToTileX(lon: Double, z: Int): Long {
        val n = 1L shl z
        val x = floor((lon + 180.0) / 360.0 * n).toLong()
        return x.coerceIn(0, n - 1)
    }

    /** Ligne de tuile contenant la latitude, au niveau z (0 = nord). */
    fun latToTileY(lat: Double, z: Int): Long {
        val n = 1L shl z
        val clamped = lat.coerceIn(-85.0511, 85.0511)
        val rad = clamped * PI / 180.0
        val y = floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toLong()
        return y.coerceIn(0, n - 1)
    }

    // ---------------------------------------------------------------- Varints

    /** Écrit un entier non signé en varint (7 bits par octet, bit de poids fort = « suite »). */
    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val bits = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(bits or 0x80) else {
                out.write(bits)
                return
            }
        }
    }

    /** Lecteur séquentiel de varints. */
    private class VarintReader(private val buf: ByteArray) {
        var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw IOException("Répertoire PMTiles tronqué")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Varint PMTiles invalide")
            }
        }
    }

    // ---------------------------------------------------------------- Répertoires

    /**
     * Désérialise un répertoire (déjà décompressé). Colonnes successives :
     * nombre d'entrées, écarts de TileID, runLengths, longueurs, offsets
     * (offset 0 = « juste après l'entrée précédente », sinon offset + 1).
     */
    fun deserializeDirectory(raw: ByteArray): List<PmEntry> {
        val r = VarintReader(raw)
        val count = r.next().toInt()
        if (count < 0 || count > 50_000_000) throw IOException("Répertoire PMTiles invalide")
        val ids = LongArray(count)
        var last = 0L
        for (i in 0 until count) {
            last += r.next()
            ids[i] = last
        }
        val runs = LongArray(count) { r.next() }
        val lengths = LongArray(count) { r.next() }
        val entries = ArrayList<PmEntry>(count)
        for (i in 0 until count) {
            val v = r.next()
            val offset = if (i > 0 && v == 0L) {
                val prev = entries[i - 1]
                prev.offset + prev.length
            } else v - 1
            entries.add(PmEntry(ids[i], offset, lengths[i], runs[i]))
        }
        return entries
    }

    /** Sérialise un répertoire (non compressé) selon le même schéma colonne par colonne. */
    fun serializeDirectory(entries: List<PmEntry>): ByteArray {
        val out = ByteArrayOutputStream(entries.size * 6 + 8)
        writeVarint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) {
            writeVarint(out, e.tileId - last)
            last = e.tileId
        }
        for (e in entries) writeVarint(out, e.runLength)
        for (e in entries) writeVarint(out, e.length)
        for (i in entries.indices) {
            val e = entries[i]
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) writeVarint(out, 0)
            else writeVarint(out, e.offset + 1)
        }
        return out.toByteArray()
    }

    /**
     * Cherche l'entrée qui couvre `tileId` : la dernière entrée dont le TileID est ≤ tileId.
     * Retourne son index, ou -1 si la tuile n'existe pas dans ce répertoire.
     * Si l'entrée trouvée est une feuille (runLength = 0), c'est au répertoire feuille de répondre.
     */
    fun findEntryIndex(entries: List<PmEntry>, tileId: Long): Int {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = tileId - entries[mid].tileId
            when {
                c > 0 -> lo = mid + 1
                c < 0 -> hi = mid - 1
                else -> return mid
            }
        }
        // hi = dernière entrée de TileID < tileId
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0L) return hi
            if (tileId - e.tileId < e.runLength) return hi
        }
        return -1
    }

    /**
     * Construit le répertoire racine et, si besoin, les répertoires feuilles (compressés en gzip).
     * Le répertoire racine doit faire au plus `maxRootBytes` octets ; sinon les entrées sont
     * réparties en feuilles de 4096 entrées (doublées tant que la racine est trop grosse).
     * @return (racine, feuilles concaténées)
     */
    fun buildDirectories(entries: List<PmEntry>, maxRootBytes: Int): Pair<ByteArray, ByteArray> {
        val single = gzip(serializeDirectory(entries))
        if (single.size <= maxRootBytes) return single to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = ByteArrayOutputStream()
            val rootEntries = ArrayList<PmEntry>()
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, minOf(i + leafSize, entries.size))
                val bytes = gzip(serializeDirectory(chunk))
                rootEntries.add(PmEntry(chunk[0].tileId, leaves.size().toLong(), bytes.size.toLong(), 0))
                leaves.write(bytes)
                i += leafSize
            }
            val root = gzip(serializeDirectory(rootEntries))
            if (root.size <= maxRootBytes) return root to leaves.toByteArray()
            leafSize *= 2
        }
    }

    // ---------------------------------------------------------------- Compression

    /** Décompresse un bloc interne (répertoire, métadonnées) selon le code de compression. */
    fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        PmCompression.NONE -> bytes
        PmCompression.GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> throw IOException("Compression PMTiles non prise en charge : $compression")
    }

    /** Compresse en gzip (compression interne utilisée pour tous les fichiers écrits). */
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 2 + 32)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
