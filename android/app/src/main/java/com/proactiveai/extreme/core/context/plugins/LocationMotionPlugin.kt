package com.proactiveai.extreme.core.context.plugins

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.Locale
import java.util.UUID

class LocationMotionPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var lastSignature: String? = null
    private var lastLocation: Location? = null
    private var lastGeofenceCell: String? = null
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
        val bearing = if (location.hasBearing()) location.bearing else null
        val heading = bearing?.let { headingFromBearing(it) } ?: "unknown"
        val altitude = if (location.hasAltitude()) location.altitude else null

        val motionState = inferMotionState(speedKmh)
        val deltaMeters = lastLocation?.distanceTo(location)?.toDouble() ?: 0.0
        val geofenceCell = geofenceCellFor(location.latitude, location.longitude)
        val cityLabel = reverseGeocodeCity(location.latitude, location.longitude)

        val signature = buildString {
            append("${location.latitude.format4()}")
            append("|")
            append("${location.longitude.format4()}")
            append("|")
            append(motionState)
            append("|")
            append(geofenceCell)
        }

        val shouldEmit = signature != lastSignature || now - lastEmitAt > EMIT_MIN_INTERVAL_MS
        val events = mutableListOf<ContextEvent>()
        if (shouldEmit) {
            lastSignature = signature
            lastEmitAt = now
            events += ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "location",
                summary = buildString {
                    append("Location ${location.latitude.format4()}, ${location.longitude.format4()} | motion=$motionState")
                    if (!cityLabel.isNullOrBlank()) {
                        append(" | city=$cityLabel")
                    }
                },
                payload = buildMap {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy.toDouble())
                    put("provider", location.provider ?: "unknown")
                    put("motionState", motionState)
                    put("geofenceCell", geofenceCell)
                    put("trajectoryDeltaMeters", deltaMeters)
                    put("heading", heading)
                    if (speedKmh != null) put("speedKmh", speedKmh.toDouble())
                    if (bearing != null) put("bearingDeg", bearing.toDouble())
                    if (altitude != null) put("altitudeM", altitude)
                    if (!cityLabel.isNullOrBlank()) put("city", cityLabel)
                },
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 86_400,
            )
        }

        if (lastGeofenceCell != null && geofenceCell != lastGeofenceCell) {
            events += ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "location",
                summary = "Geofence transition: ${lastGeofenceCell.orEmpty()} -> $geofenceCell",
                payload = mapOf(
                    "fromCell" to lastGeofenceCell.orEmpty(),
                    "toCell" to geofenceCell,
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "deltaMeters" to deltaMeters,
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 86_400,
            )
        }

        lastGeofenceCell = geofenceCell
        lastLocation = location
        return events
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
            speedKmh >= 35f -> "driving"
            speedKmh >= 12f -> "cycling_or_transit"
            speedKmh >= 4f -> "walking"
            else -> "still"
        }
    }

    private fun headingFromBearing(bearing: Float): String {
        val normalized = ((bearing % 360f) + 360f) % 360f
        return when {
            normalized < 22.5f || normalized >= 337.5f -> "N"
            normalized < 67.5f -> "NE"
            normalized < 112.5f -> "E"
            normalized < 157.5f -> "SE"
            normalized < 202.5f -> "S"
            normalized < 247.5f -> "SW"
            normalized < 292.5f -> "W"
            else -> "NW"
        }
    }

    private fun geofenceCellFor(lat: Double, lon: Double): String {
        val latBucket = (lat * 100.0).toInt()
        val lonBucket = (lon * 100.0).toInt()
        return "${latBucket}_${lonBucket}"
    }

    private fun reverseGeocodeCity(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return kotlin.runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            val address = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            address?.locality ?: address?.subAdminArea ?: address?.adminArea
        }.getOrNull()
    }

    private fun Double.format4(): String = "%.4f".format(this)

    companion object {
        private const val EMIT_MIN_INTERVAL_MS = 90_000L
    }
}
