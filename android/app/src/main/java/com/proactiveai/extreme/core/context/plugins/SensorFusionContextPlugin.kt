package com.proactiveai.extreme.core.context.plugins

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
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
    private val impactTimesMs = ArrayDeque<Long>()
    private val shakeImpactTimesMs = ArrayDeque<Long>()
    private val pendingSparklingTriggers = ArrayDeque<SparklingTrigger>()
    private var lastTapCandidateAt: Long = 0L
    private var lastDoubleTapAt: Long = 0L
    private var lastShakeAt: Long = 0L
    private var maxLinearAccelSincePoll = 0f
    private var maxDeltaAccelSincePoll = 0f
    private var maxImpactScoreSincePoll = 0f
    private var impactCountSincePoll = 0
    private var shakeCandidateCountSincePoll = 0
    private var lastAccelSample: FloatArray? = null

    override suspend fun start(): Boolean {
        availableSensors.clear()
        sensorTypes.forEach { type ->
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                availableSensors[type] = sensor
                val samplingDelay = when (type) {
                    Sensor.TYPE_ACCELEROMETER,
                    Sensor.TYPE_GYROSCOPE,
                    -> SensorManager.SENSOR_DELAY_GAME
                    else -> SensorManager.SENSOR_DELAY_NORMAL
                }
                sensorManager.registerListener(this, sensor, samplingDelay)
            }
        }
        return availableSensors.isNotEmpty()
    }

    override suspend fun stop() {
        sensorManager.unregisterListener(this)
        availableSensors.clear()
        latestValues.clear()
        impactTimesMs.clear()
        shakeImpactTimesMs.clear()
        pendingSparklingTriggers.clear()
        lastTapCandidateAt = 0L
        lastDoubleTapAt = 0L
        lastShakeAt = 0L
        maxLinearAccelSincePoll = 0f
        maxDeltaAccelSincePoll = 0f
        maxImpactScoreSincePoll = 0f
        impactCountSincePoll = 0
        shakeCandidateCountSincePoll = 0
        lastAccelSample = null
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
        val events = mutableListOf<ContextEvent>()
        if (shouldEmit) {
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

            events += ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "sensor",
                summary = "Sensor fusion activity=$activityState ambient=$ambientState steps+$stepDelta light=${round1(light ?: -1f)}",
                payload = payload,
                sensitivity = Sensitivity.MEDIUM,
                ttlSeconds = 43_200,
            )
        }

        events += drainSparklingEvents(
            now = now,
            activityState = activityState,
            ambientState = ambientState,
            stepDelta = stepDelta,
            light = light,
            proximity = proximity,
            pressure = pressure,
            humidity = humidity,
            temperature = temperature,
        )
        resetSparklingWindowStats()
        return events
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        latestValues[sensorEvent.sensor.type] = sensorEvent.values.copyOf()
        if (sensorEvent.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            detectSparklingFromAccelerometer(sensorEvent.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun detectSparklingFromAccelerometer(accelValues: FloatArray) {
        val now = System.currentTimeMillis()
        val accelMag = magnitude(accelValues)
        val gyroMag = magnitude(latestValues[Sensor.TYPE_GYROSCOPE])
        val linearAccelMag = abs(accelMag - SensorManager.GRAVITY_EARTH)
        val deltaAccelMag = magnitudeDelta(accelValues, lastAccelSample)
        lastAccelSample = accelValues.copyOf()

        maxLinearAccelSincePoll = maxOf(maxLinearAccelSincePoll, linearAccelMag)
        maxDeltaAccelSincePoll = maxOf(maxDeltaAccelSincePoll, deltaAccelMag)
        val impactScore = maxOf(linearAccelMag, deltaAccelMag)
        maxImpactScoreSincePoll = maxOf(maxImpactScoreSincePoll, impactScore)
        if (impactScore < IMPACT_MIN_THRESHOLD) return

        impactCountSincePoll += 1
        impactTimesMs.addLast(now)
        pruneOldTimestamps(impactTimesMs, now, IMPACT_WINDOW_MS)

        if (impactScore >= SHAKE_IMPACT_THRESHOLD) {
            shakeCandidateCountSincePoll += 1
            shakeImpactTimesMs.addLast(now)
            pruneOldTimestamps(shakeImpactTimesMs, now, SHAKE_WINDOW_MS)
        }

        if (shouldEmitShake(now)) {
            enqueueSparklingTrigger(
                type = "shake",
                detectedAt = now,
                linearAccelMag = linearAccelMag,
                deltaAccelMag = deltaAccelMag,
                impactScore = impactScore,
                accelMag = accelMag,
                gyroMag = gyroMag,
            )
            lastShakeAt = now
            lastTapCandidateAt = 0L
            impactTimesMs.clear()
            shakeImpactTimesMs.clear()
            return
        }

        val recentShake = now - lastShakeAt <= SHAKE_POST_BUFFER_MS
        if (recentShake) return
        if (impactScore !in DOUBLE_TAP_IMPACT_MIN..DOUBLE_TAP_IMPACT_MAX) return

        if (shouldEmitDoubleTap(now)) {
            enqueueSparklingTrigger(
                type = "double_tap",
                detectedAt = now,
                linearAccelMag = linearAccelMag,
                deltaAccelMag = deltaAccelMag,
                impactScore = impactScore,
                accelMag = accelMag,
                gyroMag = gyroMag,
            )
            lastDoubleTapAt = now
            lastTapCandidateAt = 0L
        } else {
            lastTapCandidateAt = now
        }
    }

    private fun shouldEmitShake(now: Long): Boolean {
        if (now - lastShakeAt < SHAKE_COOLDOWN_MS) return false
        return shakeImpactTimesMs.size >= SHAKE_MIN_IMPACTS
    }

    private fun shouldEmitDoubleTap(now: Long): Boolean {
        if (now - lastDoubleTapAt < DOUBLE_TAP_COOLDOWN_MS) return false
        if (lastTapCandidateAt <= 0L) return false
        val delta = now - lastTapCandidateAt
        return delta in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_MAX_GAP_MS
    }

    private fun enqueueSparklingTrigger(
        type: String,
        detectedAt: Long,
        linearAccelMag: Float,
        deltaAccelMag: Float,
        impactScore: Float,
        accelMag: Float,
        gyroMag: Float,
    ) {
        pendingSparklingTriggers.addLast(
            SparklingTrigger(
                type = type,
                detectedAt = detectedAt,
                linearAccelMag = linearAccelMag,
                deltaAccelMag = deltaAccelMag,
                impactScore = impactScore,
                accelMag = accelMag,
                gyroMag = gyroMag,
            )
        )
        Log.i(
            TAG,
            "sparkling queued type=$type impact=${"%.2f".format(Locale.US, impactScore)} linear=${"%.2f".format(Locale.US, linearAccelMag)} delta=${"%.2f".format(Locale.US, deltaAccelMag)} pending=${pendingSparklingTriggers.size}",
        )
        while (pendingSparklingTriggers.size > MAX_PENDING_SPARKLING_TRIGGERS) {
            pendingSparklingTriggers.removeFirst()
        }
    }

    private fun pruneOldTimestamps(queue: ArrayDeque<Long>, now: Long, windowMs: Long) {
        while (queue.isNotEmpty()) {
            val first = queue.peekFirst() ?: break
            if (now - first <= windowMs) break
            queue.removeFirst()
        }
    }

    private fun drainSparklingEvents(
        now: Long,
        activityState: String,
        ambientState: String,
        stepDelta: Int,
        light: Float?,
        proximity: Float?,
        pressure: Float?,
        humidity: Float?,
        temperature: Float?,
    ): List<ContextEvent> {
        if (pendingSparklingTriggers.isEmpty()) return emptyList()
        val events = mutableListOf<ContextEvent>()
        while (pendingSparklingTriggers.isNotEmpty()) {
            val trigger = pendingSparklingTriggers.removeFirst()
            val summary = when (trigger.type) {
                "shake" -> "SPARKLING marker triggered: shake gesture detected"
                "double_tap" -> "SPARKLING marker triggered: double tap detected"
                else -> "SPARKLING marker triggered"
            }

            val payload = mutableMapOf<String, Any>(
                "sparkling" to true,
                "sparklingSessionHint" to true,
                "trigger" to trigger.type,
                "triggerAtMs" to trigger.detectedAt,
                "triggerAgeMs" to (now - trigger.detectedAt).coerceAtLeast(0L),
                "linearAccelMagnitude" to trigger.linearAccelMag.toDouble(),
                "deltaAccelMagnitude" to trigger.deltaAccelMag.toDouble(),
                "impactScore" to trigger.impactScore.toDouble(),
                "accelMagnitude" to trigger.accelMag.toDouble(),
                "gyroMagnitude" to trigger.gyroMag.toDouble(),
                "activityState" to activityState,
                "ambientState" to ambientState,
                "stepDelta" to stepDelta,
            )
            light?.let { payload["lightLux"] = it.toDouble() }
            proximity?.let { payload["proximityCm"] = it.toDouble() }
            pressure?.let { payload["pressureHpa"] = it.toDouble() }
            humidity?.let { payload["humidityPct"] = it.toDouble() }
            temperature?.let { payload["ambientTempC"] = it.toDouble() }

            events += ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = trigger.detectedAt,
                source = descriptor.id,
                category = "sparkling",
                summary = summary,
                payload = payload,
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
            Log.i(
                TAG,
                "sparkling emitted type=${trigger.type} impact=${"%.2f".format(Locale.US, trigger.impactScore)} occurredAt=${trigger.detectedAt}",
            )
        }
        return events
    }

    private fun resetSparklingWindowStats() {
        maxLinearAccelSincePoll = 0f
        maxDeltaAccelSincePoll = 0f
        maxImpactScoreSincePoll = 0f
        impactCountSincePoll = 0
        shakeCandidateCountSincePoll = 0
    }

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

    private fun magnitudeDelta(current: FloatArray?, previous: FloatArray?): Float {
        if (current == null || previous == null || current.size < 3 || previous.size < 3) return 0f
        val dx = current[0] - previous[0]
        val dy = current[1] - previous[1]
        val dz = current[2] - previous[2]
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun round1(value: Float): Float = (value * 10f).toInt() / 10f
    private fun round2(value: Float): Float = (value * 100f).toInt() / 100f

    private data class SparklingTrigger(
        val type: String,
        val detectedAt: Long,
        val linearAccelMag: Float,
        val deltaAccelMag: Float,
        val impactScore: Float,
        val accelMag: Float,
        val gyroMag: Float,
    )

    companion object {
        private const val TAG = "SparklingTrigger"
        private const val EMIT_INTERVAL_MS = 90_000L
        private const val IMPACT_MIN_THRESHOLD = 1.0f
        private const val IMPACT_WINDOW_MS = 1_800L
        private const val SHAKE_IMPACT_THRESHOLD = 3.2f
        private const val SHAKE_WINDOW_MS = 1_800L
        private const val SHAKE_MIN_IMPACTS = 1
        private const val SHAKE_COOLDOWN_MS = 2_200L
        private const val SHAKE_POST_BUFFER_MS = 350L
        private const val DOUBLE_TAP_IMPACT_MIN = 1.2f
        private const val DOUBLE_TAP_IMPACT_MAX = 8.8f
        private const val DOUBLE_TAP_MIN_GAP_MS = 30L
        private const val DOUBLE_TAP_MAX_GAP_MS = 750L
        private const val DOUBLE_TAP_COOLDOWN_MS = 900L
        private const val MAX_PENDING_SPARKLING_TRIGGERS = 8
    }
}
