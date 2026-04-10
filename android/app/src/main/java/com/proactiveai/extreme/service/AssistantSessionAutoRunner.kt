package com.proactiveai.extreme.service

import android.content.Context
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.core.edge.OnDeviceInferenceEngine
import com.proactiveai.extreme.orchestrator.ContextEventPayload
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.storage.toPayload
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AssistantSessionAutoRunner {
    private const val SESSION_WINDOW_MS = 15 * 60 * 1000L

    suspend fun runIfDue(context: Context) {
        val store = ContextEventStore.getInstance(context)
        val events = store.getRecent(limit = 1200)
            .mapNotNull { item ->
                val payload = safePayloadMap(item.payloadJson)
                kotlin.runCatching { item.toPayload(payload) }.getOrNull()
            }
            .filterNot { event ->
                event.source == "local_model" || event.category == "model_io" || event.category == "assistant_session"
            }

        if (events.isEmpty()) return

        val latestBucket = events.maxOf { it.occurredAt / SESSION_WINDOW_MS }
        val lastBucket = AppPrefs.getAssistantLastSessionBucket(context)
        if (lastBucket >= latestBucket) return

        val sessionStartMs = latestBucket * SESSION_WINDOW_MS
        val sessionEndMs = sessionStartMs + SESSION_WINDOW_MS
        val sessionEvents = events
            .filter { it.occurredAt in sessionStartMs until sessionEndMs }
            .sortedByDescending { it.occurredAt }

        if (sessionEvents.isEmpty()) {
            AppPrefs.setAssistantLastSessionBucket(context, latestBucket)
            return
        }

        val snapshot = buildSnapshot(sessionEvents)
        val model = EdgeModelProfile.fromId(AppPrefs.getEdgeModel(context))
        val runtimeConfig = LocalModelRuntimeConfig(
            enabled = AppPrefs.isLocalModelEnabled(context),
            backend = LocalModelBackend.fromId(AppPrefs.getLocalModelBackend(context)),
            modelPath2B = AppPrefs.getLocalModelPath2B(context),
            modelPath4B = AppPrefs.getLocalModelPath4B(context),
            ggufPath2B = AppPrefs.getLocalGgufPath2B(context),
            ggufPath4B = AppPrefs.getLocalGgufPath4B(context),
            llamaContextSize = AppPrefs.getLocalLlamaContextSize(context),
            llamaThreads = AppPrefs.getLocalLlamaThreads(context),
        )

        val trace = OnDeviceInferenceEngine.inferFromPromptWithTrace(
            context = context,
            model = model,
            prompt = buildPrompt(sessionStartMs, sessionEndMs, sessionEvents.size, snapshot),
            runtimeConfig = runtimeConfig,
        )
        val result = trace.result
        val raw = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary
        val heuristicGuess = buildHeuristicScenarioGuess(sessionEvents, snapshot)
        val parsed = parseOutput(
            output = raw,
            suggestedActions = result.suggestedActions,
            fallbackScenario = heuristicGuess.scenario,
            fallbackActionPlan = heuristicGuess.actionPlan,
            preferFallback = !result.nativeModelUsed,
        )
        val sessionId = "session_$sessionStartMs"
        val sessionLabel = formatSessionRange(sessionStartMs, sessionEndMs)

        store.insert(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                source = "assistant_engine",
                category = "assistant_session",
                summary = "Assistant session $sessionLabel | ${parsed.guessedUserScenario.take(120)}",
                payload = mapOf(
                    "sessionId" to sessionId,
                    "sessionLabel" to sessionLabel,
                    "sessionStartMs" to sessionStartMs,
                    "sessionEndMs" to sessionEndMs,
                    "eventCount" to sessionEvents.size,
                    "speechSummary" to snapshot.speechSummary,
                    "positionSummary" to snapshot.positionSummary,
                    "indoorOutdoor" to snapshot.indoorOutdoor,
                    "locationLabel" to snapshot.locationLabel,
                    "calendarSummary" to snapshot.calendarSummary,
                    "guessedUserScenario" to parsed.guessedUserScenario,
                    "suggestion" to parsed.guessedUserScenario,
                    "actionPlan" to parsed.actionPlan,
                    "modelLabel" to "${result.model.label} | ${result.strategyLabel}",
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        val response = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary
        store.insert(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                source = "local_model",
                category = "model_io",
                summary = "Model IO [assistant_session_15m_auto] ${result.model.label} | ${result.strategyLabel}",
                payload = mapOf(
                    "trigger" to "assistant_session_15m_auto",
                    "mode" to trace.mode,
                    "model" to result.model.label,
                    "strategy" to result.strategyLabel,
                    "prompt" to trace.prompt,
                    "response" to response,
                    "nativeUsed" to result.nativeModelUsed,
                    "nativeStatus" to result.nativeModelMessage,
                    "urgency" to result.urgencyScore,
                    "suggestedActions" to result.suggestedActions,
                    "contextEventCount" to sessionEvents.size,
                    "sessionId" to sessionId,
                    "sessionLabel" to sessionLabel,
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        AppPrefs.setAssistantLastSessionBucket(context, latestBucket)
    }

    private data class Snapshot(
        val speechSummary: String,
        val positionSummary: String,
        val indoorOutdoor: String,
        val locationLabel: String,
        val calendarSummary: String,
    )

    private data class Parsed(
        val guessedUserScenario: String,
        val actionPlan: String,
    )

    private data class HeuristicGuess(
        val scenario: String,
        val actionPlan: String,
    )

    private fun buildSnapshot(events: List<ContextEventPayload>): Snapshot {
        val speechSegments = mutableListOf<String>()
        var latitude: Double? = null
        var longitude: Double? = null
        var motionState: String? = null
        var latestLocationSummary: String? = null
        val wifiSignals = mutableListOf<Boolean>()
        val cellularSignals = mutableListOf<Boolean>()
        val calendarSignals = mutableListOf<String>()

        events.forEach { event ->
            val sourceLower = event.source.lowercase(Locale.US)
            val categoryLower = event.category.lowercase(Locale.US)
            val summaryLower = event.summary.lowercase(Locale.US)

            if (categoryLower == "audio" || sourceLower.contains("audio")) {
                val stitched = payloadString(event.payload, "stitchedTranscript")
                val transcript = payloadString(event.payload, "transcript")
                val text = when {
                    !stitched.isNullOrBlank() -> stitched
                    !transcript.isNullOrBlank() -> transcript
                    event.summary.startsWith("Ambient speech transcript:", ignoreCase = true) ->
                        event.summary.substringAfter(":", "").trim()
                    else -> null
                }
                if (!text.isNullOrBlank()) {
                    speechSegments += text
                }
            }

            if (categoryLower == "location" || sourceLower.contains("location")) {
                if (latitude == null) latitude = payloadDouble(event.payload, "latitude")
                if (longitude == null) longitude = payloadDouble(event.payload, "longitude")
                if (motionState == null) motionState = payloadString(event.payload, "motionState")
                if (latestLocationSummary == null) latestLocationSummary = event.summary
            }

            if (categoryLower == "connectivity" || sourceLower.contains("connectivity")) {
                payloadBoolean(event.payload, "wifi")?.let { wifiSignals += it }
                payloadBoolean(event.payload, "cellular")?.let { cellularSignals += it }
            }

            if (
                categoryLower == "calendar" ||
                categoryLower == "task" ||
                summaryLower.contains("meeting") ||
                summaryLower.contains("calendar") ||
                summaryLower.contains("deadline") ||
                summaryLower.contains("appointment")
            ) {
                calendarSignals += event.summary
            }
        }

        val speechSummary = speechSegments
            .asSequence()
            .map { normalizeSnippet(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString(separator = " | ")
            .ifBlank { "No speech transcript in this session" }

        val positionSummary = when {
            latitude != null && longitude != null -> {
                val motion = motionState?.takeIf { it.isNotBlank() } ?: "unknown"
                "lat=${formatCoordinate(latitude)}, lon=${formatCoordinate(longitude)} | motion=$motion"
            }
            !latestLocationSummary.isNullOrBlank() -> normalizeSnippet(latestLocationSummary)
            else -> "No location sample in this session"
        }

        val indoorOutdoor = inferIndoorOutdoor(wifiSignals, cellularSignals)
        val locationLabel = if (latitude != null && longitude != null) {
            "GPS ${formatCoordinate(latitude)}, ${formatCoordinate(longitude)}"
        } else {
            "Unknown location"
        }

        val calendarSummary = calendarSignals
            .asSequence()
            .map { normalizeSnippet(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString(separator = " | ")
            .ifBlank { "No meeting signal in this session" }

        return Snapshot(
            speechSummary = speechSummary,
            positionSummary = positionSummary,
            indoorOutdoor = indoorOutdoor,
            locationLabel = locationLabel,
            calendarSummary = calendarSummary,
        )
    }

    private fun buildPrompt(
        startMs: Long,
        endMs: Long,
        eventCount: Int,
        snapshot: Snapshot,
    ): String {
        return """
            You are a proactive personal assistant running fully on-device.
            Analyze one 15-minute session and infer the user's likely scenario.
            The answer must be evidence-grounded, not generic.
            You must use at least two evidence signals from speech / calendar / motion / connectivity.
            If mood evidence is weak, state mood as uncertain.

            Session window: ${formatSessionRange(startMs, endMs)}
            Event count: $eventCount
            Speech: ${snapshot.speechSummary}
            Position: ${snapshot.positionSummary}
            Indoor/Outdoor: ${snapshot.indoorOutdoor}
            Location label: ${snapshot.locationLabel}
            Calendar signal: ${snapshot.calendarSummary}

            Respond in exactly this plain-text format:
            Guessed User Scenario: <one sentence including likely activity, workload state, and mood>
            Action Plan:
            - <step 1>
            - <step 2>
            - <step 3>
        """.trimIndent()
    }

    private fun parseOutput(
        output: String,
        suggestedActions: List<String>,
        fallbackScenario: String,
        fallbackActionPlan: String,
        preferFallback: Boolean,
    ): Parsed {
        if (preferFallback) {
            return Parsed(
                guessedUserScenario = fallbackScenario,
                actionPlan = fallbackActionPlan,
            )
        }

        val cleaned = output.trim()
        if (cleaned.isBlank()) {
            return Parsed(
                guessedUserScenario = fallbackScenario,
                actionPlan = fallbackActionPlan,
            )
        }

        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        val scenario = lines
            .firstOrNull {
                val lower = it.lowercase(Locale.US)
                lower.startsWith("guessed user scenario:") || lower.startsWith("suggestion:")
            }
            ?.substringAfter(":")
            ?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: suggestedActions.firstOrNull().orEmpty().ifBlank {
                lines.firstOrNull().orEmpty().trimStart('-', '*', '1', '.', ')', ' ').trim()
            }

        val planHeader = lines.indexOfFirst {
            val lower = it.lowercase(Locale.US)
            lower.startsWith("action plan") || lower.startsWith("plan:")
        }
        val actionLines = if (planHeader >= 0) {
            lines.drop(planHeader + 1)
                .mapNotNull { line ->
                    val normalized = line.trimStart('-', '*').trim()
                    if (normalized.isBlank()) null else normalized
                }
                .take(3)
        } else {
            emptyList()
        }

        val modelActionPlan = when {
            actionLines.isNotEmpty() -> actionLines.joinToString(" | ")
            suggestedActions.isNotEmpty() -> suggestedActions.take(3).joinToString(" | ")
            else -> lines.drop(1).take(2).joinToString(" | ").ifBlank {
                "Continue passive monitoring and wait for stronger context."
            }
        }

        val finalScenario = if (isWeakScenario(scenario)) fallbackScenario else scenario
        val finalActionPlan = if (isWeakActionPlan(modelActionPlan)) fallbackActionPlan else modelActionPlan

        return Parsed(
            guessedUserScenario = finalScenario.take(220).ifBlank { fallbackScenario },
            actionPlan = finalActionPlan.take(420).ifBlank { fallbackActionPlan },
        )
    }

    private fun buildHeuristicScenarioGuess(
        sessionEvents: List<ContextEventPayload>,
        snapshot: Snapshot,
    ): HeuristicGuess {
        val hasSpeech = !snapshot.speechSummary.startsWith("No speech", ignoreCase = true)
        val speechLower = snapshot.speechSummary.lowercase(Locale.US)
        val calendarSignals = sessionEvents.count { event ->
            val lower = event.summary.lowercase(Locale.US)
            event.category.equals("calendar", ignoreCase = true) ||
                event.category.equals("task", ignoreCase = true) ||
                containsAny(lower, listOf("meeting", "calendar", "deadline", "agenda", "appointment"))
        }
        val commSignals = sessionEvents.count { event ->
            val lower = event.summary.lowercase(Locale.US)
            event.category.equals("communication", ignoreCase = true) ||
                event.category.equals("notification", ignoreCase = true) ||
                containsAny(lower, listOf("email", "message", "inbox", "slack", "github", "reply", "notification"))
        }
        val motionState = when {
            snapshot.positionSummary.contains("motion=driving", ignoreCase = true) -> "driving"
            snapshot.positionSummary.contains("motion=walking", ignoreCase = true) -> "walking"
            snapshot.positionSummary.contains("motion=still", ignoreCase = true) -> "still"
            else -> "unknown"
        }

        val activity = when {
            motionState == "driving" || motionState == "walking" ->
                "User is likely commuting or moving between locations"
            calendarSignals > 0 && hasSpeech ->
                "User is likely preparing for or discussing meetings/tasks"
            calendarSignals > 0 ->
                "User is likely in planning mode around upcoming meetings/tasks"
            commSignals >= 2 ->
                "User is likely handling communication and coordination work"
            hasSpeech ->
                "User is likely in a conversation or active thinking flow"
            else ->
                "User activity is low-signal; likely between tasks or quietly working"
        }

        val workloadScore = calendarSignals * 2 + commSignals + if (hasSpeech) 1 else 0
        val workload = when {
            workloadScore >= 6 -> "high"
            workloadScore >= 3 -> "medium"
            else -> "low"
        }
        val mood = when {
            containsAny(speechLower, listOf("urgent", "rush", "deadline", "late", "stressed", "烦", "急")) ->
                "slightly stressed"
            containsAny(speechLower, listOf("great", "good", "nice", "happy", "awesome", "开心", "不错")) ->
                "positive"
            hasSpeech ->
                "focused/neutral"
            else ->
                "uncertain"
        }

        val evidence = buildList {
            if (calendarSignals > 0) add("calendar/task signals=$calendarSignals")
            if (commSignals > 0) add("communication signals=$commSignals")
            if (motionState != "unknown") add("motion=$motionState")
            if (!snapshot.indoorOutdoor.equals("Unknown", ignoreCase = true)) add(snapshot.indoorOutdoor)
            if (hasSpeech) add("speech=\"${snapshot.speechSummary.take(70)}\"")
        }.ifEmpty { listOf("limited context signals") }

        val confidence = (45 + evidence.size * 10 + minOf(3, workloadScore) * 5).coerceIn(35, 92)
        val scenario = buildString {
            append("$activity; workload=$workload; mood=$mood; confidence=$confidence%. ")
            append("Evidence: ${evidence.joinToString(", ")}.")
        }.take(220)

        val actions = mutableListOf<String>()
        if (calendarSignals > 0) {
            actions += "Prepare a 3-point brief for the next meeting/task."
            actions += "Surface the most relevant notes/files before the meeting."
        }
        if (commSignals > 0) {
            actions += "Prioritize top pending messages and draft concise replies."
        }
        if (motionState == "driving" || motionState == "walking") {
            actions += "Keep interventions short and defer deep tasks until stationary."
        }
        if (actions.isEmpty()) {
            actions += "Keep passive monitoring and wait for stronger intent signals."
            actions += "Avoid interrupting unless urgency increases."
        }

        return HeuristicGuess(
            scenario = scenario,
            actionPlan = actions.distinct().take(3).joinToString(" | "),
        )
    }

    private fun isWeakScenario(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (text.length < 36) return true
        return containsAny(
            lower,
            listOf(
                "insufficient context",
                "no clear",
                "no significant",
                "continue passive",
                "keep collecting",
                "unknown scenario",
            )
        )
    }

    private fun isWeakActionPlan(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (text.length < 24) return true
        return containsAny(
            lower,
            listOf("continue passive monitoring", "wait for stronger context", "no action")
        )
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        return needles.any { text.contains(it, ignoreCase = true) }
    }

    private fun safePayloadMap(payloadJson: String): Map<String, Any> {
        val raw = kotlin.runCatching { JSONObject(payloadJson).toMap() }.getOrDefault(emptyMap())
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
                    if (k != null) nested[k.toString()] = normalized
                }
                nested
            }
            is List<*> -> value.mapNotNull { normalizeAny(it) }
            else -> value
        }
    }

    private fun payloadString(payload: Map<String, Any>, key: String): String? {
        return when (val value = payload[key]) {
            is String -> value
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> null
        }?.trim()
    }

    private fun payloadDouble(payload: Map<String, Any>, key: String): Double? {
        return when (val value = payload[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun payloadBoolean(payload: Map<String, Any>, key: String): Boolean? {
        return when (val value = payload[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                when (value.trim().lowercase(Locale.US)) {
                    "1", "true", "yes", "y", "on" -> true
                    "0", "false", "no", "n", "off" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun inferIndoorOutdoor(wifiSignals: List<Boolean>, cellularSignals: List<Boolean>): String {
        val hasWifi = wifiSignals.any { it }
        val hasCell = cellularSignals.any { it }
        return when {
            hasWifi && !hasCell -> "Indoor likely (wifi)"
            hasCell && !hasWifi -> "Outdoor likely (cellular)"
            hasCell && hasWifi -> "Transition or mixed"
            else -> "Unknown"
        }
    }

    private fun normalizeSnippet(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.replace(Regex("\\s+"), " ").trim().take(220)
    }

    private fun formatCoordinate(value: Double?): String {
        if (value == null) return "0.0000"
        return String.format(Locale.US, "%.4f", value)
    }

    private fun formatSessionRange(startMs: Long, endMs: Long): String {
        val dayFmt = SimpleDateFormat("MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val day = dayFmt.format(Date(startMs))
        val start = timeFmt.format(Date(startMs))
        val end = timeFmt.format(Date(endMs))
        return "$day $start-$end"
    }
}
