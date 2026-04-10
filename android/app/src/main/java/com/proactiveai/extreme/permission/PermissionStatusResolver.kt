package com.proactiveai.extreme.permission

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.model.PermissionDescriptor
import com.proactiveai.extreme.core.model.PermissionGate

object PermissionStatusResolver {
    fun isGranted(context: Context, permission: PermissionDescriptor): Boolean {
        return when (permission.gate) {
            PermissionGate.RUNTIME_DIALOG -> isRuntimePermissionGranted(context, permission)
            PermissionGate.SETTINGS_PAGE -> isSettingsPermissionGranted(context, permission.id)
            PermissionGate.HEALTH_CONNECT -> isHealthConnectAvailable(context)
            PermissionGate.ROLE_REQUIRED -> false
        }
    }

    fun runtimePermissionsToRequest(context: Context, permissions: List<PermissionDescriptor>): List<String> {
        return permissions
            .asSequence()
            .filter { it.gate == PermissionGate.RUNTIME_DIALOG }
            .filter { shouldRequestRuntimePermission(context, it) }
            .mapNotNull { it.androidPermission }
            .distinct()
            .toList()
    }

    fun buildSettingsIntent(context: Context, permissionId: String): Intent {
        val intent = when (permissionId) {
            "usage_stats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "background_location" -> appDetailsIntent(context)
            "health_connect" -> Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
            "dnd_access" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context))
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri(context))
            "exact_alarm" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                } else {
                    appDetailsIntent(context)
                }
            }
            "battery_optimization" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
            "manage_all_files", "manage_all_files_media" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context))
                } else {
                    appDetailsIntent(context)
                }
            }
            "accessibility_service" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> appDetailsIntent(context)
        }

        return if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            appDetailsIntent(context)
        }
    }

    private fun shouldRequestRuntimePermission(context: Context, permission: PermissionDescriptor): Boolean {
        val name = permission.androidPermission ?: return false

        if (name == android.Manifest.permission.ACCESS_BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return false
        }

        if (name == android.Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        if (name == android.Manifest.permission.ACTIVITY_RECOGNITION && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        if (name == android.Manifest.permission.BODY_SENSORS_BACKGROUND && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        if (name == android.Manifest.permission.BLUETOOTH_SCAN && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        if (name == android.Manifest.permission.BLUETOOTH_CONNECT && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        if (name == android.Manifest.permission.BLUETOOTH_ADVERTISE && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        if (name == android.Manifest.permission.NEARBY_WIFI_DEVICES && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        if (name == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        if (name == "android.permission.UWB_RANGING" && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        if (name.startsWith("android.permission.READ_MEDIA_") && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        if (name == android.Manifest.permission.BODY_SENSORS && Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) {
            return false
        }

        return ContextCompat.checkSelfPermission(context, name) != PackageManager.PERMISSION_GRANTED
    }

    private fun isRuntimePermissionGranted(context: Context, permission: PermissionDescriptor): Boolean {
        val name = permission.androidPermission ?: return false

        if (name == android.Manifest.permission.ACCESS_BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        }

        if (name == android.Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        if (name == android.Manifest.permission.ACTIVITY_RECOGNITION && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }

        if (name == android.Manifest.permission.BODY_SENSORS_BACKGROUND && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        if (name == android.Manifest.permission.BLUETOOTH_SCAN && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        if (name == android.Manifest.permission.BLUETOOTH_CONNECT && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        if (name == android.Manifest.permission.BLUETOOTH_ADVERTISE && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        if (name == android.Manifest.permission.NEARBY_WIFI_DEVICES && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        if (name == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }

        if (name == "android.permission.UWB_RANGING" && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        if (name == android.Manifest.permission.READ_MEDIA_IMAGES && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (name == android.Manifest.permission.READ_MEDIA_VIDEO && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (name == android.Manifest.permission.READ_MEDIA_AUDIO && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        return ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSettingsPermissionGranted(context: Context, permissionId: String): Boolean {
        return when (permissionId) {
            "usage_stats" -> hasUsageStatsAccess(context)
            "notification_listener" -> hasNotificationListenerAccess(context)
            "background_location" -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            "dnd_access" -> {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.isNotificationPolicyAccessGranted
            }
            "overlay" -> Settings.canDrawOverlays(context)
            "write_settings" -> Settings.System.canWrite(context)
            "exact_alarm" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
            }
            "battery_optimization" -> {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
            "manage_all_files", "manage_all_files_media" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }
            }
            "accessibility_service" -> hasAccessibilityServiceAccess(context)
            else -> false
        }
    }

    private fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val packageName = context.packageName
        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component?.packageName == packageName
        }
    }

    private fun hasAccessibilityServiceAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) return false
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return services.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component?.packageName == context.packageName
        }
    }

    private fun isHealthConnectAvailable(context: Context): Boolean {
        val packageManager = context.packageManager
        return try {
            packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun appDetailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            packageUri(context),
        )
    }

    private fun packageUri(context: Context): Uri {
        return Uri.fromParts("package", context.packageName, null)
    }
}
