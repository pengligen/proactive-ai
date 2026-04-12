package com.proactiveai.extreme.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.orchestrator.ActionExecutionRequestPayload
import com.proactiveai.extreme.orchestrator.HttpOrchestratorGateway
import com.proactiveai.extreme.storage.ActionHistoryStore
import com.proactiveai.extreme.storage.ActionQueueStore
import kotlin.math.pow

class ActionExecutionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (AppPrefs.isGlobalLockEnabled(applicationContext)) {
            return Result.success()
        }

        val queueStore = ActionQueueStore.getInstance(applicationContext)
        val historyStore = ActionHistoryStore.getInstance(applicationContext)
        val gateway = HttpOrchestratorGateway(appContext = applicationContext)

        val runnable = queueStore.runnable(limit = 10)
        if (runnable.isEmpty()) {
            return Result.success()
        }

        runnable.forEach { queued ->
            queueStore.markRunning(queued.id)

            val execution = gateway.executeStep(
                ActionExecutionRequestPayload(
                    planId = queued.planId,
                    stepId = queued.stepId,
                )
            ).getOrNull()

            if (execution?.status == "SUCCESS") {
                queueStore.markSucceeded(queued.id, execution.details)
                AppPrefs.recordExecution(applicationContext, successful = true)
                historyStore.insert(
                    eventType = "QUEUE_EXEC",
                    planId = queued.planId,
                    stepId = queued.stepId,
                    summary = "Queued execution succeeded",
                    detail = "${queued.connector}.${queued.operation} ${execution.details.orEmpty()}",
                )
            } else {
                val error = execution?.details ?: "backend unreachable or request error"
                val currentAttempt = queued.attemptCount + 1
                val remaining = queued.maxAttempts - currentAttempt
                val nextRetryAt = if (remaining > 0) {
                    System.currentTimeMillis() + backoffMs(currentAttempt)
                } else {
                    Long.MAX_VALUE
                }
                queueStore.markFailed(queued.id, error, nextRetryAt)
                AppPrefs.recordExecution(applicationContext, successful = false)
                historyStore.insert(
                    eventType = "QUEUE_FAIL",
                    planId = queued.planId,
                    stepId = queued.stepId,
                    summary = "Queued execution failed",
                    detail = "attempt=$currentAttempt/${queued.maxAttempts}, error=$error",
                )
            }
        }

        val hasMoreRunnable = queueStore.runnable(limit = 1).isNotEmpty()
        return if (hasMoreRunnable) Result.retry() else Result.success()
    }

    private fun backoffMs(attempt: Int): Long {
        val base = 60_000.0
        val exp = (2.0).pow((attempt - 1).coerceAtLeast(0).toDouble())
        return (base * exp).toLong().coerceAtMost(30 * 60_000L)
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "action_execution_periodic"
        const val UNIQUE_IMMEDIATE_WORK = "action_execution_immediate"
    }
}
