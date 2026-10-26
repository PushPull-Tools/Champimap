package fr.pushpull.champimap.feature.sortie

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Boussole : cap du téléphone (0 = nord géographique), et flèche qui pointe vers une cible.
 * Les capteurs ne tournent QUE pendant que la flèche est à l'écran (`active`), pour la batterie.
 */

/**
 * Cap du téléphone en degrés (0..360, nord géographique), ou null si pas de boussole.
 * @param active false → capteurs arrêtés.
 * @param lat/lon position (correction de la déclinaison magnétique), facultative.
 */
@Composable
fun rememberHeading(active: Boolean, lat: Double? = null, lon: Double? = null): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }
    // Déclinaison magnétique (≈ +1° à +2° en France), recalculée quand la position change nettement.
    val declination = remember(lat?.let { Math.round(it * 10) }, lon?.let { Math.round(it * 10) }) {
        if (lat != null && lon != null) {
            GeomagneticField(lat.toFloat(), lon.toFloat(), 300f, System.currentTimeMillis()).declination
        } else 0f
    }

    DisposableEffect(active, declination) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!active || sm == null || sensor == null) {
            onDispose { }
        } else {
            val rot = FloatArray(9)
            val remapped = FloatArray(9)
            val orient = FloatArray(3)
            var smoothed: Float? = null
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rot, event.values)
                    // Tient compte de l'orientation de l'écran (portrait / paysage).
                    val (ax, ay) = when (displayRotation(context)) {
                        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    }
                    SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
                    SensorManager.getOrientation(remapped, orient)
                    val raw = ((Math.toDegrees(orient[0].toDouble()).toFloat() + declination) + 360f) % 360f
                    // Lissage : évite que la flèche tremble.
                    val prev = smoothed
                    val next = if (prev == null) raw else {
                        val diff = Geo.angleDiff(raw.toDouble(), prev.toDouble()).toFloat()
                        (prev + diff * 0.15f + 360f) % 360f
                    }
                    smoothed = next
                    heading.value = next
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }
    return heading
}

/** Rotation actuelle de l'écran. */
@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

/**
 * Flèche orientée (en degrés, 0 = vers le haut de l'écran). Dessinée en Canvas, sans image.
 */
@Composable
fun DirectionArrow(rotationDeg: Float, modifier: Modifier = Modifier, size: Dp = 64.dp, color: Color = Color(0xFFE08A2E)) {
    Canvas(modifier.size(size)) {
        rotate(rotationDeg) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w / 2f, h * 0.06f)
                lineTo(w * 0.86f, h * 0.88f)
                lineTo(w / 2f, h * 0.68f)
                lineTo(w * 0.14f, h * 0.88f)
                close()
            }
            drawPath(path, color)
            drawCircle(Color.White.copy(alpha = 0.85f), radius = w * 0.06f, center = Offset(w / 2f, h * 0.55f))
        }
    }
}
