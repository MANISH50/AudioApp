package io.spatia.app.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Head yaw from a headset's head-tracker sensor (Sensor.TYPE_HEAD_TRACKER, Android 13+), which compatible
 * headsets expose as a dynamic sensor. Started only while spatial head tracking is enabled.
 */
class HeadTrackerSource(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    @Volatile var yawRad: Double = 0.0
        private set
    private var offset = 0.0
    private var rawYaw = 0.0
    private var sensor: Sensor? = null
    private var listening = false
    var onYaw: ((Double) -> Unit)? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.values.size < 3) return
            rawYaw = yawFromRotationVector(e.values[0], e.values[1], e.values[2])
            yawRad = wrap(rawYaw - offset)
            onYaw?.invoke(yawRad)
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
    }

    private val dynCallback = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(s: Sensor) {
            if (isHeadTracker(s)) { sensor = s; _available.value = true; if (listening) register() }
        }
        override fun onDynamicSensorDisconnected(s: Sensor) {
            if (s == sensor) { sm.unregisterListener(listener); sensor = null; _available.value = false; yawRad = 0.0 }
        }
    }

    val isSupportedByOs: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun start() {
        if (!isSupportedByOs || listening) return
        listening = true
        sm.registerDynamicSensorCallback(dynCallback, Handler(Looper.getMainLooper()))
        sensor = sm.getDynamicSensorList(Sensor.TYPE_ALL).firstOrNull(::isHeadTracker)
        _available.value = sensor != null
        register()
    }

    fun stop() {
        if (!listening) return
        listening = false
        sm.unregisterListener(listener)
        sm.unregisterDynamicSensorCallback(dynCallback)
        yawRad = 0.0
        onYaw?.invoke(0.0)
    }

    fun recenter() { offset = rawYaw; yawRad = 0.0 }

    private fun register() {
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun isHeadTracker(s: Sensor) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s.type == Sensor.TYPE_HEAD_TRACKER

    companion object {
        /**
         * The head-tracker frame is X = right ear, Y = nose, Z = up; values are an Euler (axis * angle) vector.
         * Returns yaw with + = head turned right.
         */
        fun yawFromRotationVector(x: Float, y: Float, z: Float): Double {
            val angle = sqrt((x * x + y * y + z * z).toDouble())
            if (angle < 1e-9) return 0.0
            val s = sin(angle / 2) / angle
            val qw = cos(angle / 2); val qx = x * s; val qy = y * s; val qz = z * s
            val yawCcw = atan2(2 * (qw * qz + qx * qy), 1 - 2 * (qy * qy + qz * qz))
            return -yawCcw
        }

        fun wrap(r: Double): Double {
            var v = r
            while (v > Math.PI) v -= 2 * Math.PI
            while (v < -Math.PI) v += 2 * Math.PI
            return v
        }
    }
}
