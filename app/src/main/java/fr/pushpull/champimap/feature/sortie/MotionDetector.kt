package fr.pushpull.champimap.feature.sortie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Détection de reprise de mouvement pendant la pause du GPS (économie de batterie).
 * 1) Capteur de « mouvement significatif » (très basse consommation, géré par une puce dédiée) ;
 * 2) sinon accéléromètre au rythme le plus lent, avec un seuil de secousse.
 * `onMove` est appelé une fois par démarrage (`start`), sur le fil principal.
 */
class MotionDetector(context: Context, private val onMove: () -> Unit) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val significant: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var active = false

    /** Un capteur est disponible : sinon on ne met jamais le GPS en pause. */
    val available: Boolean get() = significant != null || accel != null

    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) = fire()
    }

    /** Accéléromètre : moyenne glissante de la gravité, mouvement = écart franc répété. */
    private val accelListener = object : SensorEventListener {
        private var gravity = SensorManager.GRAVITY_EARTH
        private var hits = 0
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            val norm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            gravity = gravity * 0.9f + norm * 0.1f
            if (abs(norm - gravity) > 1.6f) hits++ else if (hits > 0) hits--
            if (hits >= 4) { hits = 0; fire() }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (active || sm == null) return
        active = true
        if (significant != null) {
            sm.requestTriggerSensor(trigger, significant)
        } else if (accel != null) {
            // 200 ms entre deux mesures, regroupées par paquets de 5 s pour laisser le processeur dormir.
            sm.registerListener(accelListener, accel, 200_000, 5_000_000)
        }
    }

    fun stop() {
        val manager = sm ?: return
        if (!active) return
        active = false
        if (significant != null) runCatching { manager.cancelTriggerSensor(trigger, significant) }
        runCatching { manager.unregisterListener(accelListener) }
    }

    private fun fire() {
        if (!active) return
        stop()
        onMove()
    }
}

/*
 * Détection d'IMMOBILITÉ pendant la marche (GPS allumé), pour figer la position affichée
 * et ne pas ajouter de points de bruit à la trace.
 * 1) Détecteur de pas (puce basse consommation, si présent) : un pas = on bouge ;
 * 2) accéléromètre lent : un écart franc à la gravité = on bouge.
 * Immobile = aucun pas ni secousse depuis STILL_CONFIRM_MS. `onChange` est appelé sur le fil principal.
 * Sans accéléromètre, on ne se déclare jamais immobile (le filtre de trace suffit alors).
 */
class StillnessDetector(context: Context, private val onChange: (Boolean) -> Unit) {

    companion object {
        /** Durée sans pas ni secousse (ms) avant de se déclarer immobile. */
        const val STILL_CONFIRM_MS = 8_000L
        /** Écart à la gravité (m/s²) considéré comme un mouvement (main qui tremble ≈ 0,1–0,2). */
        private const val MOVE_ACCEL_THRESHOLD = 0.6f
        /** Lissage de la gravité estimée (part de la nouvelle mesure). */
        private const val GRAVITY_ALPHA = 0.1f
        /** Mesure de l'accéléromètre toutes les 200 ms (µs). */
        private const val ACCEL_PERIOD_US = 200_000
        /** Mesures regroupées par paquets d'1 s au plus (µs) : économie sans trop de retard. */
        private const val ACCEL_LATENCY_US = 1_000_000
        /** Fréquence de vérification de l'immobilité (ms). */
        private const val CHECK_PERIOD_MS = 1_000L
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    /** Détecteur de pas : depuis Android 10, il exige la permission « activité physique » (non demandée
     *  aujourd'hui) ; sans elle on s'en passe et l'accéléromètre suffit. */
    private val stepSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.takeIf {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var lastMoveAt = 0L
    private var gravity = SensorManager.GRAVITY_EARTH

    /** Immobile en ce moment ? */
    var still = false
        private set

    /** Au moins l'accéléromètre est présent. */
    val available: Boolean get() = accel != null

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            val norm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            gravity = gravity * (1 - GRAVITY_ALPHA) + norm * GRAVITY_ALPHA
            if (abs(norm - gravity) > MOVE_ACCEL_THRESHOLD) markMove()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = markMove()
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Vérifie chaque seconde si le calme dure depuis assez longtemps. */
    private val check = object : Runnable {
        override fun run() {
            if (!active) return
            if (!still && SystemClock.elapsedRealtime() - lastMoveAt >= STILL_CONFIRM_MS) setStill(true)
            handler.postDelayed(this, CHECK_PERIOD_MS)
        }
    }

    /** Démarre la surveillance ; on part de « en mouvement ». */
    fun start() {
        val manager = sm ?: return
        if (active || accel == null) return
        active = true
        gravity = SensorManager.GRAVITY_EARTH
        markMove()
        manager.registerListener(accelListener, accel, ACCEL_PERIOD_US, ACCEL_LATENCY_US, handler)
        stepSensor?.let { runCatching { manager.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) } }
        handler.postDelayed(check, CHECK_PERIOD_MS)
    }

    /** Arrête la surveillance ; l'état repasse à « en mouvement » (rien n'est plus figé). */
    fun stop() {
        val manager = sm ?: return
        if (!active) return
        active = false
        handler.removeCallbacks(check)
        runCatching { manager.unregisterListener(accelListener) }
        runCatching { manager.unregisterListener(stepListener) }
        setStill(false)
    }

    private fun markMove() {
        lastMoveAt = SystemClock.elapsedRealtime()
        if (still) setStill(false)
    }

    private fun setStill(value: Boolean) {
        if (still == value) return
        still = value
        onChange(value)
    }
}
