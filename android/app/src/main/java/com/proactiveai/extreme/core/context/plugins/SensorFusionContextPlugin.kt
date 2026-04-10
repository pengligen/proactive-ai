package com.proactiveai.extreme.core.context.plugins

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.UUID
import kotlin.math.sqrt

class SensorFusionContextPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin, SensorEventListener {
    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val sensorTypes = listOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY,
        Sensor.TYPE_STEP_COUNTER,
    )

    private val latestValues = mutableMapOf<Int, FloatArray>()
    private val availableSensors = mutableMapOf<Int, Sensor>()
    private var lastStepCounterPolled: Float? = null
    private var lastSignature: String = ""
    private var lastEmitAt: Long = 0L

    override suspend fun start(): Boolean {
        availableSensors.clear()
        sensorTypes.forEach { type ->
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                availableSensors[type] = sensor
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        return availableSensors.isNotEmpty()
    }

    override suspend fun stop() {
        sensorManager.unregisterListener(this)
        availableSensors.clear()
        latestValues.clear()
    }

    override suspend fun poll(): List<ContextEvent> {
        if (availableSensors.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        val accel = latestValues[Sensor.TYPE_ACCELEROMETER]
        val gyro = latestValues[Sensor.TYPE_GYROSCOPE]
        val light = latestValues[Sensor.TYPE_LIGHT]?.firstOrNull()
        val proximity = latestValues[Sensor.TYPE_PROXIMITY]?.firstOrNull()
        val pressure = latestValues[Sensor.TYPE_PRESSURE]?.firstOrNull()
        val humidity = latestValues[Sensor.TYPE_RELATIVE_HUMIDITY]?.firstOrNull()
        val temperature = latestValues[Sensor.TYPE_AMBIENT_TEMPERATURE]?.firstOrNull()
        val stepTotal = latestValues[Sensor.TYPE_STEP_COUNTER]?.firstOrNull()

        val accelMag = magnitude(accel)
        val gyroMag = magnitude(gyro)
        val stepDelta = computeStepDelta(stepTotal)
        val activityState = inferActivityState(accelMag, gyroMag, stepDelta)
        val ambientState = inferAmbientState(light, proximity)

        val signature = listOf(
            round1(accelMag),
            round2(gyroMag),
            round1(light ?: -1f),
            round1(proximity ?: -1f),
            round1(pressure ?: -1f),
            round1(humidity ?: -1f),
            round1(temperature ?: -1f),
            stepDelta,
            activityState,
            ambientState,
        ).joinToString("|")

        val shouldEmit = signature != lastSignature || now - lastEmitAt > EMIT_INTERVAL_MS
        if (!shouldEmit) return emptyList()
        lastSignature = signature
        lastEmitAt = now

        val payload = mutableMapOf<String, Any>(
            "activityState" to activityState,
            "ambientState" to ambientState,
            "accelMagnitude" to accelMag.toDouble(),
            "gyroMagnitude" to gyroMag.toDouble(),
            "stepDelta" to stepDelta,
            "availableSensorTypes" to availableSensors.keys.toList(),
            "activityRecognitionPermission" to hasPermission(android.Manifest.permission.ACTIVITY_RECOGNITION),
            "bodySensorsPermission" to hasPermission(android.Manifest.permission.BODY_SENSORS),
        )
        light?.let { payload["lightLux"] = it.toDouble() }
        proximity?.let { payload["proximityCm"] = it.toDouble() }
        pressure?.let { payload["pressureHpa"] = it.toDouble() }
        humidity?.let { payload["humidityPct"] = it.toDouble() }
        temperature?.let { payload["ambientTempC"] = it.toDouble() }
        stepTotal?.let { payload["stepCounterTotal"] = it.toDouble() }
        accel?.let {
            payload["accelX"] = it.getOrNull(0)?.toDouble() ?: 0.0
            payload["accelY"] = it.getOrNull(1)?.toDouble() ?: 0.0
            payload["accelZ"] = it.getOrNull(2)?.toDouble() ?: 0.0
        }
        gyro?.let {
            payload["gyroX"] = it.getOrNull(0)?.toDouble() ?: 0.0
            payload["gyroY"] = it.getOrNull(1)?.toDouble() ?: 0.0
            payload["gyroZ"] = it.getOrNull(2)?.toDouble() ?: 0.0
        }

        return listOf(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "sensor",
                summary = "Sensor fusion activity=$activityState ambient=$ambientState steps+$stepDelta light=${round1(light ?: -1f)}",
                payload = payload,
                sensitivity = Sensitivity.MEDIUM,
                ttlSeconds = 43_200,
            )
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        latestValues[sensorEvent.sensor.type] = sensorEvent.values.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun computeStepDelta(stepTotal: Float?): Int {
        if (stepTotal == null) return 0
        val previous = lastStepCounterPolled
        lastStepCounterPolled = stepTotal
        if (previous == null) return 0
        return (stepTotal - previous).toInt().coerceAtLeast(0)
    }

    private fun inferActivityState(accelMag: Float, gyroMag: Float, stepDelta: Int): String {
        return when {
            stepDelta >= 20 || accelMag > 13.5f -> "running"
            stepDelta >= 6 -> "walking"
            accelMag < 10.7f && gyroMag < 0.25f -> "still"
            gyroMag > 1.5f -> "moving"
            else -> "unknown"
        }
    }

    private fun inferAmbientState(lightLux: Float?, proximityCm: Float?): String {
        val light = lightLux ?: return "unknown"
        val near = proximityCm != null && proximityCm <= 1f
        return when {
            light < 5f && near -> "dark_near_face"
            light < 5f -> "dark"
            light < 60f -> "indoor_dim"
            light < 1000f -> "indoor_bright"
            else -> "outdoor_bright"
        }
    }

    private fun magnitude(values: FloatArray?): Float {
        if (values == null || values.size < 3) return 0f
        val x = values[0]
        val y = values[1]
        val z = values[2]
        return sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun round1(value: Float): Float = (value * 10f).toInt() / 10f
    private fun round2(value: Float): Float = (value * 100f).toInt() / 100f

    companion object {
        private const val EMIT_INTERVAL_MS = 90_000L
    }
}
