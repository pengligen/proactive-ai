package com.proactiveai.extreme.core.context.plugins

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.UUID

class LocationMotionPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var lastSignature: String? = null
    private var lastEmitAt: Long = 0L

    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> {
        if (!hasAnyLocationPermission()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val location = bestLastKnownLocation() ?: return emptyList()

        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f)
        } else {
            null
        }

        val motionState = inferMotionState(speedKmh)
        val signature = buildString {
            append("${location.latitude.format4()}")
            append("|")
            append("${location.longitude.format4()}")
            append("|")
            append(motionState)
        }

        val shouldEmit = signature != lastSignature || now - lastEmitAt > EMIT_MIN_INTERVAL_MS
        if (!shouldEmit) {
            return emptyList()
        }

        lastSignature = signature
        lastEmitAt = now

        return listOf(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "location",
                summary = "Location ${location.latitude.format4()}, ${location.longitude.format4()} | motion=$motionState",
                payload = buildMap {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy.toDouble())
                    put("provider", location.provider ?: "unknown")
                    put("motionState", motionState)
                    if (speedKmh != null) {
                        put("speedKmh", speedKmh.toDouble())
                    }
                },
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 86_400,
            )
        )
    }

    private fun hasAnyLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun bestLastKnownLocation(): Location? {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }

        val candidates = providers.mapNotNull { provider ->
            kotlin.runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }

        return candidates.maxWithOrNull(compareBy<Location> { it.time }.thenByDescending { -it.accuracy })
    }

    private fun inferMotionState(speedKmh: Float?): String {
        if (speedKmh == null) return "unknown"
        return when {
            speedKmh >= 20f -> "driving"
            speedKmh >= 4f -> "walking"
            else -> "still"
        }
    }

    private fun Double.format4(): String = "%.4f".format(this)

    companion object {
        private const val EMIT_MIN_INTERVAL_MS = 90_000L
    }
}
