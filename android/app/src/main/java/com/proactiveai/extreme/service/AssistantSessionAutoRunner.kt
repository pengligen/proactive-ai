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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AssistantSessionAutoRunner {
    private const val SESSION_WINDOW_MS = 15 * 60 * 1000L
    private const val FUTURE_EVENT_TOLERANCE_MS = 10 * 60 * 1000L
    private const val EVENT_LOOKBACK_MS = 10 * 24 * 60 * 60 * 1000L
    private const val LOCATION_FALLBACK_LOOKBACK_MS = 12 * 60 * 60 * 1000L

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
        val now = System.currentTimeMillis()
        val currentBucket = now / SESSION_WINDOW_MS
        var lastBucket = AppPrefs.getAssistantLastSessionBucket(context)
        if (lastBucket > currentBucket + 1) {
            lastBucket = currentBucket - 1
            AppPrefs.setAssistantLastSessionBucket(context, lastBucket)
        }
        if (bucket > currentBucket + 1) return
        if (!force && lastBucket >= bucket) return

        val sessionStartMs = bucket * SESSION_WINDOW_MS
        val sessionEndMs = sessionStartMs + SESSION_WINDOW_MS
        val sessionEvents = events
            .filter { it.occurredAt in sessionStartMs until sessionEndMs }
            .sortedByDescending { it.occurredAt }

        if (sessionEvents.isEmpty()) {
            if (!force) {
                AppPrefs.setAssistantLastSessionBucket(context, maxOf(lastBucket, bucket).coerceAtMost(currentBucket))
            }
            return
        }

        val store = ContextEventStore.getInstance(context)
        val snapshot = buildSnapshot(
            events = sessionEvents,
            fallbackEvents = events,
        )
        val model = EdgeModelProfile.fromId(AppPrefs.getEdgeModel(context))
        val pathMap = AppPrefs.getLocalModelPathMap(context)
        val runtimeConfig = LocalModelRuntimeConfig(
            enabled = AppPrefs.isLocalModelEnabled(context),
            backend = LocalModelBackend.fromId(AppPrefs.getLocalModelBackend(context)),
            modelPathById = pathMap,
            ggufPathById = mapOf(
                EdgeModelProfile.GEMMA_EFFECTIVE_2B.id to AppPrefs.getLocalGgufPath2B(context),
                EdgeModelProfile.GEMMA_EFFECTIVE_4B.id to AppPrefs.getLocalGgufPath4B(context),
            ),
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
        val heuristicGuess = buildHeuristicScenarioGuess(
            sessionEvents = sessionEvents,
            snapshot = snapshot,
            allEvents = events,
        )
        val parsed = parseOutput(
            output = raw,
            suggestedActions = result.suggestedActions,
            fallbackScenario = heuristicGuess.scenario,
            fallbackActionPlan = heuristicGuess.actionPlan,
            preferFallback = !result.nativeModelUsed,
        )
        val cloudOutcome = CloudAssistantActionPlanner.generate(
            context = context.applicationContext,
            request = CloudAssistantActionPlanner.SessionRequest(
                sessionLabel = formatSessionRange(sessionStartMs, sessionEndMs),
                eventCount = sessionEvents.size,
                sparklingSession = snapshot.sparklingSignalCount > 0,
                sparklingSignalCount = snapshot.sparklingSignalCount,
                sparklingTriggers = snapshot.sparklingTriggers.joinToString(separator = ", ").ifBlank { "-" },
                speechSummary = snapshot.speechSummary,
                positionSummary = snapshot.positionSummary,
                indoorOutdoor = snapshot.indoorOutdoor,
                locationLabel = snapshot.locationLabel,
                calendarSummary = snapshot.calendarSummary,
                localScenario = parsed.guessedUserScenario,
                localActionPlan = parsed.actionPlan,
                eventDigest = buildCloudSessionEventDigest(sessionEvents),
            ),
        )
        val scenarioForActions = cloudOutcome.guessedUserScenario.ifBlank { parsed.guessedUserScenario }
        val actionPlanForActions = cloudOutcome.actionPlan.ifBlank { parsed.actionPlan }
        val quickActions = AssistantQuickActionPlanner.inferQuickActions(
            speechSummary = snapshot.speechSummary,
            guessedUserScenario = scenarioForActions,
            actionPlan = actionPlanForActions,
            locationLabel = snapshot.locationLabel,
            calendarSummary = snapshot.calendarSummary,
            extraText = listOf(raw, cloudOutcome.rawResponse).joinToString("\n"),
        )
        val sessionId = "session_$sessionStartMs"
        val sessionLabel = formatSessionRange(sessionStartMs, sessionEndMs)

        store.insert(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                source = "assistant_engine",
                category = "assistant_session",
                summary = "Assistant session $sessionLabel | ${cloudOutcome.guessedUserScenario.ifBlank { parsed.guessedUserScenario }.take(120)}",
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
                    "cloudGuessedUserScenario" to cloudOutcome.guessedUserScenario,
                    "cloudActionPlan" to cloudOutcome.actionPlan,
                    "cloudComparison" to cloudOutcome.comparison,
                    "cloudModelLabel" to cloudOutcome.modelLabel,
                    "cloudStatus" to cloudOutcome.status,
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

        if (cloudOutcome.attempted) {
            store.insert(
                ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = System.currentTimeMillis(),
                    source = "cloud_model",
                    category = "model_io",
                    summary = "Model IO [assistant_session_15m_cloud_auto] ${cloudOutcome.modelLabel} | ${cloudOutcome.status}",
                    payload = mapOf(
                        "trigger" to "assistant_session_15m_cloud_auto",
                        "mode" to "cloud",
                        "model" to cloudOutcome.modelLabel,
                        "strategy" to "cloud_action_planner",
                        "prompt" to cloudOutcome.prompt,
                        "response" to cloudOutcome.rawResponse.ifBlank { cloudOutcome.detail },
                        "status" to cloudOutcome.status,
                        "detail" to cloudOutcome.detail,
                        "latencyMs" to cloudOutcome.latencyMs,
                        "contextEventCount" to sessionEvents.size,
                        "sessionId" to sessionId,
                        "sessionLabel" to sessionLabel,
                        "recomputed" to force,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = 7 * 24 * 3600,
                )
            )
        }

        AppPrefs.setAssistantLastSessionBucket(context, maxOf(lastBucket, bucket).coerceAtMost(currentBucket))
    }

    private fun loadEligibleEvents(context: Context): List<ContextEventPayload> {
        val store = ContextEventStore.getInstance(context)
        val now = System.currentTimeMillis()
        val minTs = now - EVENT_LOOKBACK_MS
        val maxTs = now + FUTURE_EVENT_TOLERANCE_MS
        val excludedCategories = setOf(
            "model_io",
            "assistant_session",
            "context_log",
            "audio_gate",
            "capability_status",
            "daily_focus",
        )
        return store.getRecent(limit = 1200)
            .mapNotNull { item ->
                val payload = safePayloadMap(item.payloadJson)
                kotlin.runCatching { item.toPayload(payload) }.getOrNull()
            }
            .filterNot { event ->
                event.occurredAt !in minTs..maxTs ||
                event.source == "local_model" ||
                    event.category.lowercase(Locale.US) in excludedCategories ||
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

    private fun buildSnapshot(
        events: List<ContextEventPayload>,
        fallbackEvents: List<ContextEventPayload>,
    ): Snapshot {
        val speechSignals = mutableListOf<SpeechSignal>()
        var latitude: Double? = null
        var longitude: Double? = null
        var cityLabel: String? = null
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
                if (cityLabel == null) cityLabel = payloadString(event.payload, "city")
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

        if (latitude == null || longitude == null) {
            val fallback = fallbackEvents
                .asSequence()
                .filter { it.occurredAt in ((events.maxOfOrNull { ev -> ev.occurredAt } ?: System.currentTimeMillis()) - LOCATION_FALLBACK_LOOKBACK_MS)..(System.currentTimeMillis() + FUTURE_EVENT_TOLERANCE_MS) }
                .sortedByDescending { it.occurredAt }
                .firstOrNull { event ->
                    val sourceLower = event.source.lowercase(Locale.US)
                    val categoryLower = event.category.lowercase(Locale.US)
                    (categoryLower == "location" || sourceLower.contains("location")) &&
                        payloadDouble(event.payload, "latitude") != null &&
                        payloadDouble(event.payload, "longitude") != null
                }
            if (fallback != null) {
                if (latitude == null) latitude = payloadDouble(fallback.payload, "latitude")
                if (longitude == null) longitude = payloadDouble(fallback.payload, "longitude")
                if (cityLabel == null) cityLabel = payloadString(fallback.payload, "city")
                if (motionState == null) motionState = payloadString(fallback.payload, "motionState")
                if (latestLocationSummary == null) latestLocationSummary = fallback.summary
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
        val locationLabel = when {
            !cityLabel.isNullOrBlank() && latitude != null && longitude != null ->
                "$cityLabel | GPS ${formatCoordinate(latitude)}, ${formatCoordinate(longitude)}"
            !cityLabel.isNullOrBlank() -> cityLabel.orEmpty()
            latitude != null && longitude != null ->
                "GPS ${formatCoordinate(latitude)}, ${formatCoordinate(longitude)}"
            else -> "Unknown location"
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
            Analyze one 15-minute context session and infer the user's real need.
            Avoid generic productivity templates. Be specific, evidence-grounded, and immediately useful.
            Prioritize these action classes:
            1) Deep research candidate: if user mentions investment/finance/company/person/project worth researching.
            2) Daily-life problem solving: restaurants, milk tea, haircut, shopping, logistics, errands.
            3) Emotional support: if user sounds angry/sad/anxious/proud, respond with appropriate tone and coping/grounding step.
            4) Health nudges: hydration, stand/walk, food/rest timing; can leverage recent behavior pattern.
            5) Explicit command execution: when user clearly asks to investigate/schedule/set up something.
            For life-problem actions, include direct links or direction/search entry points (maps/search/booking) instead of abstract advice.
            Every action must be executable in 5-30 minutes.
            If evidence is weak, output at most 2 precise actions.

            Session window: ${formatSessionRange(startMs, endMs)}
            Event count: $eventCount
            Sparkling marker: ${if (snapshot.sparklingSignalCount > 0) "YES (${snapshot.sparklingSignalCount}, triggers=${snapshot.sparklingTriggers.joinToString(", ").ifBlank { "manual" }})" else "NO"}
            Speech: ${snapshot.speechSummary}
            Position: ${snapshot.positionSummary}
            Indoor/Outdoor: ${snapshot.indoorOutdoor}
            Location label: ${snapshot.locationLabel}
            Calendar signal: ${snapshot.calendarSummary}

            Respond in exactly this plain-text format:
            Guessed User Scenario: <one sentence including likely current activity and mood>
            Action Plan:
            - <step 1 with concrete target + optional direct link/search query + evidence in parentheses>
            - <step 2 with concrete target + optional direct link/search query + evidence in parentheses>
            - <step 3 with concrete target + optional direct link/search query + evidence in parentheses>
        """.trimIndent()
    }

    private fun buildCloudSessionEventDigest(events: List<ContextEventPayload>): String {
        if (events.isEmpty()) return "- No events."
        return events
            .asSequence()
            .sortedByDescending { it.occurredAt }
            .take(24)
            .map { event ->
                val summary = normalizeSnippet(event.summary)
                val source = event.source.take(28)
                val category = event.category.take(28)
                "- [$category/$source] $summary"
            }
            .toList()
            .joinToString(separator = "\n")
            .ifBlank { "- No events." }
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
            actionPlan = finalActionPlan.take(720).ifBlank { fallbackActionPlan },
        )
    }

    private fun buildHeuristicScenarioGuess(
        sessionEvents: List<ContextEventPayload>,
        snapshot: Snapshot,
        allEvents: List<ContextEventPayload> = sessionEvents,
    ): HeuristicGuess {
        val hasSpeech = !snapshot.speechSummary.startsWith("No speech", ignoreCase = true)
        val speechLower = snapshot.speechSummary.lowercase(Locale.US)
        val calendarLower = snapshot.calendarSummary.lowercase(Locale.US)
        val calendarSignals = sessionEvents.count { event ->
            val categoryLower = event.category.lowercase(Locale.US)
            categoryLower == "calendar" || categoryLower == "task"
        }
        val commSignals = sessionEvents.count { event ->
            val categoryLower = event.category.lowercase(Locale.US)
            categoryLower == "communication" || categoryLower == "notification"
        }
        val motionState = when {
            snapshot.positionSummary.contains("motion=driving", ignoreCase = true) -> "driving"
            snapshot.positionSummary.contains("motion=walking", ignoreCase = true) -> "walking"
            snapshot.positionSummary.contains("motion=still", ignoreCase = true) -> "still"
            else -> "unknown"
        }

        val explicitWorkMarkers = listOf(
            "meeting", "deadline", "client", "customer", "project", "repo", "github",
            "slack", "email", "inbox", "follow-up", "doc", "ppt", "汇报", "客户", "项目", "会议", "周报", "邮件"
        )
        val hasExplicitWorkIntent = containsAny(speechLower, explicitWorkMarkers) ||
            (!calendarLower.startsWith("no meeting") && containsAny(calendarLower, explicitWorkMarkers))

        val foodIntent = containsAny(
            speechLower,
            listOf("奶茶", "milk tea", "bubble tea", "boba", "coffee", "咖啡", "吃饭", "lunch", "dinner", "外卖", "order food", "餐厅"),
        )
        val shoppingIntent = containsAny(
            speechLower,
            listOf("buy", "purchase", "shop", "shopping", "grocery", "groceries", "超市", "药店", "买", "采购", "下单"),
        )
        val commuteIntent = motionState == "driving" || motionState == "walking" ||
            containsAny(speechLower, listOf("commute", "drive", "parking", "route", "导航", "打车", "地铁", "bus", "train", "trip", "出发"))
        val socialIntent = containsAny(
            speechLower,
            listOf("call", "text", "message", "reply", "follow up", "朋友", "家人", "同事", "联系", "回消息"),
        )
        val healthIntent = containsAny(
            speechLower,
            listOf("tired", "sleep", "rest", "walk", "exercise", "workout", "累", "休息", "睡", "锻炼", "健康"),
        )
        val deepResearchIntent = containsAny(
            speechLower,
            listOf(
                "stock", "stocks", "equity", "earnings", "valuation", "investment", "portfolio", "fund",
                "crypto", "token", "bond", "ipo", "company", "founder", "ceo", "market", "trading", "macro",
                "project", "person", "research", "investigate", "due diligence", "尽调", "调研", "投资", "股票", "基金", "项目", "人物",
            ),
        )
        val haircutIntent = containsAny(
            speechLower,
            listOf("haircut", "barber", "salon", "理发", "发型", "剪头发"),
        )
        val restaurantIntent = containsAny(
            speechLower,
            listOf("restaurant", "dining", "eat", "lunch", "dinner", "brunch", "餐厅", "吃饭"),
        )
        val explicitCommandIntent = containsAny(
            speechLower,
            listOf("investigate", "look into", "find out", "schedule", "set meeting", "book", "reserve", "remind me", "帮我查", "帮我调查", "安排", "预订"),
        )
        val angerIntent = containsAny(
            speechLower,
            listOf("angry", "annoyed", "frustrated", "furious", "烦", "生气", "躁", "火大"),
        )
        val sadIntent = containsAny(
            speechLower,
            listOf("sad", "down", "lonely", "depressed", "upset", "难过", "低落", "伤心", "沮丧"),
        )
        val proudIntent = containsAny(
            speechLower,
            listOf("proud", "excited", "confident", "great", "awesome", "得意", "兴奋", "自信"),
        )
        val sessionEndMs = sessionEvents.maxOfOrNull { it.occurredAt } ?: System.currentTimeMillis()
        val sessionHour = java.util.Calendar.getInstance().apply { timeInMillis = sessionEndMs }.get(java.util.Calendar.HOUR_OF_DAY)
        val mealWindow = sessionHour in 11..14 || sessionHour in 18..21
        val motionSignals2h = allEvents
            .asSequence()
            .filter { it.occurredAt in (sessionEndMs - 2 * 60 * 60 * 1000L)..sessionEndMs }
            .filter { it.category.equals("location", ignoreCase = true) || it.source.contains("location", ignoreCase = true) }
            .mapNotNull { payloadString(it.payload, "motionState")?.lowercase(Locale.US) }
            .toList()
        val mostlyStill2h = motionSignals2h.isNotEmpty() &&
            motionSignals2h.count { it == "still" || it == "unknown" } >= (motionSignals2h.size * 0.7)
        val healthNudgeIntent = healthIntent || mostlyStill2h || (mealWindow && hasSpeech)
        val locationHint = snapshot.locationLabel
            .takeIf { it.isNotBlank() && !it.startsWith("Unknown", ignoreCase = true) }
            ?.take(48)
            ?: "near me"
        val milkTeaLink = "https://www.google.com/maps/search/?api=1&query=${encodeQuery("$locationHint milk tea")}"
        val restaurantLink = "https://www.google.com/maps/search/?api=1&query=${encodeQuery("$locationHint restaurant reservation")}"
        val haircutLink = "https://www.google.com/maps/search/?api=1&query=${encodeQuery("$locationHint barber salon")}"
        val deepResearchLink = "https://www.google.com/search?udm=50&q=${encodeQuery("${snapshot.speechSummary.take(80)} deep research latest analysis")}"

        val activity = when {
            deepResearchIntent ->
                "User surfaced a topic that likely needs deep research before acting"
            snapshot.sparklingSignalCount > 0 && hasSpeech ->
                "User intentionally marked a sparkling moment and likely wants fast help on a specific personal need"
            snapshot.sparklingSignalCount > 0 ->
                "User intentionally marked this as high-value and expects focused assistance now"
            angerIntent || sadIntent || proudIntent ->
                "User appears emotionally loaded and needs context-aware support, not generic productivity"
            foodIntent ->
                "User likely wants immediate food/drink options and a quick decision"
            commuteIntent ->
                "User is likely moving between places and needs low-friction guidance"
            shoppingIntent || haircutIntent || restaurantIntent ->
                "User likely has a purchase/errand intent and needs fast options"
            socialIntent ->
                "User likely needs to contact someone or follow up on a conversation"
            explicitCommandIntent ->
                "User issued an explicit command and expects direct execution support"
            hasExplicitWorkIntent ->
                "User likely has a work-related follow-up that needs a concrete next step"
            hasSpeech ->
                "User is in an active thinking/conversation flow with practical immediate intent"
            else ->
                "Signal is light; user is likely between tasks"
        }
        val mood = when {
            angerIntent ->
                "agitated"
            sadIntent ->
                "sad/low-energy"
            proudIntent ->
                "confident/excited"
            containsAny(speechLower, listOf("urgent", "rush", "deadline", "late", "stressed", "烦", "急")) ->
                "slightly stressed"
            containsAny(speechLower, listOf("hungry", "饿", "想喝", "want to drink")) ->
                "intent-driven"
            containsAny(speechLower, listOf("tired", "累", "困", "sleepy")) ->
                "fatigued"
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
            if (deepResearchIntent) add("deep-research markers in speech")
            if (foodIntent) add("food/drink phrase in speech")
            if (haircutIntent) add("grooming service phrase in speech")
            if (shoppingIntent) add("purchase/errand phrase in speech")
            if (restaurantIntent) add("restaurant booking phrase in speech")
            if (explicitCommandIntent) add("explicit command phrase in speech")
            if (angerIntent || sadIntent || proudIntent) add("emotional expression in speech")
            if (socialIntent) add("contact/follow-up phrase in speech")
            if (healthNudgeIntent) add("health/rest or sedentary pattern signal")
            if (calendarSignals > 0 && hasExplicitWorkIntent) add("calendar/task signals=$calendarSignals")
            if (commSignals > 0) add("communication signals=$commSignals")
            if (mostlyStill2h) add("mostly still in last ~2h")
            if (motionState != "unknown") add("motion=$motionState")
            if (!snapshot.indoorOutdoor.equals("Unknown", ignoreCase = true)) add(snapshot.indoorOutdoor)
            if (hasSpeech) add("speech=\"${snapshot.speechSummary.take(70)}\"")
        }.ifEmpty { listOf("limited context signals") }

        val intentScore = listOf(
            foodIntent,
            shoppingIntent,
            commuteIntent,
            socialIntent,
            healthNudgeIntent,
            deepResearchIntent,
            explicitCommandIntent,
        )
            .count { it }
        val sparklingBoost = if (snapshot.sparklingSignalCount > 0) 14 else 0
        val confidence = (44 + evidence.size * 9 + intentScore * 5 + sparklingBoost).coerceIn(35, 96)
        val scenario = buildString {
            append("$activity; mood=$mood; confidence=$confidence%. ")
            append("Evidence: ${evidence.joinToString(", ")}.")
        }.take(220)

        val actions = mutableListOf<String>()
        if (snapshot.sparklingSignalCount > 0) {
            actions += "Pin this sparkling moment and convert it into one concrete next step the user can execute now."
        }
        if (deepResearchIntent) {
            actions += "Run deep research now: build a one-page brief (thesis, upside, downside, key people, next 7-day catalysts). Link: $deepResearchLink (evidence: research/investment phrase)."
        }
        if (foodIntent) {
            actions += "Find 3 nearby milk tea options with ratings + distance + open direction link: $milkTeaLink (evidence: speech intent)."
            actions += "Open an AI search for best current deals/coupons near $locationHint: https://www.google.com/search?udm=50&q=${encodeQuery("$locationHint milk tea deals coupon")} (evidence: drink intent)."
        }
        if (restaurantIntent) {
            actions += "Show reservable restaurants near current area with direct map/search entry: $restaurantLink (evidence: dining intent)."
        }
        if (haircutIntent) {
            actions += "Find top-rated barber/salon options and open direction: $haircutLink (evidence: haircut phrase)."
        }
        if (shoppingIntent) {
            actions += "Create a buy-now checklist and open nearest store search: https://www.google.com/maps/search/?api=1&query=${encodeQuery("$locationHint grocery store")} (evidence: purchase phrase)."
        }
        if (commuteIntent) {
            actions += "Open quickest route/travel option from current location and suggest optimal departure timing (evidence: motion/location)."
        }
        if (angerIntent) {
            actions += "Emotional guardrail: pause 90 seconds before reacting, then draft a calm response in 3 lines (evidence: anger markers)."
        }
        if (sadIntent) {
            actions += "Support mode: start a short check-in conversation now and suggest one low-effort comforting step (walk/water/call a trusted person)."
        }
        if (proudIntent) {
            actions += "Momentum with caution: capture this win in 2 lines, then add one risk-control check before next decision."
        }
        if (socialIntent) {
            actions += "Draft a concise follow-up message for the mentioned person with one clear ask (evidence: contact phrase)."
        }
        if (healthNudgeIntent) {
            actions += "Health nudge: stand up, drink water, and do a 3-5 minute walk now; then set a 30-minute movement reminder (evidence: sedentary/health signal)."
        }
        if (explicitCommandIntent || hasExplicitWorkIntent) {
            actions += "Convert the explicit command into an execution checklist with owner/time/output and start with step 1 immediately."
        }
        if (actions.isEmpty() && hasSpeech) {
            actions += "Use the strongest spoken phrase to produce 3 concrete next options with direct links near current context."
        } else if (actions.isEmpty()) {
            actions += "Ask one concise clarification question, then open an AI search entry to unblock next step: https://www.google.com/search?udm=50&q=${encodeQuery("best next step based on current context near me")}."
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
        val hasDirectEntry = lower.contains("http://") ||
            lower.contains("https://") ||
            lower.contains("maps/search") ||
            lower.contains("google.com/search")
        val generic = containsAny(
            lower,
            listOf(
                "continue passive monitoring",
                "wait for stronger context",
                "no action",
                "open calendar",
                "check calendar",
                "open github",
                "prepare a 3-point brief",
                "surface the most relevant notes",
                "prioritize top pending messages",
                "review notes",
                "maintain monitoring",
                "keep collecting context",
            )
        )
        if (generic && !hasDirectEntry) return true
        val weakVerbCount = listOf("open", "check", "review", "monitor").count { lower.contains(it) }
        return weakVerbCount >= 2 && !hasDirectEntry
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

    private fun encodeQuery(input: String): String {
        return URLEncoder.encode(input, Charsets.UTF_8.name())
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
