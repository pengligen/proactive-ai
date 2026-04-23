package com.proactiveai.extreme.core.edge

import android.content.Context
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.IntentHint
import com.proactiveai.extreme.orchestrator.ContextEventPayload
import java.util.UUID

enum class EdgeModelProfile(
    val id: String,
    val label: String,
    val description: String,
    val defaultLiteRtFileName: String,
    val approxSizeLabel: String,
) {
    GEMMA_EFFECTIVE_2B(
        id = "gemma_effective_2b",
        label = "Gemma Effective 2B",
        description = "Fastest on-device inference and lower battery usage.",
        defaultLiteRtFileName = "gemma-4-E2B-it.litertlm",
        approxSizeLabel = "2.3 GB",
    ),
    GEMMA_EFFECTIVE_4B(
        id = "gemma_effective_4b",
        label = "Gemma Effective 4B",
        description = "Higher quality reasoning with higher compute cost.",
        defaultLiteRtFileName = "gemma-4-E4B-it.litertlm",
        approxSizeLabel = "4.8 GB",
    ),
    QWEN3_0_6B(
        id = "qwen3_0_6b",
        label = "Qwen3 0.6B",
        description = "Lightweight multilingual model with low RAM footprint.",
        defaultLiteRtFileName = "Qwen3-0.6B.litertlm",
        approxSizeLabel = "614 MB",
    ),
    QWEN2_5_1_5B(
        id = "qwen2_5_1_5b",
        label = "Qwen2.5 1.5B",
        description = "Balanced quality/speed for richer assistant reasoning.",
        defaultLiteRtFileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        approxSizeLabel = "1.6 GB",
    ),
    QWEN3_4B(
        id = "qwen3_4b",
        label = "Qwen3 4B",
        description = "High-quality multilingual responses with higher compute cost.",
        defaultLiteRtFileName = "qwen3_4b_channelwise_int8_float32kv.litertlm",
        approxSizeLabel = "5.3 GB",
    );

    companion object {
        val default: EdgeModelProfile = GEMMA_EFFECTIVE_2B

        fun fromId(value: String): EdgeModelProfile {
            return entries.firstOrNull { it.id == value } ?: default
        }
    }
}

data class EdgeInferenceResult(
    val model: EdgeModelProfile,
    val strategyLabel: String,
    val summary: String,
    val urgencyScore: Int,
    val intentHints: List<IntentHint>,
    val suggestedActions: List<String>,
    val nativeModelUsed: Boolean,
    val nativeModelMessage: String,
    val nativeModelOutput: String? = null,
)

data class EdgeInferenceTrace(
    val result: EdgeInferenceResult,
    val prompt: String,
    val mode: String,
)

interface EdgeInferenceStrategy {
    val label: String
    fun infer(model: EdgeModelProfile, events: List<ContextEventPayload>): EdgeInferenceResult
}

object OnDeviceInferenceEngine {
    private val strategies: Map<EdgeModelProfile, EdgeInferenceStrategy> = mapOf(
        EdgeModelProfile.GEMMA_EFFECTIVE_2B to FastHeuristicStrategy,
        EdgeModelProfile.GEMMA_EFFECTIVE_4B to DeepHeuristicStrategy,
        EdgeModelProfile.QWEN3_0_6B to FastHeuristicStrategy,
        EdgeModelProfile.QWEN2_5_1_5B to DeepHeuristicStrategy,
        EdgeModelProfile.QWEN3_4B to DeepHeuristicStrategy,
    )

    fun infer(
        context: Context,
        model: EdgeModelProfile,
        events: List<ContextEventPayload>,
        runtimeConfig: LocalModelRuntimeConfig,
    ): EdgeInferenceResult {
        return inferWithTrace(
            context = context,
            model = model,
            events = events,
            runtimeConfig = runtimeConfig,
        ).result
    }

