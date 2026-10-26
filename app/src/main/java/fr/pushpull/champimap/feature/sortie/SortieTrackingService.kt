package fr.pushpull.champimap.feature.sortie

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.TrackPoint
import fr.pushpull.champimap.core.newId
import fr.pushpull.champimap.core.util.AstroCalc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

/*
 * Service de premier plan qui suit la sortie en cours (et UNIQUEMENT pendant une sortie).
 *
 * - GPS natif (LocationManager, toutes constellations), un point toutes les 5 s en marche,
 *   toutes les 30 s en mode économie (batterie < 20 %).
 * - GPS en pause quand on ne bouge plus (MotionDetector) ; reprise au premier mouvement.
 * - Trace enregistrée dans la base ; position de départ complétée au premier bon point.
 * - Notification « Sortie en cours » avec « Marquer un spot » et « Terminer » (second appui).
 * - Alertes : nuit en forêt dans 45 min, batterie faible.
 */
class SortieTrackingService : Service() {

    companion object {
        const val ACTION_START = "fr.pushpull.champimap.sortie.START"
        const val ACTION_STOP = "fr.pushpull.champimap.sortie.STOP"
        const val ACTION_END_REQUEST = "fr.pushpull.champimap.sortie.END_REQUEST"
        const val ACTION_END_CONFIRM = "fr.pushpull.champimap.sortie.END_CONFIRM"
        const val EXTRA_SORTIE_ID = "sortieId"

        /** Intervalles GPS. */
        private const val INTERVAL_WALK_MS = 5_000L
        private const val INTERVAL_ECO_MS = 30_000L
        /** Immobile depuis tant de temps → pause du GPS. */
        private const val STILL_BEFORE_PAUSE_MS = 120_000L
        /** En pause, petite vérification de position quand même toutes les 4 min. */
        private const val PAUSE_CHECK_MS = 240_000L
        /** Délai pour le second appui sur « Terminer ». */
        private const val END_CONFIRM_WINDOW_MS = 10_000L
        /** Seuils d'alerte. */
        private const val NIGHT_ALERT_MIN = 45L
        private const val LOW_BATTERY_PCT = 20

        /** Arrête le suivi (fin de sortie). */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SortieTrackingService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifier: SortieNotifications
    private lateinit var motion: MotionDetector

    private var sortieId: String? = null
    /** Sortie en cours de chargement (évite de la charger deux fois). */
    private var loadingId: String? = null
    private var startedAt = 0L
    private var authorId = ""
    private var hasStart = false
    private var startLat: Double? = null
    private var startLon: Double? = null

    private var listener: LocationListener? = null
    private var currentInterval = 0L
    private var paused = false
    private var endRequested = false

    /** Point d'ancrage pour détecter l'immobilité. */
    private var anchor: GpsFix? = null
    private var anchorSince = 0L
    private var lastSaved: GpsFix? = null
    private var distanceM = 0.0

    /** Observations liées à la sortie suivie (annulées si le service reprend une autre sortie). */
    private val watchJobs = mutableListOf<Job>()

    private var nightAlertSent = false
    private var batteryAlertSent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = SortieNotifications(this)
        motion = MotionDetector(this) { onMotion() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_END_REQUEST, ACTION_END_CONFIRM -> {
                // Service relancé par un bouton de notification alors qu'il ne suivait plus rien : on s'arrête.
                if (sortieId == null) { stopSelf(); return START_NOT_STICKY }
                if (action == ACTION_END_REQUEST) onEndRequest() else onEndConfirm()
                return START_STICKY
            }
        }
        // ACTION_START, ou relance automatique par Android (intent nul) : on reprend la sortie en cours.
        val requested = intent?.getStringExtra(EXTRA_SORTIE_ID)
        if (!goForeground()) return START_NOT_STICKY
        if (requested != null && (requested == sortieId || requested == loadingId)) {
            if (requested == sortieId) resumeGps()
            return START_STICKY
        }
        loadingId = requested
        scope.launch { begin(requested); loadingId = null }
        return START_STICKY
    }

    /** Passe en premier plan. Renvoie false si Android le refuse (ex. relance en arrière-plan). */
    private fun goForeground(): Boolean = try {
        notifier.ensureChannels()
        ServiceCompat.startForeground(
            this,
            SortieNotifications.ID_ONGOING,
            notifier.ongoing(sortieId, startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), statusLine(), endRequested),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        ErrorLogger.log("Sortie", "Suivi GPS refusé par Android", e)
        stopSelf()
        false
    }

