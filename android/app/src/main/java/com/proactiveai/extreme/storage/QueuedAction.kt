package com.proactiveai.extreme.storage

data class QueuedAction(
    val id: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val planId: String,
    val stepId: String,
    val connector: String,
    val operation: String,
    val argsJson: String,
    val status: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val nextRetryAt: Long,
    val lastError: String?,
    val lastResult: String?,
)