    fun inferWithTrace(
        context: Context,
        model: EdgeModelProfile,
        events: List<ContextEventPayload>,
        runtimeConfig: LocalModelRuntimeConfig,
    ): EdgeInferenceTrace {
        if (AppPrefs.isGlobalLockEnabled(context)) {
            return blockedByGlobalLockTrace(model = model, mode = "context_window")
        }

        val strategy = strategies[model] ?: FastHeuristicStrategy
        val heuristic = strategy.infer(model = model, events = events)
        val prompt = buildNativePrompt(events, heuristic)
        val native = runNativeModel(
            context = context,
            model = model,
            prompt = prompt,
            runtimeConfig = runtimeConfig,
        )
        return EdgeInferenceTrace(
            result = mergeWithNativeResult(heuristic, native),
            prompt = prompt,
            mode = "context_window",
        )
    }

    fun inferFromPrompt(
        context: Context,
        model: EdgeModelProfile,
        prompt: String,
        runtimeConfig: LocalModelRuntimeConfig,
    ): EdgeInferenceResult {
        return inferFromPromptWithTrace(
            context = context,
            model = model,
            prompt = prompt,
            runtimeConfig = runtimeConfig,
        ).result
    }

    fun inferFromPromptWithTrace(
        context: Context,
        model: EdgeModelProfile,
        prompt: String,
        runtimeConfig: LocalModelRuntimeConfig,
    ): EdgeInferenceTrace {
        if (AppPrefs.isGlobalLockEnabled(context)) {
            return blockedByGlobalLockTrace(model = model, mode = "direct_prompt")
        }

        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return EdgeInferenceTrace(
                result = emptyResult(model, "Prompt Test").copy(
                    summary = "Prompt test failed: empty prompt.",
                    nativeModelMessage = "Prompt test failed: empty prompt.",
                    nativeModelOutput = null,
                ),
                prompt = trimmedPrompt,
                mode = "direct_prompt",
            )
        }

        val native = runNativeModel(
            context = context,
            model = model,
            prompt = trimmedPrompt,
            runtimeConfig = runtimeConfig,
        )
        if (native.usedNativeModel && !native.output.isNullOrBlank()) {
            val output = native.output.trim()
            return EdgeInferenceTrace(
                result = EdgeInferenceResult(
                    model = model,
                    strategyLabel = "Direct Prompt (${model.label})",
                    summary = output,
                    urgencyScore = 0,
                    intentHints = emptyList(),
                    suggestedActions = emptyList(),
                    nativeModelUsed = true,
                    nativeModelMessage = native.message,
                    nativeModelOutput = output,
                ),
                prompt = trimmedPrompt,
                mode = "direct_prompt",
            )
        }