    /** Charge la sortie, reprend la distance déjà parcourue, puis démarre le GPS. */
    private suspend fun begin(requested: String?) {
        val dao = AppGraph.db.sortieDao()
        val sortie = (requested?.let { dao.get(it) } ?: dao.currentOnce())?.takeIf { it.endedAt == null && it.deletedAt == null }
        if (sortie == null) { shutdown(); return }
        stopGps()
        sortieId = sortie.id
        startedAt = sortie.startedAt
        authorId = sortie.authorId
        hasStart = sortie.startLat != null
        startLat = sortie.startLat
        startLon = sortie.startLon
        endRequested = false
        val track = dao.trackOnce(sortie.id)
        distanceM = Geo.trackLengthM(track)
        lastSaved = track.lastOrNull()?.let { GpsFix(it.lat, it.lon, it.accuracyM, it.time) }
        SortieTracker.runningSortie.value = sortie.id
        refreshBattery()
        startGps()
        handler.removeCallbacks(minuteTick)
        handler.post(minuteTick)
        // Si la sortie est terminée depuis l'appli, le service s'arrête de lui-même.
        watchJobs.forEach { it.cancel() }
        watchJobs.clear()
        watchJobs += scope.launch {
            dao.flow(sortie.id).collectLatest { s -> if (s == null || s.endedAt != null || s.deletedAt != null) shutdown() }
        }
        // Un écran demande le GPS (écran ouvert, mesure précise) : on sort de la pause.
        watchJobs += scope.launch {
            SortieTracker.wakeRequests.collectLatest { if (it > 0 && paused) resumeGps() }
        }
        updateNotification()
    }

    // ---------------------------------------------------------------------------------------------
    // GPS adaptatif
    // ---------------------------------------------------------------------------------------------

    /** Démarre le GPS au rythme voulu (5 s, ou 30 s en économie). */
    private fun startGps() {
        val interval = if (SortieTracker.economyFlow.value) INTERVAL_ECO_MS else INTERVAL_WALK_MS
        if (listener != null && interval == currentInterval) return
        LocationUtils.stop(this, listener)
        listener = LocationUtils.start(this, interval) { onFix(it) }
        currentInterval = interval
        paused = false
        SortieTracker.gpsPausedFlow.value = false
        anchor = null
    }

    private fun stopGps() {
        LocationUtils.stop(this, listener)
        listener = null
        currentInterval = 0L
    }

    /** Pause : on coupe le GPS et on attend un mouvement (capteur basse consommation). */
    private fun pauseGps() {
        if (paused) return
        stopGps()
        paused = true
        SortieTracker.gpsPausedFlow.value = true
        motion.start()
        handler.removeCallbacks(pauseCheck)
        handler.postDelayed(pauseCheck, PAUSE_CHECK_MS)
        updateNotification()
    }

    /** Reprise du GPS (mouvement détecté, écran ouvert, mesure précise…). */
    private fun resumeGps() {
        motion.stop()
        handler.removeCallbacks(pauseCheck)
        paused = false
        startGps()
        anchorSince = System.currentTimeMillis()
        updateNotification()
    }

    private fun onMotion() {
        if (paused) resumeGps()
    }

    /** Vérification périodique pendant la pause (au cas où le capteur de mouvement manquerait un départ). */
    private val pauseCheck = Runnable { if (paused) resumeGps() }

