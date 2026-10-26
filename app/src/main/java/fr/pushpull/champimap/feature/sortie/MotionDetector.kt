package fr.pushpull.champimap.feature.sortie

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
        if (!active || sm == null) return
        active = false
        if (significant != null) runCatching { sm.cancelTriggerSensor(trigger, significant) }
        runCatching { sm.unregisterListener(accelListener) }
    }

    private fun fire() {
        if (!active) return
        stop()
        onMove()
    }
}