        val syntheticEvents = PromptContextBuilder.toSyntheticEvents(prompt)
        val strategy = strategies[model] ?: FastHeuristicStrategy
        val heuristicOnly = strategy.infer(model = model, events = syntheticEvents)
        return EdgeInferenceTrace(
            result = heuristicOnly.copy(
                summary = "${heuristicOnly.summary} [Prompt direct mode fallback, synthetic_events=${syntheticEvents.size}]",
                nativeModelUsed = false,
                nativeModelMessage = "Direct prompt failed: ${native.message}",
                nativeModelOutput = native.output?.trim(),
            ),
            prompt = trimmedPrompt,
            mode = "direct_prompt_fallback",
        )
    }

    private fun runNativeModel(
        context: Context,
        model: EdgeModelProfile,
        prompt: String,
        runtimeConfig: LocalModelRuntimeConfig,
    ): LocalModelRuntimeResult {
        return when (runtimeConfig.backend) {
            LocalModelBackend.LITERT_LM -> {
                LiteRtLmRuntime.getInstance(context).generate(
                    profile = model,
                    prompt = prompt,
                    config = runtimeConfig,
                )
            }

            LocalModelBackend.MEDIAPIPE_TASK -> {
                MediapipeLlmRuntime.getInstance(context).generate(
                    profile = model,
                    prompt = prompt,
                    config = runtimeConfig,
                )
            }
        }
    }

    private fun mergeWithNativeResult(
        heuristic: EdgeInferenceResult,
        native: LocalModelRuntimeResult,
    ): EdgeInferenceResult {
        if (!native.usedNativeModel || native.output.isNullOrBlank()) {
            return heuristic.copy(
                nativeModelUsed = false,
                nativeModelMessage = native.message,
                nativeModelOutput = native.output?.trim()?.takeIf { it.isNotBlank() },
            )
        }

        val output = native.output.trim()
        val clipped = output.take(420)
        val action = extractFirstAction(native.output)
        return heuristic.copy(
            summary = "${heuristic.summary}\nNative model: $clipped",
            suggestedActions = (heuristic.suggestedActions + action).filter { it.isNotBlank() }.distinct().take(6),
            nativeModelUsed = true,
            nativeModelMessage = native.message,
            nativeModelOutput = output,
        )
    }

    private fun buildNativePrompt(
        events: List<ContextEventPayload>,
        heuristic: EdgeInferenceResult,
    ): String {
        val eventsDigest = events
            .sortedByDescending { it.occurredAt }
            .take(20)
            .joinToString("\n") { "- [${it.category}] ${it.summary}" }

        val hints = heuristic.intentHints.joinToString("\n") { "- ${it.label}: ${it.reason}" }
        return """
            You are a personal proactive assistant running on-device.
            Based on recent context events and heuristic hints, provide:
            1) one concise situation summary
            2) one high-value next action suggestion
            Keep the answer under 120 words.

            Recent events:
            $eventsDigest

            Heuristic hints:
            $hints
        """.trimIndent()
    }

    private fun extractFirstAction(output: String): String {
        val lines = output
            .lineSequence()
            .map { it.trim().trimStart('-', '*', '•').trim() }
            .filter { it.isNotBlank() }
            .toList()
        return lines.firstOrNull { it.length >= 12 } ?: output.trim().take(160)
    }

    private fun blockedByGlobalLockTrace(
        model: EdgeModelProfile,
        mode: String,
    ): EdgeInferenceTrace {
        val lockMessage = "Global lock enabled: prompt inference is blocked."
        return EdgeInferenceTrace(
            result = EdgeInferenceResult(
                model = model,
                strategyLabel = "LOCKED",
                summary = lockMessage,
                urgencyScore = 0,
                intentHints = listOf(
                    IntentHint(
                        label = "Global lock active",
                        confidence = 1.0f,
                        reason = "Disable lock to allow local or cloud model inference.",
                    )
                ),
                suggestedActions = listOf("Disable Global Lock to run inference."),
                nativeModelUsed = false,
                nativeModelMessage = lockMessage,
                nativeModelOutput = null,
            ),
            prompt = "[BLOCKED_BY_GLOBAL_LOCK]",
            mode = "${mode}_blocked",
        )
    }
}

private object FastHeuristicStrategy : EdgeInferenceStrategy {
    override val label: String = "Fast Heuristic"

