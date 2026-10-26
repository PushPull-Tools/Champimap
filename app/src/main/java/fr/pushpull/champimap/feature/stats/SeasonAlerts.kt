package fr.pushpull.champimap.feature.stats

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.pushpull.champimap.R
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/*
 * Alertes de saison : une vérification par jour (WorkManager), 100 % locale.
 * Si un spot a bien donné à ± 7 jours de la date du jour lors d'une saison précédente,
 * notification « Ton spot <nom> donnait bien à cette période l'an dernier ».
 * Ne fait rien si l'option « Alertes de saison » est coupée dans les réglages.
 * Chaque spot n'est signalé qu'une fois tous les 30 jours (mémorisé localement).
 */
object SeasonAlertScheduler {

    private const val WORK_NAME = "champimap_season_alerts"
    const val CHANNEL_ID = "season_alerts"

    /**
     * Programme la vérification quotidienne (idempotent : sans effet si déjà programmée).
     * À appeler au démarrage de l'appli. La vérification elle-même respecte `AppSettings.seasonAlerts`.
     */
    fun schedule(context: Context) {
        createChannel(context)
        val request = PeriodicWorkRequestBuilder<SeasonAlertWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Annule la vérification quotidienne (facultatif : le travail s'arrête déjà de lui-même si l'option est coupée). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    /** Crée le canal de notification « Alertes de saison » (Android 8+, sans effet s'il existe). */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Alertes de saison", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tes spots qui donnaient bien à cette période les années précédentes"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

/** Vérification quotidienne des alertes de saison. */
class SeasonAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val settings = AppGraph.settings.flow.first()
            if (!settings.seasonAlerts) return Result.success()
            if (!canNotify()) return Result.success()

            val today = LocalDate.now()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val harvests = AppGraph.db.statsDao().harvests()
            val candidates = PastYears.goodSpotsAround(harvests, today, WINDOW_DAYS)
                .filter { it.spotStatus != "DETRUIT" && it.spotStatus != "EPUISE" }
                .filter { today.toEpochDay() - prefs.getLong(it.spotId, Long.MIN_VALUE / 2) >= REPEAT_DAYS }
                .take(MAX_PER_DAY)

            SeasonAlertScheduler.createChannel(applicationContext)
            candidates.forEach { spot ->
                notify(spot, today, settings.discreetMode)
                prefs.edit().putLong(spot.spotId, today.toEpochDay()).apply()
            }
            Result.success()
        } catch (e: Exception) {
            ErrorLogger.log("Alertes de saison", "Vérification quotidienne impossible", e)
            Result.success() // on réessaiera demain, inutile d'insister
        }
    }

    /** Notifications autorisées (permission Android 13+ et réglage système). */
    private fun canNotify(): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** Affiche la notification d'un spot. En mode discret, le nom du spot n'apparaît pas. */
    @SuppressLint("MissingPermission") // vérifiée dans canNotify()
    private fun notify(spot: PastSpot, today: LocalDate, discreet: Boolean) {
        val ctx = applicationContext
        val whenText = if (spot.lastDate.year == today.year - 1) "l'an dernier" else "en ${spot.lastDate.year}"
        val text = if (discreet) "Un de tes spots donnait bien à cette période $whenText"
        else "Ton spot ${spot.spotName} donnait bien à cette période $whenText"

        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = launch?.let {
            PendingIntent.getActivity(ctx, spot.spotId.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(ctx, SeasonAlertScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("C'est peut-être le moment !")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_BASE + (spot.spotId.hashCode() and 0xFFFF), notification)
    }

    private companion object {
        const val PREFS = "season_alerts"
        const val WINDOW_DAYS = 7
        const val REPEAT_DAYS = 30L
        const val MAX_PER_DAY = 3
        const val NOTIF_BASE = 41_000
    }
}
