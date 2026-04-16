package com.proactiveai.extreme.orchestrator

import com.proactiveai.extreme.core.model.RiskLevel

data class ContextEventPayload(
    val eventId: String,
    val occurredAt: Long,
    val source: String,
    val category: String,
    val summary: String,
    val payload: Map<String, Any>,
    val sensitivity: String,
    val ttlSeconds: Int,
)

data class EventBatchPayload(
    val userId: String,
    val sessionId: String?,
    val events: List<ContextEventPayload>,
)

data class ActionPlanPayload(
    val planId: String,
    val goal: String,
    val riskLevel: RiskLevel,
    val requiresUserConfirmation: Boolean,
    val explainWhy: String,
    val steps: List<ActionStepPayload>,
    val raw: Map<String, Any?>,
)

data class ActionStepPayload(
    val stepId: String,
    val connector: String,
    val operation: String,
    val args: Map<String, Any>,
)

data class PlanRequestPayload(
    val userId: String,
    val now: Long,
    val contextWindow: List<ContextEventPayload>,
)

data class HitlDecisionPayload(
    val planId: String,
    val approved: Boolean,
    val note: String? = null,
)

data class ActionExecutionRequestPayload(
    val planId: String,
    val stepId: String,
)

data class ActionExecutionResultPayload(
    val status: String,
    val details: String?,
)

data class ConnectorStatusPayload(
    val connector: String,
    val connected: Boolean,
    val accountLabel: String?,
    val lastConnectedAt: Long?,
)

data class ConnectorAuthorizeResultPayload(
    val userId: String,
    val connector: String,
    val connected: Boolean,
    val authUrl: String?,
    val message: String,
)

data class MobileItemPayload(
    val id: String,
    val itemType: String,
    val title: String,
    val summary: String,
    val payload: Map<String, Any>,
    val salience: Double,
    val confidence: Double,
    val dedupeKey: String,
    val occurredAt: Long,
    val availableAt: Long,
    val expiresAt: Long,
)

data class MobileItemBatchPayload(
    val deviceId: String,
    val items: List<MobileItemPayload>,
)