    override fun infer(model: EdgeModelProfile, events: List<ContextEventPayload>): EdgeInferenceResult {
        if (events.isEmpty()) {
            return emptyResult(model, label)
        }

        val recent = events.sortedByDescending { it.occurredAt }.take(24)
        val byCategory = recent.groupingBy { it.category.lowercase() }.eachCount()
        val notificationCount = (byCategory["notification"] ?: 0) + (byCategory["communication"] ?: 0)
        val mobilityCount = (byCategory["location"] ?: 0) + (byCategory["motion"] ?: 0)
        val taskSignals = (byCategory["task"] ?: 0) + (byCategory["calendar"] ?: 0)
        val highSensitivityCount = recent.count { it.sensitivity.equals("HIGH", ignoreCase = true) }

        val hints = mutableListOf<IntentHint>()
        val actions = mutableListOf<String>()

        if (notificationCount > 0) {
            hints += IntentHint(
                label = "Triage communication backlog",
                confidence = confidenceFromCount(notificationCount),
                reason = "Detected $notificationCount communication signals.",
            )
            actions += "Draft high-priority message replies."
        }

        if (mobilityCount > 0) {
            hints += IntentHint(
                label = "Offer commute-aware support",
                confidence = confidenceFromCount(mobilityCount),
                reason = "Location and motion suggest context switching.",
            )
            actions += "Prepare next-stop briefing."
        }

        if (taskSignals > 0) {
            hints += IntentHint(
                label = "Pre-brief upcoming work",
                confidence = confidenceFromCount(taskSignals),
                reason = "Task-like signals indicate near-term commitments.",
            )
            actions += "Generate quick prep notes for next task."
        }

        if (hints.isEmpty()) {
            hints += IntentHint(
                label = "Maintain passive watch mode",
                confidence = 0.52f,
                reason = "Insufficient variance in recent signals.",
            )
            actions += "Continue low-noise collection."
        }

        val urgency = (18 + notificationCount * 7 + taskSignals * 8 + highSensitivityCount * 4)
            .coerceIn(0, 100)

        val topCategories = byCategory.entries.sortedByDescending { it.value }.take(3)
            .joinToString { "${it.key}:${it.value}" }

        return EdgeInferenceResult(
            model = model,
            strategyLabel = label,
            summary = "${model.label}[$label] processed ${recent.size} events, top=$topCategories",
            urgencyScore = urgency,
            intentHints = hints.take(4),
            suggestedActions = actions.distinct().take(4),
            nativeModelUsed = false,
            nativeModelMessage = "Native model not attempted yet",
        )
    }
}

private object DeepHeuristicStrategy : EdgeInferenceStrategy {
    override val label: String = "Deep Heuristic"

    override fun infer(model: EdgeModelProfile, events: List<ContextEventPayload>): EdgeInferenceResult {
        if (events.isEmpty()) {
            return emptyResult(model, label)
        }

        val recent = events.sortedByDescending { it.occurredAt }.take(64)
        val byCategory = recent.groupingBy { it.category.lowercase() }.eachCount()
        val summaries = recent.joinToString(" ") { it.summary.lowercase() }

        val commSignals = signalScore(summaries, listOf("email", "reply", "slack", "message", "inbox"))
        val planningSignals = signalScore(summaries, listOf("meeting", "calendar", "deadline", "todo", "task"))
        val researchSignals = signalScore(summaries, listOf("investigate", "research", "analyze", "brief", "read"))
        val urgencyTerms = signalScore(summaries, listOf("urgent", "asap", "now", "tonight", "today"))
        val highSensitivityCount = recent.count { it.sensitivity.equals("HIGH", ignoreCase = true) }

        val hints = mutableListOf<IntentHint>()
        val actions = mutableListOf<String>()

        if (commSignals > 0 || (byCategory["notification"] ?: 0) > 0) {
            hints += IntentHint(
                label = "Prioritize communication responses",
                confidence = advancedConfidence(commSignals + (byCategory["notification"] ?: 0)),
                reason = "Message and inbox semantics indicate pending responses.",
            )
            actions += "Prepare ranked response bundle with draft options."
        }

        if (planningSignals > 0 || (byCategory["calendar"] ?: 0) > 0) {
            hints += IntentHint(
                label = "Plan-aware execution support",
                confidence = advancedConfidence(planningSignals + (byCategory["calendar"] ?: 0)),
                reason = "Detected meeting/deadline language in recent context.",
            )
            actions += "Create timeline and pre-meeting checklist."
        }

        if (researchSignals > 0) {
            hints += IntentHint(
                label = "Precompute deep research",
                confidence = advancedConfidence(researchSignals),
                reason = "Research-oriented language suggests discovery workload.",
            )
            actions += "Start deep research brief and source map."
        }

        if (hints.isEmpty()) {
            hints += IntentHint(
                label = "Low confidence proactive mode",
                confidence = 0.5f,
                reason = "Signals are weak or diffuse across categories.",
            )
            actions += "Keep monitoring until stronger user intent appears."
        }

        val urgency = (
            22 +
                commSignals * 5 +
                planningSignals * 7 +
                researchSignals * 6 +
                urgencyTerms * 9 +
                highSensitivityCount * 4
            ).coerceIn(0, 100)

        val categoryDigest = byCategory.entries.sortedByDescending { it.value }.take(5)
            .joinToString { "${it.key}:${it.value}" }

        return EdgeInferenceResult(
            model = model,
            strategyLabel = label,
            summary = "${model.label}[$label] analyzed ${recent.size} events, digest=$categoryDigest, semantic_signals=${
                mapOf(
                    "comm" to commSignals,
                    "planning" to planningSignals,
                    "research" to researchSignals,
                )
            }",
            urgencyScore = urgency,
            intentHints = hints.take(5),
            suggestedActions = actions.distinct().take(5),
            nativeModelUsed = false,
            nativeModelMessage = "Native model not attempted yet",
        )
    }
}

