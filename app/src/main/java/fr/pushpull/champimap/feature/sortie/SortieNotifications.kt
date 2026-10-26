package fr.pushpull.champimap.feature.sortie

import android.Manifest
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
import fr.pushpull.champimap.MainActivity
import fr.pushpull.champimap.R

/*
 * Notifications du module sortie :
 *  - « Sortie en cours » (permanente, discrète) avec les actions « Marquer un spot » et « Terminer » ;
 *    « Terminer » demande un second appui (« Confirmer la fin ») pendant 10 s ;
 *  - alertes de sécurité (nuit en forêt, batterie faible), sonores.
 */
class SortieNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_ONGOING = "sortie_en_cours"
        const val CHANNEL_ALERTS = "sortie_alertes"
        const val ID_ONGOING = 4201
        const val ID_ALERT_NIGHT = 4202
        const val ID_ALERT_BATTERY = 4203
    }

    /** Crée les canaux de notification (Android 8+), sans effet s'ils existent déjà. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Sortie en cours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Suivi GPS pendant une sortie, avec les raccourcis Marquer un spot et Terminer."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alertes de sécurité", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nuit qui tombe en forêt, batterie faible."
            },
        )
    }

    /** Notification permanente de la sortie. */
    fun ongoing(sortieId: String?, startedAt: Long, status: String, endRequested: Boolean): android.app.Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sortie en cours")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setContentIntent(openApp(sortieId, SortieIntents.OPEN, 1))
        if (sortieId != null) {
            b.addAction(0, "Marquer un spot", openApp(sortieId, SortieIntents.MARK_SPOT, 2))
            if (endRequested) {
                b.addAction(0, "Confirmer la fin", serviceAction(SortieTrackingService.ACTION_END_CONFIRM, 4))
                b.setContentText("Appuie encore pour terminer la sortie")
            } else {
                b.addAction(0, "Terminer", serviceAction(SortieTrackingService.ACTION_END_REQUEST, 3))
            }
        }
        return b.build()
    }

    /** Alerte de sécurité (son + vibration), qui ouvre la sortie. */
    fun alert(id: Int, title: String, text: String, sortieId: String?) {
        if (!canNotify()) return
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(sortieId, SortieIntents.OPEN, 10 + id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** Les notifications sont-elles autorisées (Android 13+) ? */
    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Ouvre l'appli (activité unique) avec une demande pour l'écran de sortie. */
    private fun openApp(sortieId: String?, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SortieIntents.EXTRA_ACTION, action)
            .putExtra(SortieIntents.EXTRA_SORTIE_ID, sortieId)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Action traitée directement par le service (sans ouvrir l'appli). */
    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, SortieTrackingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
