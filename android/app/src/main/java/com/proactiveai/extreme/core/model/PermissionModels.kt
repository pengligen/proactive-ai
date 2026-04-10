package com.proactiveai.extreme.core.model

enum class PermissionGate {
    RUNTIME_DIALOG,
    SETTINGS_PAGE,
    HEALTH_CONNECT,
    ROLE_REQUIRED,
}

enum class PermissionCategory {
    LOCATION,
    MOTION,
    AUDIO,
    HEALTH,
    COMMUNICATION,
    MEDIA,
    NOTIFICATION,
    APP_USAGE,
    CONNECTIVITY,
}

data class PermissionDescriptor(
    val id: String,
    val title: String,
    val androidPermission: String?,
    val category: PermissionCategory,
    val gate: PermissionGate,
    val purpose: String,
    val ttlHoursDefault: Int,
)
