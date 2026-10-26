package fr.pushpull.champimap.connect.download

import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.data.Dept
import fr.pushpull.champimap.connect.net.Http
import fr.pushpull.champimap.connect.pmtiles.ExtractStats
import fr.pushpull.champimap.connect.pmtiles.PmTilesExtractor
import fr.pushpull.champimap.connect.pmtiles.RangeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Cartes vectorielles d'un département : extraction depuis le build mondial quotidien de Protomaps
 * (fond OpenStreetMap, format PMTiles) par requêtes HTTP Range, sans télécharger l'archive entière.
 * Seule l'emprise du département est utilisée.
 */
object MapDownloader {
    private const val BUILDS_META = "https://build-metadata.protomaps.dev/builds.json"
    private const val BUILDS_BASE = "https://build.protomaps.com/"
    private val BUILD_KEY = Regex("^\\d{8}\\.pmtiles$")

    /**
     * Adresse du build le plus récent : liste officielle des builds, sinon sondage des
     * 20 derniers jours (fichiers nommés AAAAMMJJ.pmtiles).
     */
    fun findLatestBuild(): String {
        runCatching {
            val raw = Http.get(BUILDS_META, maxBytes = 4L * 1024 * 1024)
            val list = SyncProtocol.json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonArray
            val keys = list.mapNotNull { (it as? JsonObject)?.get("key")?.jsonPrimitive?.contentOrNull }
                .filter { BUILD_KEY.matches(it) }
            val latest = keys.maxOrNull()
            if (latest != null) return BUILDS_BASE + latest
        }
        val today = LocalDate.now(ZoneOffset.UTC)
        for (d in 0L..20L) {
            val key = today.minusDays(d).format(DateTimeFormatter.BASIC_ISO_DATE) + ".pmtiles"
            val code = runCatching { Http.head(BUILDS_BASE + key) }.getOrDefault(0)
            if (code == 200) return BUILDS_BASE + key
        }
        throw IOException("Aucune carte Protomaps disponible pour le moment")
    }

    /**
     * Extrait la carte du département dans `out`.
     * `onProgress(pourcentage 0..99, phase)` : 0–10 % lecture de l'index, 10–99 % données.
     */
    suspend fun extract(
        buildUrl: String,
        dept: Dept,
        maxZoom: Int,
        out: File,
        onProgress: (Int, String) -> Unit,
    ): ExtractStats {
        val source = RangeSource { offset, length -> Http.range(buildUrl, offset, length) }
        val extractor = PmTilesExtractor(source) { done, total, phase ->
            val frac = if (total > 0) done.toDouble() / total else 0.0
            val pct = if (phase.startsWith("Lecture")) (frac * 10).toInt() else 10 + (frac * 89).toInt()
            onProgress(pct.coerceIn(0, 99), phase)
        }
        return extractor.extract(dept.bbox, maxZoom, out)
    }
}
