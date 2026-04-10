package com.proactiveai.extreme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.context.engine.ContextEngine
import com.proactiveai.extreme.core.context.engine.DefaultContextPluginFactory
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

class ProactiveCollectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contextEngine by lazy {
        ContextEngine(DefaultContextPluginFactory.create(this))
    }

    private var running = false
    private var stopRequested = false
    private var restartAttempted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START, null -> {
                stopRequested = false
                restartAttempted = false
                startCollectionLoop()
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        recoverIfNeeded()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        runBlocking {
            kotlin.runCatching { contextEngine.stopAll() }
        }
        serviceScope.cancel()
        recoverIfNeeded()
        super.onDestroy()
    }

    private fun startCollectionLoop() {
        if (running) return
        running = true
        restartAttempted = false

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Collecting context every 1 minute in Extreme Mode"))
        SyncScheduler.ensurePeriodic(this)

        serviceScope.launch {
            while (isActive && running) {
                val masterEnabled = AppPrefs.isMasterEnabled(this@ProactiveCollectionService)
                if (masterEnabled) {
                    val enabledPlugins = AppPrefs.getEnabledPlugins(this@ProactiveCollectionService)
                    contextEngine.startEnabled(enabledPlugins)
                    val eventsByPlugin = contextEngine.collectTickByPlugin(enabledPlugins)
                    val events = eventsByPlugin.values.flatten().sortedBy { it.occurredAt }
                    val finalEvents = buildList {
                        if (events.isEmpty()) {
                            add(heartbeatEvent())
                        }
                        addAll(events)
                        add(contextLogEvent(enabledPlugins, eventsByPlugin))
                    }

                    ContextEventStore.getInstance(this@ProactiveCollectionService).insertAll(finalEvents)
                    SyncScheduler.enqueueImmediate(this@ProactiveCollectionService)
                }

                delay(COLLECTION_INTERVAL_MS)
            }
        }
    }

    private fun heartbeatEvent(): ContextEvent {
        return ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            source = "collection_service",
            category = "device_state",
            summary = "Service heartbeat while monitoring context.",
            payload = mapOf("masterEnabled" to AppPrefs.isMasterEnabled(this)),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 86_400,
        )
    }

    private fun contextLogEvent(
        enabledPluginIds: Set<String>,
        eventsByPlugin: Map<String, List<ContextEvent>>,
    ): ContextEvent {
        val now = System.currentTimeMillis()
        val allEvents = eventsByPlugin.values.flatten().sortedBy { it.occurredAt }
        val categoryCounts = allEvents.groupingBy { it.category }.eachCount()
        val pluginCounts = enabledPluginIds
            .sorted()
            .associateWith { pluginId -> eventsByPlugin[pluginId]?.size ?: 0 }

        val categoryDigest = if (categoryCounts.isEmpty()) {
            "none"
        } else {
            categoryCounts.entries
                .sortedByDescending { it.value }
                .joinToString(separator = ",") { "${it.key}:${it.value}" }
        }

        val detailLines = if (allEvents.isEmpty()) {
            listOf("- no context emitted by enabled plugins in this tick")
        } else {
            allEvents.map { event ->
                "- [${event.source}] ${event.category} | ${event.summary.take(220)}"
            }
        }

        val summary = buildString {
            append("1m context tick | plugins=${enabledPluginIds.size} | events=${allEvents.size} | categories=$categoryDigest")
            append("\n")
            append(detailLines.joinToString(separator = "\n"))
        }

        return ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = now,
            source = "collection_service",
            category = "context_log",
            summary = summary,
            payload = mapOf(
                "intervalMs" to COLLECTION_INTERVAL_MS,
                "enabledPlugins" to enabledPluginIds.sorted(),
                "eventsByPlugin" to pluginCounts,
                "eventsByCategory" to categoryCounts,
                "events" to allEvents.map { event ->
                    mapOf(
                        "eventId" to event.eventId,
                        "occurredAt" to event.occurredAt,
                        "source" to event.source,
                        "category" to event.category,
                        "summary" to event.summary,
                        "payload" to event.payload,
                        "sensitivity" to event.sensitivity.name,
                    )
                },
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 7 * 24 * 3600,
        )
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("ProactiveAI Extreme")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proactive Collection",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setSound(null, AudioAttributes.Builder().build())
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun recoverIfNeeded() {
        val shouldRecover = !stopRequested &&
            !running &&
            !restartAttempted &&
            AppPrefs.isCollectionEnabled(this) &&
            AppPrefs.isMasterEnabled(this)

        if (!shouldRecover) {
            return
        }

        restartAttempted = true
        kotlin.runCatching { start(this) }
    }

    companion object {
        private const val CHANNEL_ID = "proactive_collection"
        private const val NOTIFICATION_ID = 1001
        private const val COLLECTION_INTERVAL_MS = 60_000L

        const val ACTION_START = "com.proactiveai.extreme.service.START"
        const val ACTION_STOP = "com.proactiveai.extreme.service.STOP"

        fun start(context: Context) {
            AppPrefs.setCollectionEnabled(context, true)
            val intent = Intent(context, ProactiveCollectionService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            AppPrefs.setCollectionEnabled(context, false)
            val intent = Intent(context, ProactiveCollectionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
