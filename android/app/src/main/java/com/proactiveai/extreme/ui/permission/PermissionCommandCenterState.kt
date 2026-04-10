package com.proactiveai.extreme.ui.permission

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.IntentHint
import com.proactiveai.extreme.core.context.plugins.AudioAmbientPlugin
import com.proactiveai.extreme.core.edge.EdgeInferenceResult
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.core.model.EvaluationMetrics
import com.proactiveai.extreme.core.model.PermissionDescriptor
import com.proactiveai.extreme.core.model.PermissionGate
import com.proactiveai.extreme.core.model.PluginDescriptor
import com.proactiveai.extreme.data.ExtremeDefaults
import com.proactiveai.extreme.orchestrator.ActionStepPayload
import com.proactiveai.extreme.orchestrator.ActionPlanPayload
import com.proactiveai.extreme.orchestrator.ConnectorStatusPayload
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.permission.PermissionStatusResolver
import com.proactiveai.extreme.storage.ActionHistoryStore
import com.proactiveai.extreme.storage.ActionQueueStore
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.sync.ActionExecutionScheduler
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val UI_STITCH_GAP_RESET_MS = 9_000L
private const val UI_MAX_STITCH_CHARS = 1_400

data class PermissionUiState(
    val id: String,
    val pluginId: String,
    val title: String,
    val androidPermission: String?,
    val gate: PermissionGate,
    val purpose: String,
    val ttlHours: Int,
    val granted: Boolean,
)

data class PluginUiState(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val riskLabel: String,
    val permissions: List<PermissionUiState>,
)

data class ConnectorUiState(
    val id: String,
    val title: String,
    val connected: Boolean,
    val accountLabel: String?,
    val lastConnectedLabel: String?,
)

data class ActionHistoryUiState(
    val id: Long,
    val timestampLabel: String,
    val eventType: String,
    val summary: String,
    val detail: String?,
)

data class ExecutionQueueUiState(
    val id: Long,
    val summary: String,
    val status: String,
    val attempts: String,
    val nextRetryLabel: String?,
    val lastError: String?,
    val updatedAtLabel: String,
)

data class ContextLogUiState(
    val id: Long,
    val timestampLabel: String,
    val summary: String,
    val synced: Boolean,
)

data class ContextTimelineItemUiState(
    val id: Long,
    val timestampLabel: String,
    val source: String,
    val category: String,
    val summary: String,
    val sensitivity: String,
    val synced: Boolean,
)

data class ModelInteractionUiState(
    val id: Long,
    val timestampLabel: String,
    val trigger: String,
    val modelLabel: String,
    val prompt: String,
    val response: String,
    val status: String,
    val synced: Boolean,
)

data class AudioClipDebugUiState(
    val id: String,
    val filePath: String,
    val fileName: String,
    val capturedAtLabel: String,
    val durationLabel: String,
    val sizeLabel: String,
    val status: String,
    val strategy: String,
    val transcript: String,
    val stitchedTranscript: String,
    val reason: String,
    val modelStatus: String,
)

private data class AudioClipRecord(
    val wav: File,
    val capturedAtMs: Long,
    val durationMs: Long,
    val status: String,
    val strategy: String,
    val transcript: String,
    val stitchedTranscript: String,
    val reason: String,
    val modelStatus: String,
)

enum class ActionHistoryFilter(val label: String) {
    ALL("All"),
    PLAN("Plan"),
    HITL("HITL"),
    EXECUTION("Execution"),
    QUEUE("Queue"),
}