private object PromptContextBuilder {
    fun toSyntheticEvents(prompt: String): List<ContextEventPayload> {
        val cleaned = prompt.trim()
        if (cleaned.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        val lowered = cleaned.lowercase()
        val categories = linkedSetOf<String>()

        if (containsAny(lowered, listOf("meet", "calendar", "deadline", "task", "todo"))) {
            categories += "calendar"
            categories += "task"
        }
        if (containsAny(lowered, listOf("email", "slack", "message", "reply", "inbox"))) {
            categories += "notification"
            categories += "communication"
        }
        if (containsAny(lowered, listOf("drive", "commute", "travel", "arrive", "airport", "train"))) {
            categories += "location"
            categories += "motion"
        }
        if (containsAny(lowered, listOf("photo", "video", "note", "recording", "media"))) {
            categories += "media"
        }
        if (categories.isEmpty()) {
            categories += "device_state"
        }

        val sensitivity = when {
            containsAny(lowered, listOf("password", "bank", "medical", "private")) -> "HIGH"
            containsAny(lowered, listOf("work", "client", "contract")) -> "MEDIUM"
            else -> "LOW"
        }

        return categories.mapIndexed { idx, category ->
            ContextEventPayload(
                eventId = "synthetic-${UUID.randomUUID()}",
                occurredAt = now - idx * 1_000L,
                source = "inference_test_dialog",
                category = category,
                summary = cleaned,
                payload = mapOf("testPrompt" to cleaned, "categoryHint" to category),
                sensitivity = sensitivity,
                ttlSeconds = 3_600,
            )
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
}

private fun confidenceFromCount(count: Int): Float {
    return (0.45f + (count * 0.08f)).coerceIn(0.45f, 0.95f)
}

private fun advancedConfidence(score: Int): Float {
    return (0.5f + score * 0.06f).coerceIn(0.5f, 0.97f)
}

private fun signalScore(text: String, keywords: List<String>): Int {
    return keywords.count { text.contains(it) }
}

private fun emptyResult(model: EdgeModelProfile, strategyLabel: String): EdgeInferenceResult {
    return EdgeInferenceResult(
        model = model,
        strategyLabel = strategyLabel,
        summary = "${model.label}[$strategyLabel] has no recent context. Keep collecting signals.",
        urgencyScore = 10,
        intentHints = listOf(
            IntentHint(
                label = "Collect more context",
                confidence = 0.6f,
                reason = "No recent events available for inference.",
            )
        ),
        suggestedActions = listOf("Continue background collection for 15-30 minutes."),
        nativeModelUsed = false,
        nativeModelMessage = "Native model not attempted yet",
    )
}
