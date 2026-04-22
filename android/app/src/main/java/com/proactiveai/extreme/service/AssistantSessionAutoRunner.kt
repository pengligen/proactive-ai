package com.proactiveai.extreme.service

import android.content.Context
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.assistant.AssistantQuickActionPlanner
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
        if (AppPrefs.isGlobalLockEnabled(context)) return
        val events = loadEligibleEvents(context)
        if (events.isEmpty()) return
        val latestBucket = events.maxOf { it.occurredAt / SESSION_WINDOW_MS }
        runForBucket(context = context, events = events, bucket = latestBucket, force = false)
    }

    suspend fun refreshSessionForTimestamp(
        context: Context,
        occurredAtMs: Long,
    ) {
        if (AppPrefs.isGlobalLockEnabled(context)) return
        if (occurredAtMs <= 0L) return
        val events = loadEligibleEvents(context)
        if (events.isEmpty()) return
        val bucket = occurredAtMs / SESSION_WINDOW_MS
        runForBucket(context = context, events = events, bucket = bucket, force = true)
    }

    private suspend fun runForBucket(
        context: Context,
        events: List<ContextEventPayload>,
        bucket: Long,
        force: Boolean,
    ) {
        val lastBucket = AppPrefs.getAssistantLastSessionBucket(context)
        if (!force && lastBucket >= bucket) return

        val sessionStartMs = bucket * SESSION_WINDOW_MS
        val sessionEndMs = sessionStartMs + SESSION_WINDOW_MS
        val sessionEvents = events
            .filter { it.occurredAt in sessionStartMs until sessionEndMs }
            .sortedByDescending { it.occurredAt }

        if (sessionEvents.isEmpty()) {
            if (!force) {
                AppPrefs.setAssistantLastSessionBucket(context, maxOf(lastBucket, bucket))
            }
            return
        }

        val store = ContextEventStore.getInstance(context)
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
        val quickActions = AssistantQuickActionPlanner.inferQuickActions(
            speechSummary = snapshot.speechSummary,
            guessedUserScenario = parsed.guessedUserScenario,
            actionPlan = parsed.actionPlan,
            locationLabel = snapshot.locationLabel,
            calendarSummary = snapshot.calendarSummary,
            extraText = raw,
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
                    "sparklingSession" to (snapshot.sparklingSignalCount > 0),
                    "sparklingSignalCount" to snapshot.sparklingSignalCount,
                    "sparklingTriggers" to snapshot.sparklingTriggers,
                    "speechSummary" to snapshot.speechSummary,
                    "positionSummary" to snapshot.positionSummary,
                    "indoorOutdoor" to snapshot.indoorOutdoor,
                    "locationLabel" to snapshot.locationLabel,
                    "calendarSummary" to snapshot.calendarSummary,
                    "guessedUserScenario" to parsed.guessedUserScenario,
                    "suggestion" to parsed.guessedUserScenario,
                    "actionPlan" to parsed.actionPlan,
                    "quickActions" to AssistantQuickActionPlanner.toPayload(quickActions),
                    "modelLabel" to "${result.model.label} | ${result.strategyLabel}",
                    "recomputed" to force,
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
                    "recomputed" to force,
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        AppPrefs.setAssistantLastSessionBucket(context, maxOf(lastBucket, bucket))
    }

    private fun loadEligibleEvents(context: Context): List<ContextEventPayload> {
        val store = ContextEventStore.getInstance(context)
        return store.getRecent(limit = 1200)
            .mapNotNull { item ->
                val payload = safePayloadMap(item.payloadJson)
                kotlin.runCatching { item.toPayload(payload) }.getOrNull()
            }
            .filterNot { event ->
                event.source == "local_model" ||
                    event.category == "model_io" ||
                    event.category == "assistant_session" ||
                    event.category.endsWith("_bootstrap", ignoreCase = true)
            }
    }

    private data class Snapshot(
        val speechSummary: String,
        val positionSummary: String,
        val indoorOutdoor: String,
        val locationLabel: String,
        val calendarSummary: String,
        val sparklingSignalCount: Int,
        val sparklingTriggers: List<String>,
    )

    private data class SpeechSignal(
        val occurredAt: Long,
        val clipKey: String,
        val text: String,
        val priority: Int,
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
        val speechSignals = mutableListOf<SpeechSignal>()
        var latitude: Double? = null
        var longitude: Double? = null
        var motionState: String? = null
        var latestLocationSummary: String? = null
        val wifiSignals = mutableListOf<Boolean>()
        val cellularSignals = mutableListOf<Boolean>()
        val calendarSignals = mutableListOf<String>()
        var sparklingSignalCount = 0
        val sparklingTriggers = mutableListOf<String>()

        events.forEach { event ->
            val sourceLower = event.source.lowercase(Locale.US)
            val categoryLower = event.category.lowercase(Locale.US)
            val summaryLower = event.summary.lowercase(Locale.US)

            extractSpeechSignal(event)?.let { speechSignals += it }
            if (isSparklingSignal(event)) {
                sparklingSignalCount += 1
                extractSparklingTrigger(event)?.let { trigger ->
                    if (trigger !in sparklingTriggers) {
                        sparklingTriggers += trigger
                    }
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

        val speechSummary = buildSpeechSummaryFromSignals(
            signals = speechSignals,
            maxSegments = 6,
        )

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
            sparklingSignalCount = sparklingSignalCount,
            sparklingTriggers = sparklingTriggers.take(4),
        )
    }

    private fun isSparklingSignal(event: ContextEventPayload): Boolean {
        val categoryLower = event.category.lowercase(Locale.US)
        val sourceLower = event.source.lowercase(Locale.US)
        if (categoryLower == "sparkling" || sourceLower.contains("sparkling")) return true
        if (payloadBoolean(event.payload, "sparkling") == true) return true
        if (payloadBoolean(event.payload, "sparklingSessionHint") == true) return true
        return false
    }

    private fun extractSparklingTrigger(event: ContextEventPayload): String? {
        val raw = payloadString(event.payload, "trigger")
            ?.lowercase(Locale.US)
            ?.trim()
        val normalized = when {
            raw.isNullOrBlank() -> null
            raw.contains("shake") -> "shake"
            raw.contains("tap") -> "double_tap"
            else -> raw.take(32)
        }
        if (!normalized.isNullOrBlank()) return normalized
        return when {
            event.summary.contains("shake", ignoreCase = true) -> "shake"
            event.summary.contains("tap", ignoreCase = true) -> "double_tap"
            else -> null
        }
    }

    private fun extractSpeechSignal(event: ContextEventPayload): SpeechSignal? {
        val sourceLower = event.source.lowercase(Locale.US)
        val categoryLower = event.category.lowercase(Locale.US)
        if (categoryLower != "audio" && !sourceLower.contains("audio")) return null

        val status = payloadString(event.payload, "status")?.lowercase(Locale.US).orEmpty()
        if (status == "no_speech" || status == "error") return null

        val stitched = payloadString(event.payload, "stitchedTranscript")
        val transcript = payloadString(event.payload, "transcript")
        val summaryTranscript = if (event.summary.startsWith("Ambient speech transcript", ignoreCase = true)) {
            event.summary.substringAfter(":", "").trim()
        } else {
            ""
        }
        val pickedText = listOf(stitched, transcript, summaryTranscript)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
            .trim()
        if (pickedText.isBlank() || isNonSpeechText(pickedText)) return null

        val isCloudRefined = payloadBoolean(event.payload, "refinedByCloud") == true ||
            payloadString(event.payload, "modelStatus")?.contains("cloud_refined", ignoreCase = true) == true ||
            payloadString(event.payload, "strategy")?.contains("gpt-4o-transcribe", ignoreCase = true) == true ||
            sourceLower.contains("refiner")

        val clipKey = payloadString(event.payload, "wavPath")
            ?.ifBlank { null }
            ?: payloadString(event.payload, "clipOccurredAt")
                ?.ifBlank { null }
                ?.let { "clipAt:$it" }
            ?: "${event.occurredAt}:${pickedText.take(72).lowercase(Locale.US)}"

        return SpeechSignal(
            occurredAt = event.occurredAt,
            clipKey = clipKey,
            text = pickedText,
            priority = if (isCloudRefined) 2 else 1,
        )
    }

    private fun buildSpeechSummaryFromSignals(
        signals: List<SpeechSignal>,
        maxSegments: Int,
    ): String {
        if (signals.isEmpty()) return "No speech transcript in this session"

        val bestByClip = linkedMapOf<String, SpeechSignal>()
        signals
            .sortedWith(
                compareByDescending<SpeechSignal> { it.occurredAt }
                    .thenByDescending { it.priority }
            )
            .forEach { signal ->
                val existing = bestByClip[signal.clipKey]
                if (
                    existing == null ||
                    signal.priority > existing.priority ||
                    (signal.priority == existing.priority && signal.occurredAt > existing.occurredAt)
                ) {
                    bestByClip[signal.clipKey] = signal
                }
            }

        val normalized = bestByClip.values
            .sortedWith(
                compareByDescending<SpeechSignal> { it.occurredAt }
                    .thenByDescending { it.priority }
            )
            .asSequence()
            .map { normalizeSnippet(it.text) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .toList()

        if (normalized.isEmpty()) return "No speech transcript in this session"

        val shown = normalized.take(maxSegments)
        val extra = normalized.size - shown.size
        return if (extra > 0) {
            "${shown.joinToString(separator = " | ")} | (+$extra more speech clips)"
        } else {
            shown.joinToString(separator = " | ")
        }
    }

    private fun isNonSpeechText(raw: String): Boolean {
        val lower = raw.trim().lowercase(Locale.US)
        if (lower.isBlank()) return true
        if (
            lower == "<no-speech>" ||
            lower == "no speech" ||
            lower == "no_speech" ||
            lower == "[silence]" ||
            lower == "silence" ||
            lower == "empty_or_no_speech"
        ) {
            return true
        }
        if (lower.startsWith("speech recognizer failed")) return true
        if (lower.contains("speech_error_")) return true
        if (lower.contains("no clear speech")) return true
        if (lower.contains("未识别") || lower.contains("无法识别")) return true
        return false
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
            Make action steps aggressive and immediately executable in the next 10 minutes.
            Prefer direct outcomes (book/order/open/contact) over passive suggestions.
            Only propose domains that are directly supported by session evidence.
            Do NOT invent unrelated tools/apps/tasks (for example GitHub, calendar prep, Gmail, Slack) unless explicitly supported by speech/calendar/event evidence in this session.
            If evidence is weak, output fewer steps (1-2) and keep them targeted to the strongest explicit user intent.
            For each action step, include one concrete endpoint or query target and mention the evidence phrase briefly.

            Session window: ${formatSessionRange(startMs, endMs)}
            Event count: $eventCount
            Sparkling marker: ${if (snapshot.sparklingSignalCount > 0) "YES (${snapshot.sparklingSignalCount}, triggers=${snapshot.sparklingTriggers.joinToString(", ").ifBlank { "manual" }})" else "NO"}
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
                    val normalized = normalizeActionLine(line)
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
            snapshot.sparklingSignalCount > 0 && hasSpeech ->
                "User intentionally marked a sparkling moment while speaking and expects immediate help"
            snapshot.sparklingSignalCount > 0 ->
                "User intentionally marked this as a high-value moment and expects focused proactive support"
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
            if (snapshot.sparklingSignalCount > 0) {
                add("sparkling_marker=${snapshot.sparklingTriggers.joinToString(",").ifBlank { "manual" }}")
            }
            if (calendarSignals > 0) add("calendar/task signals=$calendarSignals")
            if (commSignals > 0) add("communication signals=$commSignals")
            if (motionState != "unknown") add("motion=$motionState")
            if (!snapshot.indoorOutdoor.equals("Unknown", ignoreCase = true)) add(snapshot.indoorOutdoor)
            if (hasSpeech) add("speech=\"${snapshot.speechSummary.take(70)}\"")
        }.ifEmpty { listOf("limited context signals") }

        val sparklingBoost = if (snapshot.sparklingSignalCount > 0) 16 else 0
        val confidence = (45 + evidence.size * 10 + minOf(3, workloadScore) * 5 + sparklingBoost).coerceIn(35, 96)
        val scenario = buildString {
            append("$activity; workload=$workload; mood=$mood; confidence=$confidence%. ")
            append("Evidence: ${evidence.joinToString(", ")}.")
        }.take(220)

        val actions = mutableListOf<String>()
        if (snapshot.sparklingSignalCount > 0) {
            actions += "Capture this sparkling moment as a priority note with one concrete next action and deadline."
        }
        val milkTeaIntent = containsAny(speechLower, listOf("奶茶", "milk tea", "bubble tea", "boba", "茶饮"))
        if (milkTeaIntent) {
            actions += "Find top nearby milk tea shops by ETA and rating, then show direct order/search links."
            actions += "Prepare a default order draft (size, sugar, ice) and ask one-tap confirmation."
        }
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
            actions += "Run one targeted search from current context and surface three executable links."
            actions += "Ask one confirmation question, then execute the highest-confidence next step."
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

    private fun normalizeActionLine(line: String): String {
        return line
            .replace(Regex("^\\s*[-*•]+\\s*"), "")
            .replace(Regex("^\\s*\\d+[\\).]\\s*"), "")
            .trim()
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
