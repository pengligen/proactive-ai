package com.proactiveai.extreme.core.context.plugins

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.UUID

class NotificationUsagePlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    private var lastTopPackage: String? = null
    private var lastEmitAt: Long = 0L

    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> {
        if (!hasUsageStatsAccess()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val topUsage = latestForegroundUsage(now - LOOKBACK_WINDOW_MS, now) ?: return emptyList()
        val topPackage = topUsage.packageName

        val shouldEmit = topPackage != lastTopPackage || now - lastEmitAt > EMIT_MIN_INTERVAL_MS
        if (!shouldEmit) {
            return emptyList()
        }

        lastTopPackage = topPackage
        lastEmitAt = now

        return listOf(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "app_usage",
                summary = "Foreground app appears to be $topPackage",
                payload = mapOf(
                    "topPackage" to topPackage,
                    "lastTimeUsed" to topUsage.lastTimeUsed,
                    "totalTimeForegroundMs" to topUsage.totalTimeInForeground,
                ),
                sensitivity = Sensitivity.MEDIUM,
                ttlSeconds = 43_200,
            )
        )
    }

    private fun latestForegroundUsage(startMs: Long, endMs: Long): UsageStats? {
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startMs,
            endMs,
        )

        return stats
            .asSequence()
            .filter { it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }
    }

    private fun hasUsageStatsAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val LOOKBACK_WINDOW_MS = 10 * 60 * 1000L
        private const val EMIT_MIN_INTERVAL_MS = 90_000L
    }
}
