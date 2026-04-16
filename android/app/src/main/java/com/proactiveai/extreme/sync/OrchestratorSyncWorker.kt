package com.proactiveai.extreme.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.orchestrator.HttpMobileItemsGateway
import com.proactiveai.extreme.orchestrator.MobileItemBatchPayload
import com.proactiveai.extreme.orchestrator.toMap
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
        store.pruneSyncedMobileItems()

        val deviceId = AppPrefs.getDeviceId(applicationContext)
        val unsynced = store.getUnsyncedMobileItems(limit = 100)
        if (unsynced.isEmpty()) {
            return Result.success()
        }

        val payload = MobileItemBatchPayload(
            deviceId = deviceId,
            items = unsynced.map { item ->
                item.toPayload(
                    payload = jsonStringToPayloadMap(item.payloadJson),
                )
            },
        )

        val gateway = HttpMobileItemsGateway(applicationContext)
        val accepted = gateway.ingestItems(payload).getOrElse { return Result.retry() }
        if (accepted <= 0) {
            return Result.retry()
        }

        val syncedIds = unsynced.take(accepted).map { it.id }
        store.markMobileItemsSynced(syncedIds)

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
