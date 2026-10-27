package fr.pushpull.champimap.feature.spot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Mesure de position précise pour « Je suis au pied » (module spot, autonome).
 *
 * Principe :
 *  1. On écoute le GPS natif (LocationManager.GPS_PROVIDER, toutes constellations,
 *     sans Google Play Services) une fois par seconde pendant `durationMs`.
 *  2. Chaque point reçoit un poids 1 / précision² : un point à ±3 m pèse 16 fois plus
 *     qu'un point à ±12 m.
 *  3. Rejet des aberrants AVANT la moyenne finale : on écarte les points à plus de
 *     2 écarts-types du centre (écart-type estimé d'abord par la médiane, insensible aux
 *     sauts, puis recalculé sur les points sains), puis on refait la moyenne.
 *  4. Précision annoncée = racine de (dispersion pondérée² + 1/Σpoids), bornée entre 1 m
 *     et la meilleure précision brute reçue (prudent : les erreurs GPS sont corrélées).
 *
 * Le module sortie fournira un échantillonneur plus complet (PrecisePositionSampler) ;
 * cette fonction reste volontairement simple et sans dépendance.
 */

/** Avancement en direct : `fraction` 0..1, nombre de points, précision brute du dernier point et estimation courante. */
data class PreciseProgress(
    val fraction: Float,
    val samples: Int,
    val lastAccuracyM: Float?,
    val estimateAccuracyM: Float?,
)

/** Résultat d'une mesure précise. */
data class PreciseFix(val lat: Double, val lon: Double, val accuracyM: Float, val samples: Int)

/** Vrai si la permission de localisation précise est accordée. */
fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Vrai si le GPS du téléphone est activé. */
fun isGpsEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

/**
 * Mesure la position pendant `durationMs` (rester immobile) et renvoie la moyenne pondérée,
 * ou null si aucune mesure n'a été reçue (pas de permission, GPS coupé, pas de ciel).
 * `onProgress` est appelé sur le fil principal environ 4 fois par seconde.
 * Annulable : l'écoute GPS est arrêtée dès que la coroutine est annulée.
 */
@SuppressLint("MissingPermission")
suspend fun measurePrecisePosition(
    context: Context,
    durationMs: Long = 30_000L,
    onProgress: (PreciseProgress) -> Unit = {},
): PreciseFix? = withContext(Dispatchers.Main) {
    if (!hasFineLocation(context)) return@withContext null
    val lm = context.getSystemService(LocationManager::class.java) ?: return@withContext null
    if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return@withContext null

    val samples = mutableListOf<Location>()
    // Toutes les méthodes sont redéfinies : sous Android < 11, elles n'ont pas d'implémentation par défaut.
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy > 0f) samples += location
        }
        @Deprecated("Requis avant Android 11")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
    try {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            onProgress(
                PreciseProgress(
                    fraction = min(1f, elapsed.toFloat() / durationMs),
                    samples = samples.size,
                    lastAccuracyM = samples.lastOrNull()?.accuracy,
                    estimateAccuracyM = computeFix(samples)?.accuracyM,
                )
            )
            if (elapsed >= durationMs) break
            delay(250)
        }
    } finally {
        lm.removeUpdates(listener)
    }
    computeFix(samples)
}

/** Rejet des points au-delà de ce nombre d'écarts-types du centre. */
private const val OUTLIER_SIGMAS = 2.0

/** Rayon minimal de rejet (mètres) : un nuage très serré ne perd pas la moitié de ses points. */
private const val OUTLIER_MIN_RADIUS_M = 2.0

/** Écart-type radial ≈ 1,2 × distance médiane (loi de Rayleigh) : estimation insensible aux sauts. */
private const val SIGMA_FROM_MEDIAN = 1.2

/** Moyenne pondérée (1/précision²) avec rejet des points aberrants (> 2 écarts-types). */
internal fun computeFix(raw: List<Location>): PreciseFix? {
    if (raw.isEmpty()) return null
    val first = weightedMean(raw) ?: return null

    // 1) Premier tri : écart-type estimé par la médiane des distances au centre provisoire.
    val d0 = raw.map { distanceM(it.latitude, it.longitude, first.first, first.second).toDouble() }
    val sigma0 = SIGMA_FROM_MEDIAN * d0.sorted()[d0.size / 2]
    val limit0 = max(OUTLIER_MIN_RADIUS_M, OUTLIER_SIGMAS * sigma0)
    val kept1 = if (raw.size < 3) raw else raw.filterIndexed { i, _ -> d0[i] <= limit0 }.ifEmpty { raw }

    // 2) Second tri : écart-type pondéré recalculé sur les points sains, appliqué à tous les points.
    val center = weightedMean(kept1) ?: return null
    var w1 = 0.0
    var wd1 = 0.0
    kept1.forEach { loc ->
        val w = 1.0 / (loc.accuracy.toDouble() * loc.accuracy)
        val d = distanceM(loc.latitude, loc.longitude, center.first, center.second).toDouble()
        w1 += w
        wd1 += w * d * d
    }
    val limit1 = max(OUTLIER_MIN_RADIUS_M, OUTLIER_SIGMAS * sqrt(wd1 / w1))
    val kept = if (raw.size < 3) raw else raw.filter {
        distanceM(it.latitude, it.longitude, center.first, center.second) <= limit1
    }.ifEmpty { kept1 }

    val (lat, lon) = weightedMean(kept) ?: return null
    var sumW = 0.0
    var sumWd2 = 0.0
    kept.forEach { loc ->
        val w = 1.0 / (loc.accuracy.toDouble() * loc.accuracy)
        val d = distanceM(loc.latitude, loc.longitude, lat, lon).toDouble()
        sumW += w
        sumWd2 += w * d * d
    }
    val best = kept.minOf { it.accuracy }
    val est = sqrt(sumWd2 / sumW + 1.0 / sumW).toFloat()
    return PreciseFix(lat, lon, est.coerceIn(1f, max(1f, best)), kept.size)
}

/** Moyenne des coordonnées pondérée par 1/précision². */
private fun weightedMean(list: List<Location>): Pair<Double, Double>? {
    var sw = 0.0
    var sLat = 0.0
    var sLon = 0.0
    list.forEach {
        val w = 1.0 / (it.accuracy.toDouble() * it.accuracy)
        sw += w; sLat += w * it.latitude; sLon += w * it.longitude
    }
    return if (sw > 0) Pair(sLat / sw, sLon / sw) else null
}

/** Distance en mètres entre deux points (formule d'Android). */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val out = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, out)
    return out[0]
}
