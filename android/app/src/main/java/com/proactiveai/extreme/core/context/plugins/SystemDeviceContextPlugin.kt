package com.proactiveai.extreme.core.context.plugins

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PermissionGate
import com.proactiveai.extreme.core.model.PluginDescriptor
import com.proactiveai.extreme.data.ExtremeDefaults
import com.proactiveai.extreme.permission.PermissionStatusResolver
import java.util.UUID

class SystemDeviceContextPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val alarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private var lastSignature: String = ""
    private var lastCapabilitySignature: String = ""
    private var lastEmitAt: Long = 0L
    private var lastCapabilityEmitAt: Long = 0L

    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> {
        val now = System.currentTimeMillis()
        val events = mutableListOf<ContextEvent>()

        val battery = readBatterySnapshot()
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val storage = StatFs(context.filesDir.absolutePath)
        val orientation = context.resources.configuration.orientation
        val orientationLabel = when (orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }

        val ringerMode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val dndFilter = interruptionFilterLabel(notificationManager.currentInterruptionFilter)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalLabel(powerManager.currentThermalStatus)
        } else {
            "unknown"
        }

        val payload = mutableMapOf<String, Any>(
            "batteryPct" to battery.levelPct,
            "charging" to battery.isCharging,
            "plugType" to battery.plugType,
            "temperatureC" to battery.temperatureC,
            "voltageMv" to battery.voltageMv,
            "isInteractive" to powerManager.isInteractive,
            "isPowerSaveMode" to powerManager.isPowerSaveMode,
            "isDeviceIdleMode" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager.isDeviceIdleMode else false),
            "ringerMode" to ringerMode,
            "isMusicActive" to audioManager.isMusicActive,
            "dndFilter" to dndFilter,
            "orientation" to orientationLabel,
            "thermalStatus" to thermalStatus,
            "memAvailMb" to memory.availMem / (1024 * 1024),
            "memTotalMb" to memory.totalMem / (1024 * 1024),
            "memLow" to memory.lowMemory,
            "storageAvailMb" to storage.availableBytes / (1024 * 1024),
            "storageTotalMb" to storage.totalBytes / (1024 * 1024),
            "nfcEnabled" to isNfcEnabled(),
            "exactAlarmAllowed" to canScheduleExactAlarm(),
            "overlayAllowed" to Settings.canDrawOverlays(context),
            "writeSettingsAllowed" to Settings.System.canWrite(context),
            "allFilesAccess" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true),
            "ignoreBatteryOptimization" to powerManager.isIgnoringBatteryOptimizations(context.packageName),
            "notificationPolicyAccess" to notificationManager.isNotificationPolicyAccessGranted,
        )

        val signature = listOf(
            battery.levelPct,
            battery.isCharging,
            powerManager.isInteractive,
            powerManager.isPowerSaveMode,
            ringerMode,
            dndFilter,
            orientationLabel,
            thermalStatus,
            payload["nfcEnabled"],
            payload["exactAlarmAllowed"],
            payload["overlayAllowed"],
            payload["writeSettingsAllowed"],
            payload["ignoreBatteryOptimization"],
        ).joinToString("|")

        val shouldEmitState = signature != lastSignature || now - lastEmitAt > STATE_EMIT_INTERVAL_MS
        if (shouldEmitState) {
            lastSignature = signature
            lastEmitAt = now
            events += ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "device_state",
                summary = "Device state battery=${battery.levelPct}% charging=${battery.isCharging} ringer=$ringerMode dnd=$dndFilter powerSave=${powerManager.isPowerSaveMode} orientation=$orientationLabel",
                payload = payload,
                sensitivity = Sensitivity.MEDIUM,
                ttlSeconds = 43_200,
            )
        }

        val capabilityEvent = buildCapabilityCoverageEvent(now)
        if (capabilityEvent != null) {
            events += capabilityEvent
        }

        return events
    }

    private fun readBatterySnapshot(): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugType = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        val tempTenth = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        return BatterySnapshot(
            levelPct = pct,
            isCharging = charging,
            plugType = plugType,
            temperatureC = tempTenth / 10f,
            voltageMv = voltage,
        )
    }

    private fun buildCapabilityCoverageEvent(now: Long): ContextEvent? {
        val permissions = ExtremeDefaults.plugins()
            .flatMap { plugin ->
                plugin.permissions.map { permission ->
                    Triple(plugin.id, permission.id, PermissionStatusResolver.isGranted(context, permission))
                }
            }
        val granted = permissions.count { it.third }
        val total = permissions.size
        val missing = permissions
            .filterNot { it.third }
            .map { "${it.first}.${it.second}" }
            .take(30)

        val signature = "$granted/$total|${missing.joinToString(",")}"
        val shouldEmit = signature != lastCapabilitySignature ||
            now - lastCapabilityEmitAt > CAPABILITY_EMIT_INTERVAL_MS
        if (!shouldEmit) return null

        lastCapabilitySignature = signature
        lastCapabilityEmitAt = now

        return ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = now,
            source = descriptor.id,
            category = "capability_status",
            summary = "Capability coverage granted=$granted/$total missing=${missing.size}",
            payload = mapOf(
                "grantedCount" to granted,
                "totalCount" to total,
                "missing" to missing,
                "settingsGatedMissing" to permissions.filter {
                    !it.third && isSettingsGatedPermission(it.first, it.second)
                }.map { "${it.first}.${it.second}" },
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 7 * 24 * 3600,
        )
    }

    private fun isSettingsGatedPermission(pluginId: String, permissionId: String): Boolean {
        val permission = ExtremeDefaults.plugins()
            .firstOrNull { it.id == pluginId }
            ?.permissions
            ?.firstOrNull { it.id == permissionId }
            ?: return false
        return permission.gate == PermissionGate.SETTINGS_PAGE
    }

    private fun isNfcEnabled(): Boolean {
        return kotlin.runCatching {
            val adapterClass = Class.forName("android.nfc.NfcAdapter")
            val method = adapterClass.getMethod("getDefaultAdapter", Context::class.java)
            val adapter = method.invoke(null, context) ?: return false
            val isEnabled = adapterClass.getMethod("isEnabled")
            isEnabled.invoke(adapter) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun canScheduleExactAlarm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            kotlin.runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
    }

    private fun thermalLabel(status: Int): String {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun interruptionFilterLabel(value: Int): String {
        return when (value) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            else -> "unknown"
        }
    }

    private data class BatterySnapshot(
        val levelPct: Int,
        val isCharging: Boolean,
        val plugType: String,
        val temperatureC: Float,
        val voltageMv: Int,
    )

    companion object {
        private const val STATE_EMIT_INTERVAL_MS = 90_000L
        private const val CAPABILITY_EMIT_INTERVAL_MS = 15 * 60_000L
    }
}
