package fr.pushpull.champimap.connect.download

import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale

/*
 * Modèle d'identification de Champimap : publié comme release GitHub « ia-vN » du dépôt officiel,
 * avec un seul asset champi-ia-resultat.tar.gz.
 *  - liste des versions : API PUBLIQUE GitHub, GET non authentifié (aucune donnée envoyée) ;
 *  - téléchargement reprenable (Http.downloadTo) ;
 *  - empreinte SHA-256 de l'archive (comparée au champ « digest » de l'API quand il existe) ;
 *  - statistiques du modèle : commentaire `<!-- STATS {json} -->` dans le texte (body) de la release
 *    (champs tolérés : version, especes, top1, top5, photosControle, photosEntrainement, architecture,
 *    tailleModeleMo ; un champ absent n'est simplement pas affiché).
 * Toutes les fonctions sont bloquantes : à appeler depuis Dispatchers.IO (runInterruptible).
 */
object IaModelDownloader {
    /** Liste des releases du dépôt officiel (100 par page : largement assez). */
    private const val RELEASES_URL = "https://api.github.com/repos/PushPull-Tools/Champimap/releases?per_page=100"

    /** Seules les adresses de téléchargement du dépôt officiel sont acceptées. */
    private const val DOWNLOAD_PREFIX = "https://github.com/PushPull-Tools/Champimap/releases/download/"

    /** Nom de l'asset attendu dans chaque release « ia-vN ». */
    const val ASSET_NAME = "champi-ia-resultat.tar.gz"

    /** Taille approximative affichée avant de connaître la vraie (≈ 140 Mo). */
    const val APPROX_BYTES = 140L * 1024 * 1024

    /** Une version publiée du modèle. */
    data class IaRelease(
        val tag: String,
        val number: Int,
        val url: String,
        val size: Long,
        /** Empreinte SHA-256 publiée par GitHub (hexadécimal minuscule), null si absente. */
        val sha256: String?,
        /** Date de publication « AAAA-MM-JJ » (peut être vide). */
        val date: String,
        /** Statistiques publiées dans le texte de la release (null si absentes). */
        val stats: IaStats? = null,
    ) {
        /** « ia-v1 · 1 032 espèces · réussite 73,8 % (top-5 92,7 %) · 35 Mo · 26/10/2026 ». */
        fun summary(): String = buildList {
            add(tag)
            stats?.especes?.let { add("${formatInt(it)} espèces") }
            stats?.top1?.let { t1 ->
                add("réussite ${formatPct(t1)}" + (stats?.top5?.let { " (top-5 ${formatPct(it)})" } ?: ""))
            }
            // this@IaRelease : dans buildList, « size » désignerait la taille de la liste.
            val bytes = this@IaRelease.size
            val mo = stats?.tailleModeleMo ?: if (bytes > 0) bytes / 1048576.0 else null
            mo?.let { add(String.format(Locale.FRANCE, "%.0f Mo", it)) }
            frenchDate(date)?.let { add(it) }
        }.joinToString(" · ")
    }

    /** Statistiques d'un modèle (toutes facultatives). */
    data class IaStats(
        val especes: Int? = null,
        val top1: Double? = null,
        val top5: Double? = null,
        val photosControle: Int? = null,
        val photosEntrainement: Int? = null,
        val architecture: String? = null,
        val tailleModeleMo: Double? = null,
        /** Objet JSON d'origine (transmis tel quel à l'appli dans stats.json). */
        val raw: JsonObject,
    )

    /** Commentaire de statistiques dans le texte d'une release. */
    private val STATS_REGEX = Regex("<!--\\s*STATS\\s*(\\{.*?\\})\\s*-->", RegexOption.DOT_MATCHES_ALL)

    /** Lit `<!-- STATS {json} -->` dans le texte d'une release (null si absent ou illisible). */
    fun parseStats(body: String?): IaStats? {
        val text = body?.let { STATS_REGEX.find(it)?.groupValues?.get(1) } ?: return null
        val o = runCatching { SyncProtocol.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun p(k: String) = o[k] as? JsonPrimitive
        return IaStats(
            especes = p("especes")?.intOrNull,
            top1 = p("top1")?.doubleOrNull,
            top5 = p("top5")?.doubleOrNull,
            photosControle = p("photosControle")?.intOrNull,
            photosEntrainement = p("photosEntrainement")?.intOrNull,
            architecture = p("architecture")?.contentOrNull,
            tailleModeleMo = p("tailleModeleMo")?.doubleOrNull,
            raw = o,
        )
    }

    /** « 1 032 » (espace fine insécable remplacée par une espace simple pour l'affichage). */
    private fun formatInt(n: Int): String = String.format(Locale.FRANCE, "%,d", n).replace('\u202F', ' ').replace('\u00A0', ' ')

    /** « 73,8 % ». */
    private fun formatPct(v: Double): String = String.format(Locale.FRANCE, "%.1f %%", v)

    /** « AAAA-MM-JJ » → « JJ/MM/AAAA » (null si vide ou mal formé). */
    private fun frenchDate(d: String): String? =
        d.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }?.let { "${it.substring(8, 10)}/${it.substring(5, 7)}/${it.substring(0, 4)}" }

    /** Numéro N de « ia-vN » (null si le texte n'est pas une version valide). */
    fun versionNumber(tag: String?): Int? =
        tag?.trim()?.let { SyncProtocol.IA_VERSION_REGEX.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Toutes les versions publiées, de la plus récente à la plus ancienne :
     * releases non brouillon, étiquette « ia-vN », avec l'asset champi-ia-resultat.tar.gz.
     */
    fun listReleases(): List<IaRelease> {
        val raw = Http.get(
            RELEASES_URL,
            maxBytes = 8L * 1024 * 1024,
            headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
        )
        val list = SyncProtocol.json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonArray
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.bool("draft") == true) return@mapNotNull null
            val tag = o.text("tag_name") ?: return@mapNotNull null
            val n = versionNumber(tag) ?: return@mapNotNull null
            val asset = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.text("name") == ASSET_NAME } ?: return@mapNotNull null
            val url = asset.text("browser_download_url")?.takeIf { it.startsWith(DOWNLOAD_PREFIX) } ?: return@mapNotNull null
            val size = asset.long("size") ?: 0L
            // « digest » : « sha256:<64 hex> » (présent sur les releases récentes de GitHub).
            val sha = asset.text("digest")?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')?.lowercase()?.takeIf { HEX64.matches(it) }
            IaRelease(tag, n, url, size, sha, o.text("published_at")?.take(10) ?: "", parseStats(o.text("body")))
        }.sortedByDescending { it.number }
    }

    /** Télécharge l'archive d'une version dans `part` (reprenable). `onProgress(octets, total)`. */
    fun download(release: IaRelease, part: File, onProgress: (Long, Long) -> Unit) {
        Http.downloadTo(release.url, part, release.size, onProgress)
        if (release.size > 0 && part.length() != release.size) throw IOException("Téléchargement du modèle incomplet")
    }

    /** Empreinte SHA-256 (hexadécimal minuscule) d'un fichier, lue en flux. `onProgress(octets lus, total)`. */
    fun sha256(file: File, onProgress: (Long, Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        FileInputStream(file).use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Vérification annulée")
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
        return hex(md.digest())
    }

    /** Octets → hexadécimal minuscule. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    // Petits lecteurs JSON tolérants.
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
