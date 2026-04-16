package com.proactiveai.extreme.storage

import com.proactiveai.extreme.orchestrator.MobileItemPayload

data class MobileSyncItem(
    val itemId: String,
    val itemType: String,
    val title: String,
    val summary: String,
    val payload: Map<String, Any>,
    val salience: Double,
    val confidence: Double,
    val dedupeKey: String,
    val occurredAt: Long,
    val availableAt: Long = occurredAt,
    val expiresAt: Long,
)

data class StoredMobileSyncItem(
    val id: Long,
    val itemId: String,
    val itemType: String,
    val title: String,
    val summary: String,
    val payloadJson: String,
    val salience: Double,
    val confidence: Double,
    val dedupeKey: String,
    val occurredAt: Long,
    val availableAt: Long,
    val expiresAt: Long,
    val synced: Boolean,
)

fun StoredMobileSyncItem.toPayload(payload: Map<String, Any>): MobileItemPayload {
    return MobileItemPayload(
        id = itemId,
        itemType = itemType,
        title = title,
        summary = summary,
        payload = payload,
        salience = salience,
        confidence = confidence,
        dedupeKey = dedupeKey,
        occurredAt = occurredAt,
        availableAt = availableAt,
        expiresAt = expiresAt,
    )
}
