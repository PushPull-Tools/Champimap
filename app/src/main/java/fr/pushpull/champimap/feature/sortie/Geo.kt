package fr.pushpull.champimap.feature.sortie

import fr.pushpull.champimap.core.db.TrackPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Calculs géographiques et mise en forme des textes (distance, durée, coordonnées).
 * Fonctions pures, sans Android : réutilisables par tous les modules.
 */
object Geo {

    /** Rayon moyen de la Terre (mètres). */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distance en mètres entre deux points (formule de haversine, précise à quelques cm à cette échelle). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Cap (0 = nord, 90 = est) pour aller du point 1 au point 2, en degrés 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Longueur d'une trace en mètres. Les points trop imprécis (> 30 m) sont ignorés
     * et les petits sauts (< 2 m, bruit du GPS à l'arrêt) ne sont pas comptés.
     */
    fun trackLengthM(points: List<TrackPoint>): Double {
        var total = 0.0
        var prev: TrackPoint? = null
        for (p in points) {
            if (p.accuracyM > 30f) continue
            val last = prev
            if (last != null) {
                val d = distanceM(last.lat, last.lon, p.lat, p.lon)
                if (d >= 2.0) {
                    total += d
                    prev = p
                }
            } else prev = p
        }
        return total
    }

    /** « 850 m » ou « 1,2 km ». */
    fun formatDistance(m: Double): String = when {
        m < 100 -> "${m.roundToInt()} m"
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        m < 10_000 -> String.format(Locale.FRANCE, "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    /** « 1 h 12 », « 12 min » (durée en millisecondes). */
    fun formatDuration(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val min = totalMin % 60
        return if (h > 0) "$h h ${min.toString().padStart(2, '0')}" else "$min min"
    }

    /** Coordonnées décimales à la française : « 45,83412 ; 1,26145 ». */
    fun formatCoords(lat: Double, lon: Double): String =
        String.format(Locale.FRANCE, "%.5f ; %.5f", lat, lon)

    /** Une coordonnée en degrés, minutes, secondes : « 45° 50′ 2,8″ N ». */
    fun formatDms(value: Double, isLat: Boolean): String {
        val hemi = if (isLat) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "O")
        val a = abs(value)
        val deg = a.toInt()
        val minFull = (a - deg) * 60
        val min = minFull.toInt()
        val sec = (minFull - min) * 60
        return String.format(Locale.FRANCE, "%d° %d′ %.1f″ %s", deg, min, sec, hemi)
    }

    /** Écart d'angle signé (-180..180) : utile pour tourner une flèche. */
    fun angleDiff(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0
}