    /** Nouvelle position du GPS. */
    private fun onFix(fix: GpsFix) {
        SortieTracker.publishFix(fix)
        val id = sortieId ?: return
        if (fix.accuracyM > 50f) return

        // Départ : la voiture est ici (première position correcte si elle manquait).
        if (!hasStart && fix.accuracyM <= 30f) {
            hasStart = true
            startLat = fix.lat
            startLon = fix.lon
            scope.launch(Dispatchers.IO) { AppGraph.db.sortieDao().setStartIfMissing(id, fix.lat, fix.lon, System.currentTimeMillis()) }
        }

        // Distance (on ignore les petits sauts de bruit à l'arrêt).
        val prev = lastSaved
        val step = prev?.let { Geo.distanceM(it.lat, it.lon, fix.lat, fix.lon) } ?: 0.0
        if (prev == null || step >= 2.0 || fix.time - prev.time >= 60_000) {
            if (prev != null && fix.accuracyM <= 30f && step >= 2.0) distanceM += step
            lastSaved = fix
            val point = TrackPoint(id = newId(), sortieId = id, authorId = authorId, time = fix.time, lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM)
            scope.launch(Dispatchers.IO) {
                runCatching { AppGraph.db.sortieDao().upsertPoints(listOf(point)) }
                    .onFailure { ErrorLogger.log("Sortie", "Point de trace non enregistré", it) }
            }
        }

        // Immobilité : tout reste dans un petit rayon depuis 2 min → pause.
        val a = anchor
        val radius = maxOf(12.0, fix.accuracyM.toDouble())
        if (a == null || Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon) > radius || (fix.speedMs ?: 0f) > 0.8f) {
            anchor = fix
            anchorSince = fix.time
        } else if (fix.time - anchorSince >= STILL_BEFORE_PAUSE_MS && motion.available) {
            pauseGps()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Batterie, alertes, notification
    // ---------------------------------------------------------------------------------------------

    /** Toutes les minutes : batterie, alertes, mise à jour de la notification. */
    private val minuteTick = object : Runnable {
        override fun run() {
            refreshBattery()
            checkNightAlert()
            updateNotification()
            handler.postDelayed(this, 60_000)
        }
    }

    /** Lit le niveau de batterie ; bascule le mode économie sous 20 %. */
    private fun refreshBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        SortieTracker.batteryFlow.value = pct
        val eco = pct != null && pct < LOW_BATTERY_PCT
        if (eco != SortieTracker.economyFlow.value) {
            SortieTracker.economyFlow.value = eco
            if (!paused) { stopGps(); startGps() }
        }
        if (eco && !batteryAlertSent) {
            batteryAlertSent = true
            notifier.alert(
                SortieNotifications.ID_ALERT_BATTERY,
                "Batterie faible : $pct %",
                "Mode économie activé (un point toutes les 30 s). Pense au chemin du retour.",
                sortieId,
            )
        }
    }

    /** Alerte « nuit en forêt dans 45 min », avec la distance jusqu'à la voiture. */
    private fun checkNightAlert() {
        if (nightAlertSent) return
        val fix = SortieTracker.lastFix.value ?: return
        val dark = AstroCalc.forestDark(LocalDate.now(), fix.lat, fix.lon) ?: return
        val minutesLeft = java.time.Duration.between(ZonedDateTime.now(), dark).toMinutes()
        if (minutesLeft in 0..NIGHT_ALERT_MIN) {
            nightAlertSent = true
            val sLat = startLat
            val sLon = startLon
            val dist = if (sLat != null && sLon != null) " · voiture à ${Geo.formatDistance(Geo.distanceM(fix.lat, fix.lon, sLat, sLon))}" else ""
            notifier.alert(
                SortieNotifications.ID_ALERT_NIGHT,
                "Nuit en forêt dans $minutesLeft min",
                "Il est temps de prendre le chemin du retour$dist.",
                sortieId,
            )
        }
    }

    /** Ligne d'état de la notification : distance, précision GPS, pause/économie. */
    private fun statusLine(): String {
        val parts = mutableListOf(Geo.formatDistance(distanceM) + " parcourus")
        val fix = SortieTracker.lastFix.value
        when {
            paused -> parts += "GPS en pause (immobile)"
            fix != null -> parts += "GPS ±${fix.accuracyM.toInt()} m"
        }
        if (SortieTracker.economyFlow.value) parts += "mode économie"
        return parts.joinToString(" · ")
    }

    private fun updateNotification() {
        if (sortieId == null) return
        runCatching {
            NotificationManagerCompat.from(this).notify(
                SortieNotifications.ID_ONGOING,
                notifier.ongoing(sortieId, startedAt, statusLine(), endRequested),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // « Terminer » depuis la notification (second appui de confirmation)
    // ---------------------------------------------------------------------------------------------

    private fun onEndRequest() {
        if (sortieId == null) return
        endRequested = true
        updateNotification()
        handler.removeCallbacks(cancelEndRequest)
        handler.postDelayed(cancelEndRequest, END_CONFIRM_WINDOW_MS)
    }

    private val cancelEndRequest = Runnable {
        endRequested = false
        updateNotification()
    }

    private fun onEndConfirm() {
        val id = sortieId
        if (id == null || !endRequested) { onEndRequest(); return }
        AppGraph.appScope.launch { AppGraph.db.sortieDao().end(id, System.currentTimeMillis()) }
        shutdown()
    }

    /** Arrêt complet : GPS, capteurs, minuteries, notification. */
    private fun shutdown() {
        watchJobs.forEach { it.cancel() }
        watchJobs.clear()
        stopGps()
        motion.stop()
        handler.removeCallbacksAndMessages(null)
        SortieTracker.runningSortie.value = null
        SortieTracker.gpsPausedFlow.value = false
        sortieId = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        stopGps()
        motion.stop()
        handler.removeCallbacksAndMessages(null)
        SortieTracker.runningSortie.value = null
        scope.cancel()
        super.onDestroy()
    }
}
