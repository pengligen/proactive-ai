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

class HealthConnectPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin, SensorEventListener {
    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private var stepSensor: Sensor? = null
    private var latestStepCounter: Float? = null
    private var lastEmittedStepCounter: Float? = null

    override suspend fun start(): Boolean {
        if (!hasBodySensorsPermission()) {
            return false
        }

        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        return true
    }

    override suspend fun stop() {
        sensorManager.unregisterListener(this)
    }

    override suspend fun poll(): List<ContextEvent> {
        val current = latestStepCounter ?: return emptyList()
        val previous = lastEmittedStepCounter
        if (previous != null && current <= previous) {
            return emptyList()
        }

        lastEmittedStepCounter = current
        val delta = if (previous == null) 0f else current - previous

        return listOf(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                source = descriptor.id,
                category = "health",
                summary = "Step counter update: total=${current.toInt()} delta=${delta.toInt()}",
                payload = mapOf(
                    "stepCounterTotal" to current.toDouble(),
                    "stepCounterDelta" to delta.toDouble(),
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 86_400,
            )
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val value = event?.values?.firstOrNull() ?: return
        latestStepCounter = value
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasBodySensorsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.BODY_SENSORS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
