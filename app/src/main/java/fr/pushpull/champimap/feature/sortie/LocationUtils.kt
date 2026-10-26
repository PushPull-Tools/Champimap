package fr.pushpull.champimap.feature.sortie

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import fr.pushpull.champimap.core.ErrorLogger

/*
 * Outils de localisation : GPS natif d'Android (LocationManager, fournisseur GPS,
 * toutes constellations), sans Google Play Services.
 */

/**
 * Écouteur de position qui implémente TOUTES les méthodes de LocationListener.
 * Indispensable : sur Android 8 à 10, les méthodes « par défaut » n'existent pas encore
 * et un simple lambda ferait planter l'appli quand le GPS change d'état.
 */
abstract class SimpleLocationListener : LocationListener {
    abstract override fun onLocationChanged(location: Location)
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Déprécié par Android, gardé pour les anciennes versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

object LocationUtils {

    /** La position précise est-elle autorisée ? */
    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Le GPS du téléphone est-il activé ? */
    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    /** Convertit une Location Android en GpsFix. */
    fun toFix(l: Location): GpsFix = GpsFix(
        lat = l.latitude,
        lon = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy else 50f,
        time = l.time,
        speedMs = if (l.hasSpeed()) l.speed else null,
    )

    /** Dernière position connue du GPS si elle date de moins de `maxAgeMs`, sinon null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context, maxAgeMs: Long = 120_000): GpsFix? {
        if (!hasFineLocation(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val l = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return null
        return if (System.currentTimeMillis() - l.time <= maxAgeMs) toFix(l) else null
    }

    /**
     * Démarre l'écoute du GPS. Renvoie l'écouteur à passer à `stop`, ou null si impossible
     * (permission refusée, GPS absent).
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context, intervalMs: Long, onFix: (GpsFix) -> Unit): LocationListener? {
        if (!hasFineLocation(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val listener = object : SimpleLocationListener() {
            override fun onLocationChanged(location: Location) = onFix(toFix(location))
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
            listener
        } catch (e: Exception) {
            ErrorLogger.log("Sortie", "Impossible de démarrer le GPS", e)
            null
        }
    }

    /** Arrête l'écoute du GPS. */
    fun stop(context: Context, listener: LocationListener?) {
        if (listener == null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(listener) }
    }
}

/**
 * Position en direct pour un écran : écoute le GPS tant que l'écran est affiché
 * (toutes les 2 s) et publie aussi dans SortieTracker. Part de la dernière position connue.
 */
@Composable
fun rememberLiveFix(intervalMs: Long = 2000): State<GpsFix?> {
    val context = LocalContext.current
    DisposableEffect(context, intervalMs) {
        LocationUtils.lastKnown(context)?.let { SortieTracker.publishFix(it) }
        val listener = LocationUtils.start(context, intervalMs) { SortieTracker.publishFix(it) }
        onDispose { LocationUtils.stop(context, listener) }
    }
    val flow = remember { SortieTracker.lastFix }
    return flow.collectAsState()
}
