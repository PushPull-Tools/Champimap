package fr.pushpull.champimap.connect.download

import fr.pushpull.champimap.connect.data.Dept
import fr.pushpull.champimap.connect.net.Http
import java.io.IOException

/*
 * Contour d'un département (GeoJSON) depuis le dépôt public france-geojson (données IGN / OSM),
 * servi par GitHub. Le fichier est renvoyé tel quel à Champimap (contour_<code>.geojson).
 */
object ContourDownloader {
    private const val BASE = "https://raw.githubusercontent.com/gregoiredavid/france-geojson/master/departements"

    /** Télécharge le contour du département et vérifie sommairement que c'est bien du GeoJSON. */
    fun download(dept: Dept): ByteArray {
        val url = "$BASE/${dept.slug}/departement-${dept.slug}.geojson"
        val bytes = Http.get(url, maxBytes = 16L * 1024 * 1024)
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8)
        if (!head.contains("\"type\"")) throw IOException("Contour invalide reçu")
        return bytes
    }
}
