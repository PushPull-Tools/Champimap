package fr.pushpull.champimap.connect.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import fr.pushpull.champimap.connect.ConnectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * Service de premier plan : garde Connect en vie pendant la synchronisation, même quand l'écran
 * est fermé (mode arrière-plan). Notification « Synchronisation… x % » avec bouton Annuler ;
 * le service s'arrête tout seul à la fin.
 */
class SyncService : Service() {
    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIF_ID = 1
        private const val ACTION_CANCEL = "fr.pushpull.champimap.connect.CANCEL"

        /** Démarre le service (sans effet visible si la synchro se termine très vite). */
        fun start(context: Context) {
            val i = Intent(context, SyncService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            SyncEngine.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        val first = build(SyncEngine.state.value.percent, SyncEngine.state.value.step)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            SyncEngine.state.collectLatest { s ->
                if (!s.running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build(s.percent, s.step))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Canal de notification discret (pas de son). */
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Synchronisation", NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** Notification d'avancement : appui = écran Connect, bouton = Annuler. */
    private fun build(percent: Int, step: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Champimap Connect · $percent %")
            .setContentText(step)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Annuler", cancel).build())
            .build()
    }
}
