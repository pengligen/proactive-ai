package com.proactiveai.extreme.data

import com.proactiveai.extreme.core.model.PermissionCategory
import com.proactiveai.extreme.core.model.PermissionDescriptor
import com.proactiveai.extreme.core.model.PermissionGate
import com.proactiveai.extreme.core.model.PluginDescriptor
import com.proactiveai.extreme.core.model.RiskLevel

object ExtremeDefaults {
    fun plugins(): List<PluginDescriptor> {
        return listOf(
            PluginDescriptor(
                id = "location_motion",
                title = "Location + Motion",
                description = "Track movement state, commute pattern, and place context.",
                enabledByDefault = true,
                riskLevel = RiskLevel.MEDIUM,
                permissions = listOf(
                    runtime("fine_location", "Fine location", "android.permission.ACCESS_FINE_LOCATION", PermissionCategory.LOCATION, "Enable precise location context.", 24),
                    runtime("coarse_location", "Coarse location", "android.permission.ACCESS_COARSE_LOCATION", PermissionCategory.LOCATION, "Enable low-power location fallback.", 24),
                    runtime("background_location", "Background location", "android.permission.ACCESS_BACKGROUND_LOCATION", PermissionCategory.LOCATION, "Allow context updates when app is not visible.", 24),
                    runtime("activity_recognition", "Activity recognition", "android.permission.ACTIVITY_RECOGNITION", PermissionCategory.MOTION, "Detect walking, driving, and still state.", 24),
                )
            ),
            PluginDescriptor(
                id = "audio_ambient",
                title = "Audio Ambient",
                description = "Capture voice environment cues for proactive understanding.",
                enabledByDefault = true,
                riskLevel = RiskLevel.CRITICAL,
                permissions = listOf(
                    runtime("record_audio", "Microphone", "android.permission.RECORD_AUDIO", PermissionCategory.AUDIO, "Capture ambient speech/audio with explicit foreground indicator.", 12)
                )
            ),
            PluginDescriptor(
                id = "health_connect",
                title = "Health Connect",
                description = "Read steps, sleep, and physiology context from Health Connect.",
                enabledByDefault = true,
                riskLevel = RiskLevel.HIGH,
                permissions = listOf(
                    runtime("body_sensors", "Body sensors", "android.permission.BODY_SENSORS", PermissionCategory.HEALTH, "Allow direct sensor data access.", 24),
                    settings("health_connect", "Health Connect grants", PermissionCategory.HEALTH, PermissionGate.HEALTH_CONNECT, "Grant granular health record reads.", 168),
                )
            ),
            PluginDescriptor(
                id = "notification_usage",
                title = "Notification + Usage",
                description = "Read notification stream and app usage timeline.",
                enabledByDefault = true,
                riskLevel = RiskLevel.HIGH,
                permissions = listOf(
                    runtime("post_notifications", "Post notifications", "android.permission.POST_NOTIFICATIONS", PermissionCategory.NOTIFICATION, "Display actionable assistant alerts.", 24),
                    settings("notification_listener", "Notification listener", PermissionCategory.NOTIFICATION, PermissionGate.SETTINGS_PAGE, "Read incoming notifications for context.", 24),
                    settings("usage_stats", "Usage stats access", PermissionCategory.APP_USAGE, PermissionGate.SETTINGS_PAGE, "Observe app focus and work mode.", 24),
                )
            ),
            PluginDescriptor(
                id = "media_notebook",
                title = "Media + Local files",
                description = "Index photos and local notes/files for memory grounding.",
                enabledByDefault = true,
                riskLevel = RiskLevel.HIGH,
                permissions = listOf(
                    runtime("read_images", "Photos", "android.permission.READ_MEDIA_IMAGES", PermissionCategory.MEDIA, "Read image metadata and selected content.", 720),
                    runtime("read_video", "Videos", "android.permission.READ_MEDIA_VIDEO", PermissionCategory.MEDIA, "Read video metadata and selected content.", 720),
                    runtime("read_audio", "Audio library", "android.permission.READ_MEDIA_AUDIO", PermissionCategory.MEDIA, "Read local audio metadata.", 720),
                )
            ),
            PluginDescriptor(
                id = "communication",
                title = "Communication context",
                description = "Enable contacts, calendar, and optional sms/call context.",
                enabledByDefault = true,
                riskLevel = RiskLevel.CRITICAL,
                permissions = listOf(
                    runtime("contacts", "Contacts", "android.permission.READ_CONTACTS", PermissionCategory.COMMUNICATION, "Resolve people and relationship graph.", 720),
                    runtime("calendar_read", "Calendar read", "android.permission.READ_CALENDAR", PermissionCategory.COMMUNICATION, "Track meetings and deadlines.", 720),
                    runtime("calendar_write", "Calendar write", "android.permission.WRITE_CALENDAR", PermissionCategory.COMMUNICATION, "Create or update events with approval.", 720),
                    runtime("read_sms", "Read SMS", "android.permission.READ_SMS", PermissionCategory.COMMUNICATION, "Parse transactional and personal message context.", 168),
                    runtime("send_sms", "Send SMS", "android.permission.SEND_SMS", PermissionCategory.COMMUNICATION, "Send messages under policy gate.", 24),
                    runtime("receive_sms", "Receive SMS", "android.permission.RECEIVE_SMS", PermissionCategory.COMMUNICATION, "Listen for inbound SMS context.", 24),
                    runtime("call_log", "Call log", "android.permission.READ_CALL_LOG", PermissionCategory.COMMUNICATION, "Use call timing and recency as context.", 168),
                )
            ),
            PluginDescriptor(
                id = "nearby_connectivity",
                title = "Nearby + Connectivity",
                description = "Understand nearby devices and local connectivity state.",
                enabledByDefault = true,
                riskLevel = RiskLevel.MEDIUM,
                permissions = listOf(
                    runtime("bt_scan", "Bluetooth scan", "android.permission.BLUETOOTH_SCAN", PermissionCategory.CONNECTIVITY, "Detect nearby devices and movement context.", 24),
                    runtime("bt_connect", "Bluetooth connect", "android.permission.BLUETOOTH_CONNECT", PermissionCategory.CONNECTIVITY, "Read and maintain connection status.", 24),
                    runtime("nearby_wifi", "Nearby Wi-Fi", "android.permission.NEARBY_WIFI_DEVICES", PermissionCategory.CONNECTIVITY, "Use nearby networks as place context.", 24),
                )
            ),
        )
    }

    private fun runtime(
        id: String,
        title: String,
        androidPermission: String,
        category: PermissionCategory,
        purpose: String,
        ttlHoursDefault: Int,
    ) = PermissionDescriptor(
        id = id,
        title = title,
        androidPermission = androidPermission,
        category = category,
        gate = PermissionGate.RUNTIME_DIALOG,
        purpose = purpose,
        ttlHoursDefault = ttlHoursDefault,
    )

    private fun settings(
        id: String,
        title: String,
        category: PermissionCategory,
        gate: PermissionGate,
        purpose: String,
        ttlHoursDefault: Int,
    ) = PermissionDescriptor(
        id = id,
        title = title,
        androidPermission = null,
        category = category,
        gate = gate,
        purpose = purpose,
        ttlHoursDefault = ttlHoursDefault,
    )
}