class PermissionCommandCenterState internal constructor(
    private val appContext: Context,
    private val descriptors: List<PluginDescriptor>,
) {
    private val historyStore = ActionHistoryStore.getInstance(appContext)
    private val queueStore = ActionQueueStore.getInstance(appContext)
    private val audioPluginDescriptor = descriptors.firstOrNull { it.id == "audio_ambient" }
    private val audioReplayPlugin by lazy {
        audioPluginDescriptor?.let { AudioAmbientPlugin(appContext, it) }
    }

    private val _plugins = mutableStateListOf<PluginUiState>()
    val plugins: List<PluginUiState> get() = _plugins

    private val _connectors = mutableStateListOf<ConnectorUiState>()
    val connectors: List<ConnectorUiState> get() = _connectors

    private val _actionHistory = mutableStateListOf<ActionHistoryUiState>()
    val actionHistory: List<ActionHistoryUiState> get() = _actionHistory

    private val _executionQueue = mutableStateListOf<ExecutionQueueUiState>()
    val executionQueue: List<ExecutionQueueUiState> get() = _executionQueue

    private val _contextLogs = mutableStateListOf<ContextLogUiState>()
    val contextLogs: List<ContextLogUiState> get() = _contextLogs

    private val _contextTimeline = mutableStateListOf<ContextTimelineItemUiState>()
    val contextTimeline: List<ContextTimelineItemUiState> get() = _contextTimeline

    private val _modelInteractions = mutableStateListOf<ModelInteractionUiState>()
    val modelInteractions: List<ModelInteractionUiState> get() = _modelInteractions

    private val _audioClips = mutableStateListOf<AudioClipDebugUiState>()
    val audioClips: List<AudioClipDebugUiState> get() = _audioClips

    private val _intentHints = mutableStateListOf<IntentHint>()
    val intentHints: List<IntentHint> get() = _intentHints

    private val _suggestedActions = mutableStateListOf<String>()
    val suggestedActions: List<String> get() = _suggestedActions

    private val _stepDecisions = mutableStateMapOf<String, Boolean>()
    private val _stepExecutionResults = mutableStateMapOf<String, String>()

    var masterEnabled by mutableStateOf(AppPrefs.isMasterEnabled(appContext))
        private set

    var collectionEnabled by mutableStateOf(AppPrefs.isCollectionEnabled(appContext))
        private set

    var orchestratorHealthLabel by mutableStateOf("Unknown")
        private set

    var unsyncedEvents by mutableStateOf(0)
        private set

    var lastPlanPreview by mutableStateOf("No plan fetched yet")
        private set

    var edgeModelId by mutableStateOf(AppPrefs.getEdgeModel(appContext))
        private set

    var autoExecuteLowRisk by mutableStateOf(AppPrefs.isAutoExecuteLowRisk(appContext))
        private set

    var localModelEnabled by mutableStateOf(AppPrefs.isLocalModelEnabled(appContext))
        private set

    var localModelPath2B by mutableStateOf(AppPrefs.getLocalModelPath2B(appContext))
        private set

    var localModelPath4B by mutableStateOf(AppPrefs.getLocalModelPath4B(appContext))
        private set

    var localModelBackendId by mutableStateOf(AppPrefs.getLocalModelBackend(appContext))
        private set

    var localGgufPath2B by mutableStateOf(AppPrefs.getLocalGgufPath2B(appContext))
        private set

    var localGgufPath4B by mutableStateOf(AppPrefs.getLocalGgufPath4B(appContext))
        private set

    var localLlamaContextSize by mutableStateOf(AppPrefs.getLocalLlamaContextSize(appContext))
        private set

    var localLlamaThreads by mutableStateOf(AppPrefs.getLocalLlamaThreads(appContext))
        private set

    var huggingFaceToken by mutableStateOf(AppPrefs.getHuggingFaceToken(appContext))
        private set

    var latestInferenceSummary by mutableStateOf("No on-device inference yet")
        private set

    var latestInferenceStrategy by mutableStateOf("No strategy yet")
        private set

    var latestInferenceUrgency by mutableStateOf(0)
        private set

    var latestNativeModelStatus by mutableStateOf("Native model not used yet")
        private set

    var latestNativeModelOutput by mutableStateOf("")
        private set

    var modelDownloadStatus by mutableStateOf("No model download yet")
        private set

    var modelDownloadProgress by mutableStateOf(-1)
        private set

    var currentPlan by mutableStateOf<ActionPlanPayload?>(null)
        private set

    var latestExecutionLabel by mutableStateOf("No actions executed yet")
        private set

    var connectorStatusMessage by mutableStateOf("Connectors not loaded yet")
        private set

    var queueStatusMessage by mutableStateOf("Queue not loaded yet")
        private set

    var contextLogStatusMessage by mutableStateOf("No context logs yet")
        private set

    var contextTimelineStatusMessage by mutableStateOf("No context timeline yet")
        private set

    var modelInteractionStatusMessage by mutableStateOf("No local model calls yet")
        private set

    var audioClipStatusMessage by mutableStateOf("No audio clips captured yet")
        private set

    var contextInsightStatusMessage by mutableStateOf("No context insight yet")
        private set

    var contextInsightSummary by mutableStateOf("Tap Analyze to infer potential proactive help from current contexts.")
        private set

    private val _contextInsightActions = mutableStateListOf<String>()
    val contextInsightActions: List<String> get() = _contextInsightActions

    var assistantBriefStatusMessage by mutableStateOf("No proactive assistant brief yet")
        private set

    var assistantBriefText by mutableStateOf("Generate a brief to understand your current state and next best actions.")
        private set

    private val _assistantHighlights = mutableStateListOf<String>()
    val assistantHighlights: List<String> get() = _assistantHighlights

    var historyFilter by mutableStateOf(ActionHistoryFilter.ALL)
        private set

    var historyExportLabel by mutableStateOf("No history export yet")
        private set

    var metrics by mutableStateOf(AppPrefs.getEvaluationMetrics(appContext))
        private set

    val edgeModelOptions: List<EdgeModelProfile> = EdgeModelProfile.entries

    val readinessScore: Int
        get() {
            val total = plugins.sumOf { it.permissions.size }
            if (total == 0) return 0
            val granted = plugins.sumOf { plugin -> plugin.permissions.count { it.granted } }
            return (granted * 100) / total
        }

    init {
        refreshGrantStates()
    }

    fun refreshGrantStates() {
        val enabledPlugins = AppPrefs.getEnabledPlugins(appContext)
        masterEnabled = AppPrefs.isMasterEnabled(appContext)
        collectionEnabled = AppPrefs.isCollectionEnabled(appContext)
        unsyncedEvents = ContextEventStore.getInstance(appContext).countUnsynced()
        autoExecuteLowRisk = AppPrefs.isAutoExecuteLowRisk(appContext)
        edgeModelId = AppPrefs.getEdgeModel(appContext)
        localModelEnabled = AppPrefs.isLocalModelEnabled(appContext)
        localModelPath2B = AppPrefs.getLocalModelPath2B(appContext)
        localModelPath4B = AppPrefs.getLocalModelPath4B(appContext)
        localModelBackendId = AppPrefs.getLocalModelBackend(appContext)
        localGgufPath2B = AppPrefs.getLocalGgufPath2B(appContext)
        localGgufPath4B = AppPrefs.getLocalGgufPath4B(appContext)
        localLlamaContextSize = AppPrefs.getLocalLlamaContextSize(appContext)
        localLlamaThreads = AppPrefs.getLocalLlamaThreads(appContext)
        huggingFaceToken = AppPrefs.getHuggingFaceToken(appContext)
        refreshMetrics()

        _plugins.clear()
        _plugins.addAll(
            descriptors.map { descriptor ->
                PluginUiState(
                    id = descriptor.id,
                    title = descriptor.title,
                    description = descriptor.description,
                    enabled = descriptor.id in enabledPlugins,
                    riskLabel = descriptor.riskLevel.name,
                    permissions = descriptor.permissions.map { permission ->
                        permission.toUiState(
                            pluginId = descriptor.id,
                            granted = PermissionStatusResolver.isGranted(appContext, permission),
                        )
                    },
                )
            }
        )

        refreshActionHistory()
        refreshQueue()
        refreshContextLogs()
        refreshContextTimeline()
        refreshModelInteractions()
        refreshAudioClips()
    }

    fun toggleMaster(enabled: Boolean) {
        AppPrefs.setMasterEnabled(appContext, enabled)
        masterEnabled = enabled
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) {
        val current = AppPrefs.getEnabledPlugins(appContext).toMutableSet()
        if (enabled) {
            current += pluginId
        } else {
            current -= pluginId
        }
        AppPrefs.setEnabledPlugins(appContext, current)
        refreshGrantStates()
    }

    fun persistCollectionEnabled(enabled: Boolean) {
        AppPrefs.setCollectionEnabled(appContext, enabled)
        collectionEnabled = enabled
    }

    fun setEdgeModel(modelId: String) {
        AppPrefs.setEdgeModel(appContext, modelId)
        edgeModelId = modelId
    }

    fun persistAutoExecuteLowRisk(enabled: Boolean) {
        AppPrefs.setAutoExecuteLowRisk(appContext, enabled)
        autoExecuteLowRisk = enabled
    }

    fun persistLocalModelConfig(
        enabled: Boolean,
        modelPath2B: String,
        modelPath4B: String,
        backendId: String = localModelBackendId,
        ggufPath2B: String = localGgufPath2B,
        ggufPath4B: String = localGgufPath4B,
        llamaContextSize: Int = localLlamaContextSize,
        llamaThreads: Int = localLlamaThreads,
    ) {
        AppPrefs.setLocalModelEnabled(appContext, enabled)
        AppPrefs.setLocalModelPath2B(appContext, modelPath2B)
        AppPrefs.setLocalModelPath4B(appContext, modelPath4B)
        AppPrefs.setLocalModelBackend(appContext, backendId)
        AppPrefs.setLocalGgufPath2B(appContext, ggufPath2B)
        AppPrefs.setLocalGgufPath4B(appContext, ggufPath4B)
        AppPrefs.setLocalLlamaContextSize(appContext, llamaContextSize)
        AppPrefs.setLocalLlamaThreads(appContext, llamaThreads)
        localModelEnabled = enabled
        localModelPath2B = modelPath2B
        localModelPath4B = modelPath4B
        localModelBackendId = backendId
        localGgufPath2B = ggufPath2B
        localGgufPath4B = ggufPath4B
        localLlamaContextSize = llamaContextSize
        localLlamaThreads = llamaThreads
    }

    fun persistHuggingFaceToken(token: String) {
        AppPrefs.setHuggingFaceToken(appContext, token)
        huggingFaceToken = token
    }

    fun setInference(result: EdgeInferenceResult) {
        latestInferenceSummary = result.summary
        latestInferenceStrategy = result.strategyLabel
        latestInferenceUrgency = result.urgencyScore
        latestNativeModelStatus = result.nativeModelMessage
        latestNativeModelOutput = result.nativeModelOutput.orEmpty()
        _intentHints.clear()
        _intentHints.addAll(result.intentHints)
        _suggestedActions.clear()
        _suggestedActions.addAll(result.suggestedActions)
    }

    fun localRuntimeConfig(): LocalModelRuntimeConfig {
        return LocalModelRuntimeConfig(
            enabled = localModelEnabled,
            backend = LocalModelBackend.fromId(localModelBackendId),
            modelPath2B = localModelPath2B,
            modelPath4B = localModelPath4B,
            ggufPath2B = localGgufPath2B,
            ggufPath4B = localGgufPath4B,
            llamaContextSize = localLlamaContextSize,
            llamaThreads = localLlamaThreads,
        )
    }

    fun updateModelDownloadStatus(status: String) {
        modelDownloadStatus = status
    }

    fun updateModelDownloadProgress(percent: Int) {
        modelDownloadProgress = percent
    }

    fun updateCurrentPlan(plan: ActionPlanPayload?) {
        currentPlan = plan
        _stepDecisions.clear()
        _stepExecutionResults.clear()
        latestExecutionLabel = "No actions executed yet"
        lastPlanPreview = if (plan == null) {
            "Plan fetch failed"
        } else {
            "Plan: ${plan.goal} | steps=${plan.steps.size} | risk=${plan.riskLevel.name}"
        }
        if (plan != null) {
            appendHistory(
                eventType = "PLAN",
                planId = plan.planId,
                stepId = null,
                summary = "Plan generated: ${plan.goal}",
                detail = "risk=${plan.riskLevel.name}, steps=${plan.steps.size}",
            )
        }
    }

    fun markStepDecision(stepId: String, approved: Boolean) {
        _stepDecisions[stepId] = approved
        appendHistory(
            eventType = "HITL",
            planId = currentPlan?.planId,
            stepId = stepId,
            summary = if (approved) "Step approved" else "Step denied",
            detail = "stepId=$stepId",
        )
    }

    fun markStepExecution(stepId: String, resultLabel: String) {
        _stepExecutionResults[stepId] = resultLabel
        latestExecutionLabel = resultLabel
        appendHistory(
            eventType = "EXECUTION",
            planId = currentPlan?.planId,
            stepId = stepId,
            summary = "Execution result",
            detail = resultLabel,
        )
    }

    fun stepDecision(stepId: String): Boolean? = _stepDecisions[stepId]

    fun stepExecutionResult(stepId: String): String? = _stepExecutionResults[stepId]

    fun recordSuggestionGenerated() {
        AppPrefs.recordSuggestionGenerated(appContext)
        refreshMetrics()
    }

    fun recordSuggestionAccepted() {
        AppPrefs.recordSuggestionAccepted(appContext)
        refreshMetrics()
    }

    fun recordExecution(successful: Boolean) {
        AppPrefs.recordExecution(appContext, successful)
        refreshMetrics()
    }

    fun recordInterruptionDecision(positive: Boolean) {
        AppPrefs.recordInterruptionDecision(appContext, positive)
        refreshMetrics()
    }

    fun refreshMetrics() {
        metrics = AppPrefs.getEvaluationMetrics(appContext)
    }

    fun resetMetrics() {
        AppPrefs.resetEvaluationMetrics(appContext)
        refreshMetrics()
    }

    fun pendingRuntimePermissions(): List<String> {
        val enabledPluginIds = AppPrefs.getEnabledPlugins(appContext)
        val activePermissions = descriptors
            .filter { it.id in enabledPluginIds }
            .flatMap { it.permissions }

        return PermissionStatusResolver.runtimePermissionsToRequest(appContext, activePermissions)
    }

    fun firstPendingSettingsPermission(): PermissionUiState? {
        return plugins
            .asSequence()
            .filter { it.enabled }
            .flatMap { it.permissions.asSequence() }
            .firstOrNull {
                !it.granted && (
                    it.gate != PermissionGate.RUNTIME_DIALOG ||
                        (it.id == "background_location" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    )
            }
    }

    fun setOrchestratorHealth(healthy: Boolean?) {
        orchestratorHealthLabel = when (healthy) {
            true -> "Healthy"
            false -> "Unreachable"
            null -> "Checking..."
        }
    }

    fun setPlanPreview(text: String) {
        lastPlanPreview = text
    }

    fun setConnectors(items: List<ConnectorStatusPayload>) {
        val sorted = items.sortedBy { it.connector }
        _connectors.clear()
        _connectors.addAll(sorted.map { payload ->
            ConnectorUiState(
                id = payload.connector,
                title = connectorTitle(payload.connector),
                connected = payload.connected,
                accountLabel = payload.accountLabel,
                lastConnectedLabel = payload.lastConnectedAt?.let(::formatTime),
            )
        })
        val connectedCount = sorted.count { it.connected }
        connectorStatusMessage = "Connected $connectedCount/${sorted.size}"
    }

    fun updateConnectorStatusMessage(text: String) {
        connectorStatusMessage = text
    }

    fun refreshActionHistory() {
        val filtered = historyStore.recent(limit = 200).filter { item ->
            when (historyFilter) {
                ActionHistoryFilter.ALL -> true
                ActionHistoryFilter.PLAN -> item.eventType == "PLAN"
                ActionHistoryFilter.HITL -> item.eventType == "HITL"
                ActionHistoryFilter.EXECUTION -> item.eventType == "EXECUTION"
                ActionHistoryFilter.QUEUE -> item.eventType.startsWith("QUEUE_")
            }
        }
        _actionHistory.clear()
        _actionHistory.addAll(
            filtered.take(60).map { item ->
                ActionHistoryUiState(
                    id = item.id,
                    timestampLabel = formatTime(item.createdAt),
                    eventType = item.eventType,
                    summary = item.summary,
                    detail = item.detail,
                )
            }
        )
    }

    fun clearActionHistory() {
        historyStore.clear()
        refreshActionHistory()
    }

    fun cycleHistoryFilter() {
        val values = ActionHistoryFilter.entries
        val next = (values.indexOf(historyFilter) + 1) % values.size
        historyFilter = values[next]
        refreshActionHistory()
    }

    fun exportActionHistory(): String? {
        val rows = historyStore.recent(limit = 500)
        if (rows.isEmpty()) {
            historyExportLabel = "No rows to export"
            return null
        }

        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val exportDir = File(appContext.filesDir, "exports").apply { mkdirs() }
        val output = File(exportDir, "action-history-$ts.txt")

        output.bufferedWriter().use { writer ->
            rows.forEach { item ->
                writer.appendLine(
                    "${formatTime(item.createdAt)} | ${item.eventType} | ${item.summary} | ${item.detail.orEmpty()}"
                )
            }
        }
        historyExportLabel = "Exported: ${output.absolutePath}"
        return output.absolutePath
    }

    fun refreshQueue() {
        val rows = queueStore.recent(limit = 40)
        _executionQueue.clear()
        _executionQueue.addAll(
            rows.map { item ->
                ExecutionQueueUiState(
                    id = item.id,
                    summary = "${item.connector}.${item.operation} (${item.stepId.take(8)})",
                    status = item.status,
                    attempts = "${item.attemptCount}/${item.maxAttempts}",
                    nextRetryLabel = item.nextRetryAt.takeIf { it in 1 until Long.MAX_VALUE }?.let(::formatTime),
                    lastError = item.lastError,
                    updatedAtLabel = formatTime(item.updatedAt),
                )
            }
        )

        val pending = queueStore.countByStatus(ActionQueueStore.STATUS_PENDING)
        val failed = queueStore.countByStatus(ActionQueueStore.STATUS_FAILED)
        val succeeded = queueStore.countByStatus(ActionQueueStore.STATUS_SUCCEEDED)
        queueStatusMessage = "Pending=$pending Failed=$failed Succeeded=$succeeded"
    }

    fun refreshContextLogs(limit: Int = 24) {
        val rows = ContextEventStore.getInstance(appContext)
            .getRecent(limit = 600)
            .filter { it.category == "context_log" }
            .take(limit)

        _contextLogs.clear()
        _contextLogs.addAll(
            rows.map { row ->
                ContextLogUiState(
                    id = row.id,
                    timestampLabel = formatTime(row.occurredAt),
                    summary = row.summary,
                    synced = row.synced,
                )
            }
        )

        contextLogStatusMessage = if (rows.isEmpty()) {
            "No context logs yet. Start service and wait for next 1-minute tick."
        } else {
            "Latest log: ${formatTime(rows.first().occurredAt)} | showing ${rows.size} entries"
        }
    }

    fun refreshContextTimeline(limit: Int = 200) {
        val rows = ContextEventStore.getInstance(appContext).getRecent(limit)
        _contextTimeline.clear()
        _contextTimeline.addAll(
            rows.map { row ->
                ContextTimelineItemUiState(
                    id = row.id,
                    timestampLabel = formatTime(row.occurredAt),
                    source = row.source,
                    category = row.category,
                    summary = row.summary,
                    sensitivity = row.sensitivity,
                    synced = row.synced,
                )
            }
        )

        contextTimelineStatusMessage = if (rows.isEmpty()) {
            "No context events yet. Start collection service first."
        } else {
            "Showing latest ${rows.size} context events (newest first)"
        }
    }

    fun refreshModelInteractions(limit: Int = 120) {
        val rows = ContextEventStore.getInstance(appContext)
            .getRecent(limit = 800)
            .filter { it.category == "model_io" }
            .take(limit)

        _modelInteractions.clear()
        _modelInteractions.addAll(
            rows.map { row ->
                val payload = kotlin.runCatching { JSONObject(row.payloadJson).toMap() }.getOrNull().orEmpty()
                val prompt = payload["prompt"]?.toString().orEmpty()
                val response = payload["response"]?.toString().orEmpty()
                ModelInteractionUiState(
                    id = row.id,
                    timestampLabel = formatTime(row.occurredAt),
                    trigger = payload["trigger"]?.toString().orEmpty().ifBlank { "unknown" },
                    modelLabel = payload["model"]?.toString().orEmpty().ifBlank { row.source },
                    prompt = prompt,
                    response = response,
                    status = payload["nativeStatus"]?.toString().orEmpty().ifBlank { row.summary },
                    synced = row.synced,
                )
            }
        )

        modelInteractionStatusMessage = if (rows.isEmpty()) {
            "No local model prompt/response history yet."
        } else {
            "Showing ${rows.size} local model calls (newest first)"
        }
    }

    fun refreshAudioClips(limit: Int = 80) {
        val dir = File(appContext.filesDir, "audio_capture")
        if (!dir.exists()) {
            _audioClips.clear()
            audioClipStatusMessage = "No audio capture directory yet. Expected: ${dir.absolutePath}"
            return
        }

        val wavFiles = dir.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }.orEmpty().take(limit)

        val records = wavFiles.map { wav ->
            val metaFile = File(wav.parentFile, "${wav.nameWithoutExtension}.json")
            val meta = kotlin.runCatching {
                if (metaFile.exists()) JSONObject(metaFile.readText()).toMap() else emptyMap()
            }.getOrNull().orEmpty()

            val durationMs = (meta["durationMs"] as? Number)?.toLong()
                ?: estimateWavDurationMs(wav.length())
            val status = meta["status"]?.toString().orEmpty().ifBlank { "unknown" }
            val strategy = meta["strategy"]?.toString().orEmpty().ifBlank { "unknown" }
            val transcript = meta["transcript"]?.toString().orEmpty()
            val stitchedTranscript = meta["stitchedTranscript"]?.toString().orEmpty()
            val reason = meta["reason"]?.toString().orEmpty()
            val modelStatus = meta["modelStatus"]?.toString().orEmpty()

            AudioClipRecord(
                wav = wav,
                capturedAtMs = wav.lastModified(),
                durationMs = durationMs,
                status = status,
                strategy = strategy,
                transcript = transcript,
                stitchedTranscript = stitchedTranscript,
                reason = reason,
                modelStatus = modelStatus,
            )
        }

        val stitchedById = buildRealtimeStitchedTranscripts(records)

        _audioClips.clear()
        _audioClips.addAll(
            records.map { record ->
                val finalStitched = if (record.stitchedTranscript.isNotBlank()) {
                    record.stitchedTranscript
                } else {
                    stitchedById[record.wav.absolutePath].orEmpty()
                }
                AudioClipDebugUiState(
                    id = record.wav.absolutePath,
                    filePath = record.wav.absolutePath,
                    fileName = record.wav.name,
                    capturedAtLabel = formatTime(record.capturedAtMs),
                    durationLabel = formatDuration(record.durationMs),
                    sizeLabel = formatSize(record.wav.length()),
                    status = record.status,
                    strategy = record.strategy,
                    transcript = record.transcript,
                    stitchedTranscript = finalStitched,
                    reason = record.reason,
                    modelStatus = record.modelStatus,
                )
            }
        )

        audioClipStatusMessage = if (wavFiles.isEmpty()) {
            "No .wav clips yet. Keep service running and speak near the phone. Dir: ${dir.absolutePath}"
        } else {
            "Showing ${wavFiles.size} recent audio clips | Dir: ${dir.absolutePath}"
        }
    }

    fun clearAudioClips() {
        val dir = File(appContext.filesDir, "audio_capture")
        dir.listFiles()?.forEach { file ->
            kotlin.runCatching { file.delete() }
        }
        refreshAudioClips()
    }

    suspend fun replayLatestAudioClips(limit: Int = 8) {
        val plugin = audioReplayPlugin
        if (plugin == null) {
            audioClipStatusMessage = "Audio replay unavailable: audio_ambient descriptor missing."
            return
        }

        val dir = File(appContext.filesDir, "audio_capture")
        if (!dir.exists()) {
            audioClipStatusMessage = "No audio capture directory yet. Expected: ${dir.absolutePath}"
            return
        }

        val wavFiles = dir.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }.orEmpty().take(limit)

        if (wavFiles.isEmpty()) {
            audioClipStatusMessage = "No wav clips available to replay."
            return
        }

        var recognized = 0
        var noSpeech = 0
        var errors = 0
        wavFiles.forEach { wav ->
            val outcome = plugin.reprocessExistingWavClip(wav.absolutePath)
            when (outcome.status) {
                "recognized" -> recognized += 1
                "no_speech" -> noSpeech += 1
                else -> errors += 1
            }
        }

        refreshAudioClips(limit = maxOf(limit, 20))
        audioClipStatusMessage = "Replay done: total=${wavFiles.size}, recognized=$recognized, no_speech=$noSpeech, errors=$errors | Dir: ${dir.absolutePath}"
    }

    suspend fun replaySingleAudioClip(path: String) {
        val plugin = audioReplayPlugin
        if (plugin == null) {
            audioClipStatusMessage = "Audio replay unavailable: audio_ambient descriptor missing."
            return
        }
        val outcome = plugin.reprocessExistingWavClip(path)
        refreshAudioClips()
        audioClipStatusMessage = buildString {
            append("Replay clip result: status=${outcome.status}, strategy=${outcome.strategy}")
            if (!outcome.reason.isNullOrBlank()) {
                append(", reason=${outcome.reason}")
            }
            if (outcome.modelStatus.isNotBlank()) {
                append(", detail=${outcome.modelStatus}")
            }
        }
    }

    fun updateContextInsight(summary: String, actions: List<String>, eventCount: Int, status: String) {
        contextInsightSummary = summary
        _contextInsightActions.clear()
        _contextInsightActions.addAll(actions.distinct().take(6))
        contextInsightStatusMessage = "$status | based on $eventCount context events"
    }

    fun updateAssistantBrief(briefText: String, highlights: List<String>, eventCount: Int, status: String) {
        assistantBriefText = briefText
        _assistantHighlights.clear()
        _assistantHighlights.addAll(highlights.distinct().take(8))
        assistantBriefStatusMessage = "$status | based on $eventCount context events"
    }

    fun enqueueAction(planId: String, step: ActionStepPayload): Long {
        val id = queueStore.enqueue(planId = planId, step = step)
        refreshQueue()
        appendHistory(
            eventType = "QUEUE_ADD",
            planId = planId,
            stepId = step.stepId,
            summary = "Action queued",
            detail = "${step.connector}.${step.operation} #$id",
        )
        return id
    }

    fun retryQueueItem(id: Long) {
        queueStore.retryNow(id)
        refreshQueue()
        appendHistory(
            eventType = "QUEUE_RETRY",
            planId = null,
            stepId = null,
            summary = "Manual retry",
            detail = "queueId=$id",
        )
    }

    fun clearSucceededQueueItems() {
        val removed = queueStore.clearFinished()
        refreshQueue()
        appendHistory(
            eventType = "QUEUE_CLEAR",
            planId = null,
            stepId = null,
            summary = "Cleared succeeded queue items",
            detail = "removed=$removed",
        )
    }

    fun triggerQueueExecutionNow() {
        ActionExecutionScheduler.enqueueImmediate(appContext)
        refreshQueue()
        queueStatusMessage = "Queue run requested"
    }

    private fun appendHistory(
        eventType: String,
        planId: String?,
        stepId: String?,
        summary: String,
        detail: String?,
    ) {
        historyStore.insert(
            eventType = eventType,
            planId = planId,
            stepId = stepId,
            summary = summary,
            detail = detail,
        )
        refreshActionHistory()
    }

    private fun connectorTitle(id: String): String {
        return when (id.lowercase()) {
            "gmail" -> "Gmail"
            "slack" -> "Slack"
            "github" -> "GitHub"
            else -> id
        }
    }

    private fun buildRealtimeStitchedTranscripts(
        records: List<AudioClipRecord>,
    ): Map<String, String> {
        if (records.isEmpty()) return emptyMap()

        val output = mutableMapOf<String, String>()
        var buffer = ""
        var lastAt = 0L

        records.sortedBy { it.capturedAtMs }.forEach { record ->
            val key = record.wav.absolutePath
            val isRecognized = record.status.lowercase(Locale.US).startsWith("recognized")
            val base = record.transcript.trim().replace(Regex("\\s+"), " ")

            if (!isRecognized || base.isBlank()) {
                output[key] = record.stitchedTranscript
                if (!isRecognized) {
                    buffer = ""
                    lastAt = 0L
                }
                return@forEach
            }

            val gapMs = if (lastAt > 0L) record.capturedAtMs - lastAt else Long.MAX_VALUE
            val shouldReset =
                buffer.isBlank() ||
                    gapMs > UI_STITCH_GAP_RESET_MS ||
                    endsWithSentencePunctuation(buffer)

            buffer = if (shouldReset) {
                base
            } else {
                mergeTranscriptSegments(buffer, base)
            }
            if (buffer.length > UI_MAX_STITCH_CHARS) {
                buffer = buffer.takeLast(UI_MAX_STITCH_CHARS).trim()
            }
            lastAt = record.capturedAtMs
            output[key] = buffer
        }
        return output
    }

    private fun mergeTranscriptSegments(previous: String, next: String): String {
        if (next.isBlank()) return previous
        if (previous.isBlank()) return next

        val prev = previous.trim()
        val nxt = next.trim()
        val prevLower = prev.lowercase(Locale.US)
        val nextLower = nxt.lowercase(Locale.US)

        if (prevLower == nextLower) return prev
        if (nextLower.startsWith(prevLower)) return nxt
        if (prevLower.endsWith(nextLower)) return prev

        val maxOverlap = minOf(prev.length, nxt.length, 96)
        for (size in maxOverlap downTo 8) {
            val left = prevLower.takeLast(size)
            val right = nextLower.take(size)
            if (left == right) {
                return (prev + nxt.drop(size)).trim()
            }
        }
        return "$prev $nxt".replace(Regex("\\s+"), " ").trim()
    }

    private fun endsWithSentencePunctuation(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return false
        return when (trimmed.last()) {
            '.', '!', '?', '。', '！', '？' -> true
            else -> false
        }
    }

    private fun estimateWavDurationMs(fileSizeBytes: Long): Long {
        if (fileSizeBytes <= 44L) return 0L
        val pcmBytes = fileSizeBytes - 44L
        return (pcmBytes * 1000L) / (16_000L * 2L)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000L
        val millisPart = (durationMs % 1000L) / 100L
        return "${seconds}.${millisPart}s"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        return if (kb < 1024.0) {
            String.format(Locale.US, "%.1f KB", kb)
        } else {
            String.format(Locale.US, "%.2f MB", kb / 1024.0)
        }
    }

    private fun formatTime(epochMs: Long): String {
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        return formatter.format(Date(epochMs))
    }
}

@Composable
fun rememberPermissionCommandCenterState(context: Context): PermissionCommandCenterState {
    val appContext = context.applicationContext
    val defaults = remember { ExtremeDefaults.plugins() }
    return remember(appContext) {
        PermissionCommandCenterState(appContext = appContext, descriptors = defaults)
    }
}

private fun PermissionDescriptor.toUiState(
    pluginId: String,
    granted: Boolean,
): PermissionUiState {
    return PermissionUiState(
        id = id,
        pluginId = pluginId,
        title = title,
        androidPermission = androidPermission,
        gate = gate,
        purpose = purpose,
        ttlHours = ttlHoursDefault,
        granted = granted,
    )
}
