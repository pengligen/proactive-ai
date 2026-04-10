package com.proactiveai.extreme.permission

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
            Uri.fromParts("package", context.packageName, null),
        )
    }
}
