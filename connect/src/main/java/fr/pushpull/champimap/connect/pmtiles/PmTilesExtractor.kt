package fr.pushpull.champimap.connect.pmtiles

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Extraction d'une zone (emprise d'un département) depuis une grande archive PMTiles distante
 * (build mondial Protomaps), SANS la télécharger en entier : uniquement des requêtes « Range ».
 *
 * Étapes :
 *  1. lire l'en-tête et le répertoire racine (16 Ko au début du fichier) ;
 *  2. lister les tuiles (z, x, y) de l'emprise, du zoom minimal au zoom maximal demandé ;
 *  3. trouver chaque tuile dans les répertoires (racine puis feuilles, téléchargées à la demande) ;
 *  4. calculer le nouveau fichier : contenus uniques (dédoublonnés), répertoires réécrits ;
 *  5. écrire l'en-tête, les répertoires, les métadonnées, puis télécharger les données des tuiles
 *     par gros blocs contigus (les tuiles voisines sont rangées côte à côte dans l'archive source).
 *
 * Le résultat est un fichier PMTiles v3 autonome et valide, lisible hors ligne par MapLibre.
 * Ce fichier ne dépend d'aucune API Android (testable sur ordinateur).
 */

/** Accès par plages d'octets à une archive (HTTP Range dans l'appli, fichier local en test). */
fun interface RangeSource {
    /** Lit `length` octets à partir de `offset` (peut renvoyer moins en fin de fichier). */
    fun read(offset: Long, length: Long): ByteArray
}

/** Bilan d'une extraction (affiché dans l'avancement). */
data class ExtractStats(val tiles: Long, val uniqueContents: Long, val bytes: Long, val maxZoom: Int)

/**
 * Extracteur. `onProgress(fait, total, étape)` est appelé régulièrement (octets de tuiles).
 */
class PmTilesExtractor(
    private val source: RangeSource,
    private val onProgress: (done: Long, total: Long, phase: String) -> Unit = { _, _, _ -> },
) {
    private companion object {
        /** Écart maximal entre deux tuiles pour les télécharger dans la même requête. */
        const val MAX_GAP = 64L * 1024
        /** Taille maximale d'une requête de données (mémoire raisonnable sur un téléphone). */
        const val MAX_CHUNK = 4L * 1024 * 1024
        /** Profondeur maximale des répertoires (racine + feuilles imbriquées). */
        const val MAX_DEPTH = 4
    }

    /** Une tuile trouvée dans l'archive source. */
    private class Found(val tileId: Long, val srcOffset: Long, val length: Long)

    /**
     * Extrait l'emprise `bbox` jusqu'au zoom `maxZoom` dans le fichier `out` (écrasé).
     * Lève IOException en cas d'erreur réseau ou de format.
     */
    suspend fun extract(bbox: BBox, maxZoom: Int, out: File): ExtractStats {
        // 1. En-tête + répertoire racine.
        onProgress(0, 1, "Lecture de l'index de la carte")
        val first = source.read(0, PmTiles.FIRST_FETCH.toLong())
        val header = PmHeader.decode(first)
        val rootBytes = slice(first, header.rootOffset, header.rootLength)
        val root = PmTiles.deserializeDirectory(PmTiles.decompress(rootBytes, header.internalCompression))
        val metadataRaw = if (header.metadataLength > 0) {
            PmTiles.decompress(slice(first, header.metadataOffset, header.metadataLength), header.internalCompression)
        } else "{}".toByteArray()

        // 2. Tuiles voulues (triées par TileID).
        val zMin = header.minZoom
        val zMax = min(maxZoom, header.maxZoom)
        if (zMax < zMin) throw IOException("Niveaux de zoom indisponibles dans l'archive")
        val wanted = wantedTileIds(bbox, zMin, zMax)

        // 3. Résolution dans les répertoires (feuilles mises en cache).
        val leafCache = HashMap<Long, List<PmEntry>>()
        val found = ArrayList<Found>(wanted.size)
        for ((i, id) in wanted.withIndex()) {
            if (i % 2000 == 0) {
                currentCoroutineContext().ensureActive()
                onProgress(i.toLong(), wanted.size.toLong(), "Lecture de l'index de la carte")
            }
            val e = resolve(header, root, id, leafCache) ?: continue
            if (e.length > 0) found.add(Found(id, e.offset, e.length))
        }
        if (found.isEmpty()) throw IOException("Aucune tuile disponible pour cette zone")

        // 4. Contenus uniques, rangés dans l'ordre de l'archive source → offsets du nouveau fichier.
        val uniqueLen = HashMap<Long, Long>()
        for (f in found) uniqueLen[f.srcOffset] = f.length
        val uniqueSorted = uniqueLen.keys.sorted()
        val outOffsetOf = HashMap<Long, Long>(uniqueSorted.size * 2)
        var dataLength = 0L
        for (src in uniqueSorted) {
            outOffsetOf[src] = dataLength
            dataLength += uniqueLen.getValue(src)
        }

        // Entrées du nouveau répertoire, avec regroupement des tuiles identiques consécutives.
        val entries = ArrayList<PmEntry>()
        for (f in found) {
            val off = outOffsetOf.getValue(f.srcOffset)
            val last = entries.lastOrNull()
            if (last != null && last.offset == off && last.tileId + last.runLength == f.tileId) {
                last.runLength++
            } else entries.add(PmEntry(f.tileId, off, f.length, 1))
        }
        val clustered = isClustered(entries)
        val (rootOut, leavesOut) = PmTiles.buildDirectories(entries, PmTiles.FIRST_FETCH - PmTiles.HEADER_SIZE)
        val metadataOut = PmTiles.gzip(metadataRaw)

        // 5. Écriture : en-tête, racine, métadonnées, feuilles, puis données.
        val rootOffset = PmTiles.HEADER_SIZE.toLong()
        val metadataOffset = rootOffset + rootOut.size
        val leavesOffset = metadataOffset + metadataOut.size
        val dataOffset = leavesOffset + leavesOut.size
        val outHeader = PmHeader(
            rootOffset = rootOffset, rootLength = rootOut.size.toLong(),
            metadataOffset = metadataOffset, metadataLength = metadataOut.size.toLong(),
            leafDirsOffset = leavesOffset, leafDirsLength = leavesOut.size.toLong(),
            tileDataOffset = dataOffset, tileDataLength = dataLength,
            addressedTiles = found.size.toLong(),
            tileEntries = entries.size.toLong(),
            tileContents = uniqueSorted.size.toLong(),
            clustered = clustered,
            internalCompression = PmCompression.GZIP,
            tileCompression = header.tileCompression,
            tileType = header.tileType,
            minZoom = PmTiles.tileIdToZxy(entries.first().tileId).first,
            maxZoom = PmTiles.tileIdToZxy(entries.last().tileId).first,
            minLonE7 = e7(bbox.minLon), minLatE7 = e7(bbox.minLat),
            maxLonE7 = e7(bbox.maxLon), maxLatE7 = e7(bbox.maxLat),
            centerZoom = min(zMax, max(zMin, 9)),
            centerLonE7 = e7((bbox.minLon + bbox.maxLon) / 2),
            centerLatE7 = e7((bbox.minLat + bbox.maxLat) / 2),
        )

        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(outHeader.encode())
            raf.write(rootOut)
            raf.write(metadataOut)
            raf.write(leavesOut)
            downloadData(header, uniqueSorted, uniqueLen, outOffsetOf, dataOffset, dataLength, raf)
            raf.setLength(dataOffset + dataLength)
        }
        return ExtractStats(found.size.toLong(), uniqueSorted.size.toLong(), dataOffset + dataLength, outHeader.maxZoom)
    }

    /** Liste triée des TileID couvrant l'emprise, pour chaque niveau de zoom. */
    private fun wantedTileIds(b: BBox, zMin: Int, zMax: Int): LongArray {
        val ids = ArrayList<Long>()
        for (z in zMin..zMax) {
            val x0 = PmTiles.lonToTileX(b.minLon, z)
            val x1 = PmTiles.lonToTileX(b.maxLon, z)
            val y0 = PmTiles.latToTileY(b.maxLat, z) // nord = petite ligne
            val y1 = PmTiles.latToTileY(b.minLat, z)
            for (x in x0..x1) for (y in y0..y1) ids.add(PmTiles.zxyToTileId(z, x, y))
        }
        val arr = ids.toLongArray()
        arr.sort()
        return arr
    }

    /** Trouve l'entrée de données d'une tuile (racine puis feuilles), ou null si absente. */
    private fun resolve(h: PmHeader, root: List<PmEntry>, tileId: Long, cache: HashMap<Long, List<PmEntry>>): PmEntry? {
        var dir = root
        repeat(MAX_DEPTH) {
            val idx = PmTiles.findEntryIndex(dir, tileId)
            if (idx < 0) return null
            val e = dir[idx]
            if (e.runLength > 0) return e
            // Pointeur vers un répertoire feuille : téléchargé une seule fois.
            val abs = h.leafDirsOffset + e.offset
            dir = cache.getOrPut(abs) {
                val raw = source.read(abs, e.length)
                if (raw.size.toLong() != e.length) throw IOException("Répertoire de carte incomplet")
                PmTiles.deserializeDirectory(PmTiles.decompress(raw, h.internalCompression))
            }
        }
        return null
    }

    /**
     * Télécharge les contenus uniques par blocs contigus (écart ≤ 64 Ko, bloc ≤ 4 Mo)
     * et les écrit à leur place dans le nouveau fichier.
     */
    private suspend fun downloadData(
        h: PmHeader,
        uniqueSorted: List<Long>,
        uniqueLen: Map<Long, Long>,
        outOffsetOf: Map<Long, Long>,
        outDataOffset: Long,
        total: Long,
        raf: RandomAccessFile,
    ) {
        var done = 0L
        var i = 0
        while (i < uniqueSorted.size) {
            currentCoroutineContext().ensureActive()
            // Constitution d'un bloc.
            val chunkStart = uniqueSorted[i]
            var chunkEnd = chunkStart + uniqueLen.getValue(chunkStart)
            var j = i + 1
            while (j < uniqueSorted.size) {
                val s = uniqueSorted[j]
                val end = s + uniqueLen.getValue(s)
                if (s - chunkEnd > MAX_GAP || end - chunkStart > MAX_CHUNK) break
                chunkEnd = max(chunkEnd, end)
                j++
            }
            // Téléchargement du bloc puis découpage en tuiles.
            val bytes = source.read(h.tileDataOffset + chunkStart, chunkEnd - chunkStart)
            if (bytes.size.toLong() < chunkEnd - chunkStart) throw IOException("Bloc de carte incomplet")
            for (k in i until j) {
                val s = uniqueSorted[k]
                val len = uniqueLen.getValue(s)
                raf.seek(outDataOffset + outOffsetOf.getValue(s))
                raf.write(bytes, (s - chunkStart).toInt(), len.toInt())
                done += len
            }
            onProgress(done, total, "Téléchargement de la carte")
            i = j
        }
    }

    /** Vrai si les données sont rangées dans l'ordre des TileID (drapeau « clustered »). */
    private fun isClustered(entries: List<PmEntry>): Boolean {
        var expected = 0L
        val seen = HashSet<Long>()
        for (e in entries) {
            if (seen.add(e.offset)) {
                if (e.offset != expected) return false
                expected = e.offset + e.length
            }
        }
        return true
    }

    /** Extrait une portion de la première lecture, ou la télécharge si elle dépasse. */
    private fun slice(first: ByteArray, offset: Long, length: Long): ByteArray =
        if (offset + length <= first.size) first.copyOfRange(offset.toInt(), (offset + length).toInt())
        else source.read(offset, length).also {
            if (it.size.toLong() != length) throw IOException("Lecture de carte incomplète")
        }

    private fun e7(deg: Double): Int = (deg * 10_000_000).roundToInt()
}
