package com.proactiveai.extreme.storage

data class ActionHistoryEntry(
    val id: Long,
    val createdAt: Long,
    val eventType: String,
    val planId: String?,
    val stepId: String?,
    val summary: String,
    val detail: String?,
)
