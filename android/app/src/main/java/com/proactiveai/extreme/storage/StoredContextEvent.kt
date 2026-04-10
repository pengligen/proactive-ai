package com.proactiveai.extreme.storage

import com.proactiveai.extreme.orchestrator.ContextEventPayload

data class StoredContextEvent(
    val id: Long,
    val eventId: String,
    val occurredAt: Long,
    val source: String,
    val category: String,
    val summary: String,
    val payloadJson: String,
    val sensitivity: String,
    val ttlSeconds: Int,
    val synced: Boolean,
)

fun StoredContextEvent.toPayload(payload: Map<String, Any>): ContextEventPayload {
    return ContextEventPayload(
        eventId = eventId,
        occurredAt = occurredAt,
        source = source,
        category = category,
        summary = summary,
        payload = payload,
        sensitivity = sensitivity,
        ttlSeconds = ttlSeconds,
    )
}
