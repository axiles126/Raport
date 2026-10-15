package com.example.report.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Device heading (azimuth, 0..360°) from the rotation-vector sensor, low-pass smoothed.
 * Returns null while [enabled] is false or before the first sensor event.
 *
 * TODO(decomp): only this function lives in Compass.kt in the APK (inlined into MapScreen);
 *  the "Компас" button / "курс N°" label UI is in MapScreen.kt.
 */
@Composable
fun rememberHeading(enabled: Boolean): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    val smooth = remember { mutableFloatStateOf(0f) }

    DisposableEffect(enabled) {
        if (!enabled) {
            heading = null
            return@DisposableEffect onDispose { }
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, orient)
                val deg = ((Math.toDegrees(orient[0].toDouble()) + 360.0) % 360.0).toFloat()
                // Shortest-arc exponential smoothing (α = 0.15).
                val diff = (deg - smooth.floatValue + 540f) % 360f - 180f
                smooth.floatValue = (smooth.floatValue + diff * 0.15f + 360f) % 360f
                heading = smooth.floatValue
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null && sm != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm?.unregisterListener(listener) }
    }
    return heading
}
