package com.proactiveai.extreme.core.model

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

data class PluginDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val enabledByDefault: Boolean,
    val riskLevel: RiskLevel,
    val permissions: List<PermissionDescriptor>,
)
