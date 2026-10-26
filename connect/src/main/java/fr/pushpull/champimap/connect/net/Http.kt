package fr.pushpull.champimap.connect.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/*
 * Accès réseau minimal de Champimap Connect : HttpURLConnection uniquement (aucune dépendance HTTP).
 * - délais courts, nouvelles tentatives sur les erreurs passagères (réseau, 429, 5xx) ;
 * - requêtes « Range » pour lire une petite partie d'une très grosse archive de cartes ;
 * - taille maximale de réponse pour ne jamais saturer la mémoire du téléphone.
 * Toutes les fonctions sont bloquantes : à appeler depuis Dispatchers.IO.
 */

/** Erreur HTTP avec son code (404, 503…). */
class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    private const val USER_AGENT = "ChampimapConnect/0.1 (appli de cueillette hors ligne ; github.com/PushPull-Tools)"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val ATTEMPTS = 3

    /** Taille maximale d'une réponse « normale » (JSON, GeoJSON) : 32 Mo. */
    const val MAX_BODY = 32L * 1024 * 1024

    /** GET : renvoie le corps de la réponse (erreur si code ≠ 2xx). */
    fun get(url: String, maxBytes: Long = MAX_BODY): ByteArray = retry {
        val c = open(url, "GET")
        try {
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

    /** Nouvelles tentatives (1 s, 3 s) sur les erreurs passagères ; les 4xx (hors 429) échouent tout de suite. */
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
            if (attempt < ATTEMPTS) Thread.sleep(if (attempt == 1) 1_000L else 3_000L)
        }
        throw last ?: IOException("Erreur réseau")
    }
}
