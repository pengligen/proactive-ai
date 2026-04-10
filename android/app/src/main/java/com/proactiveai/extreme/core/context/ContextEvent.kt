package com.proactiveai.extreme.core.context

enum class Sensitivity {
    LOW,
    MEDIUM,
    HIGH,
}

data class ContextEvent(
    val eventId: String,
    val occurredAt: Long,
    val source: String,
    val category: String,
    val summary: String,
    val payload: Map<String, Any>,
    val sensitivity: Sensitivity,
    val ttlSeconds: Int,
)

data class IntentHint(
    val label: String,
    val confidence: Float,
    val reason: String,
)
