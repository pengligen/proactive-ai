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
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.storage.StoredContextEvent
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
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class ProactiveCollectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contextEngine by lazy {
        ContextEngine(DefaultContextPluginFactory.create(this))
    }

    private var running = false
    private var stopRequested = false
    private var restartAttempted = false
    private var assistantAutoRunning = false
    private var dailyFocusAutoRunning = false
    private var cloudRefineRunning = false
    private var lastAudioGateSignature = ""
    private var lastAudioGateEventAt = 0L

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
                    val store = ContextEventStore.getInstance(this@ProactiveCollectionService)
                    val configuredPlugins = AppPrefs.getEnabledPlugins(this@ProactiveCollectionService)
                    val audioGateDecision = buildAudioGateDecision(
                        store = store,
                        configuredPlugins = configuredPlugins,
                    )
                    val runtimePlugins = if (audioGateDecision.allowAudio) {
                        configuredPlugins
                    } else {
                        configuredPlugins - AUDIO_PLUGIN_ID
                    }

                    contextEngine.startEnabled(runtimePlugins)
                    val eventsByPlugin = contextEngine.collectTickByPlugin(runtimePlugins)
                    val events = eventsByPlugin.values.flatten().sortedBy { it.occurredAt }
                    val finalEvents = buildList {
                        if (events.isEmpty()) {
                            add(heartbeatEvent())
                        }
                        addAll(events)
                        add(contextLogEvent(runtimePlugins, eventsByPlugin))
                        maybeBuildAudioGateEvent(audioGateDecision)?.let { add(it) }
                    }

                    store.insertAll(finalEvents)
                    SyncScheduler.enqueueImmediate(this@ProactiveCollectionService)
                    maybeRunCloudSpeechRefine()
                    maybeRunAssistantAutoSession()
                    maybeRunDailyFocusTop3()
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

    private fun maybeRunAssistantAutoSession() {
        if (assistantAutoRunning) return
        assistantAutoRunning = true
        serviceScope.launch {
            try {
                AssistantSessionAutoRunner.runIfDue(this@ProactiveCollectionService)
            } catch (_: Throwable) {
            } finally {
                assistantAutoRunning = false
            }
        }
    }

    private fun maybeRunCloudSpeechRefine() {
        if (cloudRefineRunning) return
        cloudRefineRunning = true
        serviceScope.launch {
            try {
                CloudSpeechTranscriptionRefiner.runIfDue(this@ProactiveCollectionService)
            } catch (_: Throwable) {
            } finally {
                cloudRefineRunning = false
            }
        }
    }

    private fun maybeRunDailyFocusTop3() {
        if (dailyFocusAutoRunning) return
        dailyFocusAutoRunning = true
        serviceScope.launch {
            try {
                DailyFocusTop3AutoRunner.runIfDue(this@ProactiveCollectionService)
            } catch (_: Throwable) {
            } finally {
                dailyFocusAutoRunning = false
            }
        }
    }

    private fun buildAudioGateDecision(
        store: ContextEventStore,
        configuredPlugins: Set<String>,
    ): AudioGateDecision {
        if (AUDIO_PLUGIN_ID !in configuredPlugins) {
            return AudioGateDecision(
                allowAudio = false,
                reason = "audio_plugin_disabled",
                payload = mapOf("configured" to false),
            )
        }

        val now = System.currentTimeMillis()
        val recentRows = store.getRecent(limit = 320)
            .filter { row -> now - row.occurredAt <= AUDIO_GATE_LOOKBACK_MS }

        val sensorPayload = recentRows
            .firstOrNull { row -> row.source == "sensor_fusion" || row.category == "sensor" }
            ?.payloadMap()
            .orEmpty()
        val devicePayload = recentRows
            .firstOrNull { row -> row.source == "system_device" || row.category == "device_state" }
            ?.payloadMap()
            .orEmpty()
        val locationPayload = recentRows
            .firstOrNull { row -> row.source == "location_motion" || row.category == "location" }
            ?.payloadMap()
            .orEmpty()

        val activityState = payloadString(sensorPayload, "activityState")
        val ambientState = payloadString(sensorPayload, "ambientState")
        val lightLux = payloadDouble(sensorPayload, "lightLux")
        val stepDelta = payloadInt(sensorPayload, "stepDelta")
        val motionState = payloadString(locationPayload, "motionState")
        val isInteractive = payloadBoolean(devicePayload, "isInteractive")
        val isDeviceIdle = payloadBoolean(devicePayload, "isDeviceIdleMode")

        val hasRecentSpeech = recentRows.any { row ->
            row.category == "audio" &&
                now - row.occurredAt <= AUDIO_GATE_SPEECH_RECENT_MS &&
                (
                    row.summary.startsWith("Ambient speech transcript", ignoreCase = true) ||
                        row.summary.contains("cloud-refined", ignoreCase = true)
                    )
        }

        val motionActive = activityState in setOf("walking", "running", "moving") ||
            stepDelta > 0 ||
            motionState in setOf("walking", "running", "driving", "cycling_or_transit")
        val stillLike = (activityState.isBlank() || activityState in setOf("still", "unknown")) &&
            stepDelta <= 0 &&
            (motionState.isBlank() || motionState in setOf("still", "unknown"))
        val darkLike = ambientState.startsWith("dark") || (lightLux != null && lightLux < 10.0)
        val indoorLike = ambientState.contains("indoor") || ambientState.startsWith("dark") || (lightLux != null && lightLux < 80.0)
        val screenInactive = (isInteractive == false) || (isDeviceIdle == true)
        val indoorInactive = indoorLike && stillLike && screenInactive && !hasRecentSpeech
        val gateByDarkIndoorInactive = darkLike && indoorInactive
        val allowAudio = !gateByDarkIndoorInactive

        val reason = if (allowAudio) {
            "active_or_partial_context"
        } else {
            "dark_and_indoor_inactive"
        }

        return AudioGateDecision(
            allowAudio = allowAudio,
            reason = reason,
            payload = mapOf(
                "configured" to true,
                "activityState" to activityState.ifBlank { "unknown" },
                "ambientState" to ambientState.ifBlank { "unknown" },
                "lightLux" to (lightLux ?: -1.0),
                "stepDelta" to stepDelta,
                "motionState" to motionState.ifBlank { "unknown" },
                "isInteractive" to (isInteractive ?: "unknown"),
                "isDeviceIdleMode" to (isDeviceIdle ?: "unknown"),
                "hasRecentSpeech" to hasRecentSpeech,
                "motionActive" to motionActive,
                "darkLike" to darkLike,
                "indoorLike" to indoorLike,
                "stillLike" to stillLike,
                "screenInactive" to screenInactive,
                "indoorInactive" to indoorInactive,
                "gateByDarkIndoorInactive" to gateByDarkIndoorInactive,
            ),
        )
    }

    private fun maybeBuildAudioGateEvent(decision: AudioGateDecision): ContextEvent? {
        val now = System.currentTimeMillis()
        val signature = "${decision.allowAudio}|${decision.reason}|${decision.payload["hour"]}|${decision.payload["activityState"]}|${decision.payload["ambientState"]}|${decision.payload["motionState"]}"
        val shouldEmit = signature != lastAudioGateSignature || now - lastAudioGateEventAt >= AUDIO_GATE_EMIT_INTERVAL_MS
        if (!shouldEmit) return null

        lastAudioGateSignature = signature
        lastAudioGateEventAt = now

        val mode = if (decision.allowAudio) "ACTIVE" else "PAUSED"
        return ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = now,
            source = "collection_service",
            category = "audio_gate",
            summary = "Audio gate $mode | reason=${decision.reason}",
            payload = mapOf(
                "allowAudio" to decision.allowAudio,
                "reason" to decision.reason,
            ) + decision.payload,
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 7 * 24 * 3600,
        )
    }

    private fun StoredContextEvent.payloadMap(): Map<String, Any?> {
        return kotlin.runCatching { JSONObject(payloadJson).toMap() }.getOrDefault(emptyMap())
    }

    private fun payloadString(payload: Map<String, Any?>, key: String): String {
        return payload[key]?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
    }

    private fun payloadBoolean(payload: Map<String, Any?>, key: String): Boolean? {
        return when (val value = payload[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase(Locale.US)) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun payloadDouble(payload: Map<String, Any?>, key: String): Double? {
        return when (val value = payload[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun payloadInt(payload: Map<String, Any?>, key: String): Int {
        return when (val value = payload[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: 0
    }

    private data class AudioGateDecision(
        val allowAudio: Boolean,
        val reason: String,
        val payload: Map<String, Any>,
    )

    companion object {
        private const val CHANNEL_ID = "proactive_collection"
        private const val NOTIFICATION_ID = 1001
        private const val COLLECTION_INTERVAL_MS = 60_000L
        private const val AUDIO_PLUGIN_ID = "audio_ambient"
        private const val AUDIO_GATE_LOOKBACK_MS = 15 * 60_000L
        private const val AUDIO_GATE_SPEECH_RECENT_MS = 10 * 60_000L
        private const val AUDIO_GATE_EMIT_INTERVAL_MS = 5 * 60_000L

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
