package com.proactiveai.extreme.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.model.RiskLevel
import com.proactiveai.extreme.orchestrator.EventBatchPayload
import com.proactiveai.extreme.orchestrator.HttpOrchestratorGateway
import com.proactiveai.extreme.orchestrator.PlanRequestPayload
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.storage.ActionQueueStore
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.storage.toPayload
import org.json.JSONObject

class OrchestratorSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (AppPrefs.isGlobalLockEnabled(applicationContext)) {
            return Result.success()
        }

        val store = ContextEventStore.getInstance(applicationContext)
        store.pruneExpired()

        val unsynced = store.getUnsynced(limit = 100)
        if (unsynced.isEmpty()) {
            return Result.success()
        }

        val payload = EventBatchPayload(
            userId = AppPrefs.getUserId(applicationContext),
            sessionId = "android-session",
            events = unsynced.map { event ->
                val payloadMap = jsonStringToPayloadMap(event.payloadJson)
                event.toPayload(payloadMap)
            },
        )

        val gateway = HttpOrchestratorGateway(appContext = applicationContext)
        val accepted = gateway.ingestEvents(payload).getOrElse { return Result.retry() }
        if (accepted <= 0) {
            return Result.retry()
        }

        val syncedIds = unsynced.take(accepted).map { it.id }
        store.markSynced(syncedIds)

        val recentContextWindow = store.getRecent(limit = 60).map { event ->
            event.toPayload(jsonStringToPayloadMap(event.payloadJson))
        }

        val userId = AppPrefs.getUserId(applicationContext)
        val plan = gateway.requestPlan(
            PlanRequestPayload(
                userId = userId,
                now = System.currentTimeMillis(),
                contextWindow = recentContextWindow,
            )
        ).getOrNull()

        if (
            plan != null &&
            AppPrefs.isAutoExecuteLowRisk(applicationContext) &&
            plan.riskLevel == RiskLevel.LOW &&
            !plan.requiresUserConfirmation
        ) {
            val queueStore = ActionQueueStore.getInstance(applicationContext)
            plan.steps.forEach { step ->
                queueStore.enqueue(planId = plan.planId, step = step)
            }
            ActionExecutionScheduler.enqueueImmediate(applicationContext)
        }

        return Result.success()
    }

    private fun jsonStringToPayloadMap(payloadJson: String): Map<String, Any> {
        val raw = JSONObject(payloadJson).toMap()
        return normalizeAnyMap(raw)
    }

    private fun normalizeAnyMap(input: Map<String, Any?>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        input.forEach { (key, value) ->
            val normalized = normalizeAny(value) ?: return@forEach
            result[key] = normalized
        }
        return result
    }

    private fun normalizeAny(value: Any?): Any? {
        return when (value) {
            null -> null
            is Map<*, *> -> {
                val nested = mutableMapOf<String, Any>()
                value.forEach { (k, v) ->
                    val normalized = normalizeAny(v) ?: return@forEach
                    if (k != null) {
                        nested[k.toString()] = normalized
                    }
                }
                nested
            }
            is List<*> -> value.mapNotNull { normalizeAny(it) }
            else -> value
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "orchestrator_periodic_sync"
        const val UNIQUE_IMMEDIATE_WORK = "orchestrator_immediate_sync"
    }
}
