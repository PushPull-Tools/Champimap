package fr.pushpull.champimap.connect.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * Accès réseau minimal de Champimap Connect : HttpURLConnection uniquement (aucune dépendance HTTP).
 * - délais courts, nouvelles tentatives sur les erreurs passagères (réseau, 429, 5xx) ;
 * - requêtes « Range » pour lire une petite partie d'une très grosse archive de cartes ;
 * - taille maximale de réponse pour ne jamais saturer la mémoire du téléphone ;
 * - téléchargement en flux reprenable (fichier .part + « Range: bytes=N- ») pour les gros fichiers.
 * Toutes les fonctions sont bloquantes : à appeler depuis Dispatchers.IO.
 */

/** Erreur HTTP avec son code (404, 503…). */
class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    private const val USER_AGENT = "Champimap-Connect/0.1 (github.com/PushPull-Tools)"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val ATTEMPTS = 3

    /** Taille maximale d'une réponse « normale » (JSON, GeoJSON) : 32 Mo. */
    const val MAX_BODY = 32L * 1024 * 1024

    /** Nombre de tentatives d'un téléchargement reprenable (chaque reprise repart de la fin du .part). */
    private const val DOWNLOAD_ATTEMPTS = 6

    /** GET : renvoie le corps de la réponse (erreur si code ≠ 2xx). `headers` : en-têtes en plus. */
    fun get(url: String, maxBytes: Long = MAX_BODY, headers: Map<String, String> = emptyMap()): ByteArray = retry {
        val c = open(url, "GET")
        try {
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            checkOk(c, url)
            readAll(c.inputStream, maxBytes)
        } finally {
            c.disconnect()
        }
    }

    /** POST d'un formulaire (Overpass). `body` est déjà encodé (application/x-www-form-urlencoded). */
    fun postForm(url: String, body: String, maxBytes: Long = MAX_BODY): ByteArray = retry {
        val c = open(url, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            checkOk(c, url)
            readAll(c.inputStream, maxBytes)
        } finally {
            c.disconnect()
        }
    }

    /** HEAD : renvoie le code HTTP (sans nouvelle tentative : sert à sonder l'existence d'un fichier). */
    fun head(url: String): Int {
        val c = open(url, "HEAD")
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    /**
     * Lit `length` octets à partir de `offset` (requête Range).
     * Refuse une réponse 200 complète : cela voudrait dire télécharger toute l'archive mondiale.
     */
    fun range(url: String, offset: Long, length: Long): ByteArray = retry {
        val c = open(url, "GET")
        try {
            c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            val code = c.responseCode
            if (code == 416) return@retry ByteArray(0) // au-delà de la fin du fichier
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw HttpException(code, "Le serveur de cartes n'accepte pas la lecture partielle (code $code)")
            }
            readAll(c.inputStream, length)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Télécharge `url` dans `part`, en reprenant là où le fichier s'est arrêté (requête Range).
     *  - 206 + Content-Range cohérent : ajout à la fin du fichier ;
     *  - 200 (le serveur ignore Range) : on repart de zéro ;
     *  - 416 : le fichier est déjà complet (si sa taille vaut `expectedSize`).
     * Les coupures réseau sont reprises automatiquement (6 tentatives, la suite seulement).
     * `onProgress(octetsReçus, total)` est appelé au fil de l'eau. Interrompable (fil interrompu → arrêt).
     * @param expectedSize taille attendue (0 = inconnue) ; un fichier plus gros est supprimé.
     */
    fun downloadTo(url: String, part: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        part.parentFile?.mkdirs()
        if (expectedSize > 0 && part.length() > expectedSize) part.delete()
        var last: IOException? = null
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            if (expectedSize > 0 && part.length() == expectedSize) return // déjà complet
            try {
                downloadOnce(url, part, expectedSize, onProgress)
                return
            } catch (e: HttpException) {
                if (e.code != 429 && e.code < 500) throw e
                last = e
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e // annulation : pas de nouvelle tentative
                last = e
            }
            if (attempt < DOWNLOAD_ATTEMPTS) Thread.sleep(minOf(2_000L * attempt, 10_000L))
        }
        throw last ?: IOException("Erreur réseau")
    }

    /** Une tentative de téléchargement (reprise à partir de la taille actuelle de `part`). */
    private fun downloadOnce(url: String, part: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        val start = if (part.exists()) part.length() else 0L
        val c = open(url, "GET")
        try {
            if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
            val code = c.responseCode
            if (code == 416 && start > 0 && (expectedSize <= 0 || start == expectedSize)) return
            val append = when {
                code == HttpURLConnection.HTTP_PARTIAL &&
                    (c.getHeaderField("Content-Range") ?: "").startsWith("bytes $start-") -> true
                code == HttpURLConnection.HTTP_PARTIAL -> {
                    // Morceau inattendu : on jette le partiel et on recommence proprement.
                    part.delete()
                    throw IOException("Reprise du téléchargement incohérente")
                }
                code in 200..299 -> false
                else -> {
                    runCatching { c.errorStream?.close() }
                    throw HttpException(code, "Erreur $code sur ${URL(url).host}")
                }
            }
            var done = if (append) start else 0L
            val length = c.contentLengthLong
            val total = if (expectedSize > 0) expectedSize else if (length > 0) done + length else 0L
            c.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Téléchargement annulé")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done > total) throw IOException("Fichier plus gros que prévu")
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (expectedSize > 0 && done != expectedSize) throw IOException("Téléchargement incomplet")
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ interne

    /** Prépare une connexion (pas de compression transparente : les tailles doivent rester exactes). */
    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.useCaches = false
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    /** Lève une HttpException si le code n'est pas 2xx. */
    private fun checkOk(c: HttpURLConnection, url: String) {
        val code = c.responseCode
        if (code !in 200..299) {
            runCatching { c.errorStream?.close() }
            throw HttpException(code, "Erreur $code sur ${URL(url).host}")
        }
    }

    /** Lit tout le flux, en refusant de dépasser `maxBytes`. */
    private fun readAll(input: InputStream, maxBytes: Long): ByteArray = input.use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Réponse trop volumineuse")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    /** Nouvelles tentatives (2 s, 6 s) sur les erreurs passagères ; les 4xx (hors 429) échouent tout de suite. */
    private fun <T> retry(block: () -> T): T {
        var last: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code != 429 && e.code < 500) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < ATTEMPTS) Thread.sleep(if (attempt == 1) 2_000L else 6_000L)
        }
        throw last ?: IOException("Erreur réseau")
    }
}
