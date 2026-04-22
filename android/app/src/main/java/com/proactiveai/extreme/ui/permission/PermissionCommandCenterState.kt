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
import com.proactiveai.extreme.assistant.AssistantQuickAction
import com.proactiveai.extreme.assistant.AssistantQuickActionPlanner
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.IntentHint
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.context.plugins.AudioAmbientPlugin
import com.proactiveai.extreme.core.edge.EdgeInferenceResult
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.core.edge.ModelDownloadPlan
import com.proactiveai.extreme.core.edge.RemoteModelDownloader
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
import com.proactiveai.extreme.storage.StoredContextEvent
import com.proactiveai.extreme.service.CloudSpeechTranscriptionRefiner
import com.proactiveai.extreme.sync.ActionExecutionScheduler
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val UI_STITCH_GAP_RESET_MS = 9_000L
private const val UI_MAX_STITCH_CHARS = 1_400
private const val MANUAL_INTAKE_TOTAL_TIMEOUT_MS = 120_000L
private const val MANUAL_INTAKE_CLOUD_REFINE_TIMEOUT_MS = 45_000L
private const val ASSISTANT_SESSION_WINDOW_MS = 15 * 60 * 1000L
private const val ASSISTANT_SPEECH_MAX_CHARS = 12_000
private const val ASSISTANT_NO_SPEECH_TEXT = "No speech transcript in this session"

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
    val detail: String?,
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
    val refinedTranscript: String,
    val refinedStatus: String,
    val reason: String,
    val modelStatus: String,
)

data class ManualSpeechIntakeResult(
    val status: String,
    val transcript: String,
    val cloudRefined: Boolean,
    val wavPath: String?,
    val detail: String,
)

data class EngagedSessionUiState(
    val id: Long,
    val sessionLabel: String,
    val durationLabel: String,
    val transcript: String,
    val locationLabel: String,
    val indoorOutdoor: String,
    val synced: Boolean,
)

data class EngagedSessionPersistResult(
    val status: String,
    val transcript: String,
    val detail: String,
)

data class AssistantSessionRowUiState(
    val sessionId: String,
    val sessionLabel: String,
    val eventCount: Int,
    val sparklingSession: Boolean,
    val sparklingSignalCount: Int,
    val sparklingTriggers: String,
    val speechSummary: String,
    val positionSummary: String,
    val indoorOutdoor: String,
    val locationLabel: String,
    val calendarSummary: String,
    val guessedUserScenario: String,
    val actionPlan: String,
    val quickActions: List<AssistantQuickAction>,
    val modelLabel: String,
)

private data class AssistantSessionSortableRow(
    val row: AssistantSessionRowUiState,
    val sessionStartMs: Long,
    val updatedAtMs: Long,
)

private data class SessionSpeechSignal(
    val occurredAt: Long,
    val clipKey: String,
    val text: String,
    val priority: Int,
)

private data class AudioClipRecord(
    val wav: File,
    val capturedAtMs: Long,
    val durationMs: Long,
    val status: String,
    val strategy: String,
    val transcript: String,
    val stitchedTranscript: String,
    val refinedTranscript: String,
    val refinedStatus: String,
    val reason: String,
    val modelStatus: String,
)

private data class ManualSystemSnapshot(
    val lightLux: Double?,
    val ambientState: String,
    val activityState: String,
    val wifi: Boolean?,
    val cellular: Boolean?,
    val internet: Boolean?,
    val bluetoothEnabled: Boolean?,
    val wifiSsid: String?,
    val locationLabel: String,
    val latitude: Double?,
    val longitude: Double?,
    val motionState: String,
)

private data class ManualClipSnapshot(
    val occurredAt: Long,
    val durationMs: Long,
    val clipRmsDb: Double,
    val clipPeakDb: Double,
    val metaPath: String?,
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

    private val _engagedSessions = mutableStateListOf<EngagedSessionUiState>()
    val engagedSessions: List<EngagedSessionUiState> get() = _engagedSessions

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

    var globalLockEnabled by mutableStateOf(AppPrefs.isGlobalLockEnabled(appContext))
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

    var openAiApiKey by mutableStateOf(AppPrefs.getOpenAiApiKey(appContext))
        private set

    var mobileApiBaseUrl by mutableStateOf(AppPrefs.getMobileApiBaseUrl(appContext))
        private set

    var deviceId by mutableStateOf(AppPrefs.getDeviceId(appContext))
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

    var engagedSessionStatusMessage by mutableStateOf("No engaged sessions yet")
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

    var assistantSessionStatusMessage by mutableStateOf("No 15-minute session analysis yet")
        private set

    private val _assistantSessionRows = mutableStateListOf<AssistantSessionRowUiState>()
    val assistantSessionRows: List<AssistantSessionRowUiState> get() = _assistantSessionRows

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
        globalLockEnabled = AppPrefs.isGlobalLockEnabled(appContext)
        unsyncedEvents = ContextEventStore.getInstance(appContext).countUnsyncedMobileItems()
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
        openAiApiKey = AppPrefs.getOpenAiApiKey(appContext)
        mobileApiBaseUrl = AppPrefs.getMobileApiBaseUrl(appContext)
        deviceId = AppPrefs.getDeviceId(appContext)
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

    fun persistGlobalLockEnabled(enabled: Boolean) {
        AppPrefs.setGlobalLockEnabled(appContext, enabled)
        globalLockEnabled = enabled
        if (enabled) {
            connectorStatusMessage = "Global lock enabled: connector and outbound calls are blocked."
            queueStatusMessage = "Global lock enabled: queued execution paused."
            lastPlanPreview = "Global lock ON: local/cloud inference and outbound actions are blocked."
        } else {
            lastPlanPreview = "Global lock OFF: inference and outbound actions resumed."
        }
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

    fun persistOpenAiApiKey(apiKey: String) {
        AppPrefs.setOpenAiApiKey(appContext, apiKey)
        openAiApiKey = apiKey
    }

    fun persistMobileSyncConfig(baseUrl: String) {
        AppPrefs.setMobileApiBaseUrl(appContext, baseUrl)
        mobileApiBaseUrl = baseUrl
        deviceId = AppPrefs.getDeviceId(appContext)
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

    suspend fun downloadAndActivateModel(
        profile: EdgeModelProfile,
        downloadUrl: String,
        huggingFaceToken: String,
    ) {
        val normalizedUrl = ModelDownloadPlan.normalizeUrl(profile, downloadUrl)
        val normalizedToken = huggingFaceToken.trim()
        val profileLabel = ModelDownloadPlan.shortLabelFor(profile)
        persistHuggingFaceToken(normalizedToken)
        modelDownloadProgress = 0
        modelDownloadStatus = "Downloading $profileLabel LiteRT-LM model..."

        val result = RemoteModelDownloader.downloadToAppStorage(
            context = appContext,
            url = normalizedUrl,
            huggingFaceToken = normalizedToken.ifBlank { null },
            preferredFileName = ModelDownloadPlan.preferredFileName(profile, normalizedUrl),
            onProgress = { progress ->
                modelDownloadProgress = progress.percent
                modelDownloadStatus = if (progress.totalBytes > 0) {
                    "Downloading $profileLabel LiteRT-LM model... ${progress.percent}%"
                } else {
                    "Downloading $profileLabel LiteRT-LM model..."
                }
            },
        )

        result.fold(
            onSuccess = { path ->
                persistLocalModelConfig(
                    enabled = true,
                    modelPath2B = if (profile == EdgeModelProfile.GEMMA_EFFECTIVE_2B) path else localModelPath2B,
                    modelPath4B = if (profile == EdgeModelProfile.GEMMA_EFFECTIVE_4B) path else localModelPath4B,
                    backendId = LocalModelBackend.LITERT_LM.id,
                    ggufPath2B = localGgufPath2B,
                    ggufPath4B = localGgufPath4B,
                    llamaContextSize = localLlamaContextSize,
                    llamaThreads = localLlamaThreads,
                )
                setEdgeModel(profile.id)
                modelDownloadProgress = 100
                modelDownloadStatus = "$profileLabel LiteRT-LM model ready: $path"
                latestNativeModelStatus = "$profileLabel LiteRT-LM downloaded. Run Inference Test."
                latestNativeModelOutput = ""
            },
            onFailure = { error ->
                modelDownloadProgress = -1
                modelDownloadStatus = "$profileLabel LiteRT-LM download failed: ${RemoteModelDownloader.describeFailure(error)}"
            },
        )
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

    fun updateQueueStatusMessage(text: String) {
        queueStatusMessage = text
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
        val rows = queueStore.recent(limit = 20)
        _executionQueue.clear()
        _executionQueue.addAll(
            rows.map { item ->
                ExecutionQueueUiState(
                    id = item.id,
                    summary = formatQueueSummary(item.connector, item.operation, item.stepId, item.argsJson),
                    detail = formatQueueDetail(item.connector, item.operation, item.argsJson),
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

    fun refreshEngagedSessions(limit: Int = 120) {
        val rows = ContextEventStore.getInstance(appContext)
            .getRecent(limit = 1200)
            .filter { row ->
                row.category.equals("engaged_session", ignoreCase = true) ||
                    row.source.equals("assistant_engaged_session", ignoreCase = true)
            }
            .take(limit)

        _engagedSessions.clear()
        _engagedSessions.addAll(
            rows.map { row ->
                val payload = safePayloadMap(row.payloadJson)
                val startedAt = valueAsLong(payload["sessionStartedAt"]) ?: row.occurredAt
                val endedAt = valueAsLong(payload["sessionEndedAt"]) ?: row.occurredAt
                val durationMs = valueAsLong(payload["durationMs"]) ?: (endedAt - startedAt).coerceAtLeast(0L)
                val transcript = valueAsString(payload["transcript"]).orEmpty().ifBlank {
                    row.summary.substringAfter(":", "").trim()
                }
                val locationLabel = valueAsString(payload["locationLabel"]).orEmpty().ifBlank { "Unknown location" }
                val indoorOutdoor = valueAsString(payload["indoorOutdoor"]).orEmpty().ifBlank { "Unknown" }

                EngagedSessionUiState(
                    id = row.id,
                    sessionLabel = "${formatTime(startedAt)} → ${formatTime(endedAt)}",
                    durationLabel = formatDuration(durationMs),
                    transcript = transcript,
                    locationLabel = locationLabel,
                    indoorOutdoor = indoorOutdoor,
                    synced = row.synced,
                )
            }
        )

        engagedSessionStatusMessage = if (rows.isEmpty()) {
            "No engaged sessions yet. Tap Engage in Assistant to start one."
        } else {
            "Showing latest ${rows.size} engaged sessions (newest first)"
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
            val refinedTranscript = meta["openAiRefinedTranscript"]?.toString().orEmpty()
            val refinedStatus = meta["openAiRefinedStatus"]?.toString().orEmpty()
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
                refinedTranscript = refinedTranscript,
                refinedStatus = refinedStatus,
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
                    refinedTranscript = record.refinedTranscript,
                    refinedStatus = record.refinedStatus,
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

    suspend fun runCloudTranscribeSingleAudioClip(path: String) {
        val outcome = CloudSpeechTranscriptionRefiner.transcribeSingleClip(
            context = appContext,
            wavPath = path,
        )
        refreshAudioClips()
        refreshContextTimeline()
        refreshAssistantSessions()
        audioClipStatusMessage = buildString {
            append("GPT transcribe result: status=${outcome.status}")
            if (outcome.transcript.isNotBlank()) {
                append(", transcript=\"${outcome.transcript.take(120)}\"")
            }
            if (outcome.detail.isNotBlank()) {
                append(", detail=${outcome.detail}")
            }
        }
    }

    fun persistEngagedSession(
        sessionStartedAt: Long,
        sessionEndedAt: Long,
        transcript: String,
        localeTag: String,
        segmentCount: Int,
        wavPath: String? = null,
        strategy: String = "engaged_gpt4o_transcribe",
    ): EngagedSessionPersistResult {
        if (AppPrefs.isGlobalLockEnabled(appContext)) {
            return EngagedSessionPersistResult(
                status = "blocked",
                transcript = "",
                detail = "Global lock enabled: engaged session recording is blocked.",
            )
        }

        val cleaned = normalizeEngagedTranscript(transcript)
        if (cleaned.isBlank() || isNoSpeechText(cleaned)) {
            return EngagedSessionPersistResult(
                status = "no_speech",
                transcript = "",
                detail = "Engaged session stopped: no valid speech transcript captured.",
            )
        }

        val startedAt = sessionStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val endedAt = sessionEndedAt.takeIf { it > startedAt } ?: System.currentTimeMillis()
        val durationMs = (endedAt - startedAt).coerceAtLeast(0L)
        val snapshot = buildManualSystemSnapshot()
        val indoorOutdoor = inferIndoorOutdoor(
            wifi = snapshot.wifi,
            cellular = snapshot.cellular,
        )
        val stitched = cleaned.take(UI_MAX_STITCH_CHARS)

        ContextEventStore.getInstance(appContext).insert(
            ContextEvent(
                eventId = java.util.UUID.randomUUID().toString(),
                occurredAt = startedAt,
                source = "assistant_engaged_session",
                category = "engaged_session",
                summary = "Engaged session transcript: $stitched",
                payload = buildMap<String, Any> {
                    put("status", "recognized")
                    put("engagedSession", true)
                    put("transcript", cleaned)
                    put("stitchedTranscript", stitched)
                    put("segmentCount", segmentCount.coerceAtLeast(1))
                    put("sessionStartedAt", startedAt)
                    put("sessionEndedAt", endedAt)
                    put("durationMs", durationMs)
                    put("locale", localeTag.ifBlank { Locale.getDefault().toLanguageTag() })
                    put("strategy", strategy)
                    put(
                        "modelStatus",
                        if (strategy.contains("gpt-4o-transcribe", ignoreCase = true)) {
                            "cloud_refined"
                        } else {
                            "on_device_or_system"
                        }
                    )
                    if (!wavPath.isNullOrBlank()) put("wavPath", wavPath)
                    put("lightLux", snapshot.lightLux ?: -1.0)
                    put("ambientState", snapshot.ambientState)
                    put("activityState", snapshot.activityState)
                    if (snapshot.wifi != null) put("wifi", snapshot.wifi)
                    if (snapshot.cellular != null) put("cellular", snapshot.cellular)
                    if (snapshot.internet != null) put("internet", snapshot.internet)
                    if (snapshot.bluetoothEnabled != null) put("bluetoothEnabled", snapshot.bluetoothEnabled)
                    if (!snapshot.wifiSsid.isNullOrBlank()) put("wifiSsid", snapshot.wifiSsid)
                    put("indoorOutdoor", indoorOutdoor)
                    put("locationLabel", snapshot.locationLabel)
                    if (snapshot.latitude != null) put("latitude", snapshot.latitude)
                    if (snapshot.longitude != null) put("longitude", snapshot.longitude)
                    put("motionState", snapshot.motionState)
                    put("transcriptionEventAt", System.currentTimeMillis())
                },
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 14 * 24 * 3600,
            )
        )

        return EngagedSessionPersistResult(
            status = "recognized",
            transcript = cleaned,
            detail = buildString {
                append("Engaged session saved")
                append(" | duration=${formatDuration(durationMs)}")
                append(" | location=${snapshot.locationLabel}")
                append(" | indoor=${indoorOutdoor}")
            },
        )
    }

    suspend fun captureManualSpeechIntakeContext(
        shouldStop: (() -> Boolean)? = null,
    ): ManualSpeechIntakeResult {
        if (AppPrefs.isGlobalLockEnabled(appContext)) {
            return ManualSpeechIntakeResult(
                status = "blocked",
                transcript = "",
                cloudRefined = false,
                wavPath = null,
                detail = "Global lock enabled: manual speech intake blocked.",
            )
        }

        val plugin = audioReplayPlugin
        if (plugin == null) {
            return ManualSpeechIntakeResult(
                status = "error",
                transcript = "",
                cloudRefined = false,
                wavPath = null,
                detail = "Manual intake unavailable: audio_ambient descriptor missing.",
            )
        }

        val manualHoldMode = shouldStop != null
        val intake = withTimeoutOrNull(MANUAL_INTAKE_TOTAL_TIMEOUT_MS) {
            plugin.captureManualIntakeOnce(
                shouldStop = shouldStop,
                deferCloudTranscription = manualHoldMode,
            )
        } ?: return ManualSpeechIntakeResult(
            status = "timeout",
            transcript = "",
            cloudRefined = false,
            wavPath = null,
            detail = "Manual intake timed out after ${MANUAL_INTAKE_TOTAL_TIMEOUT_MS / 1000}s. Please try again.",
        )
        val wavPath = intake.wavPath

        if (manualHoldMode && intake.status == "captured_untranscribed" && !wavPath.isNullOrBlank()) {
            val clip = readManualClipSnapshot(wavPath)
            return ManualSpeechIntakeResult(
                status = "captured",
                transcript = "",
                cloudRefined = false,
                wavPath = wavPath,
                detail = buildString {
                    append("WAV captured (${clip.durationMs}ms). ")
                    append("Ready for async cloud transcription.")
                },
            )
        }

        if (intake.status != "recognized") {
            return ManualSpeechIntakeResult(
                status = intake.status,
                transcript = intake.transcript.orEmpty(),
                cloudRefined = false,
                wavPath = wavPath,
                detail = buildString {
                    append("Manual intake status=${intake.status}, strategy=${intake.strategy}")
                    if (!intake.reason.isNullOrBlank()) append(", reason=${intake.reason}")
                    if (intake.modelStatus.isNotBlank()) append(", detail=${intake.modelStatus}")
                },
            )
        }

        val intakeAlreadyCloud = intake.strategy.contains("gpt-4o-transcribe", ignoreCase = true) ||
            intake.strategy.contains("cloud", ignoreCase = true)
        var finalTranscript = intake.transcript.orEmpty().trim()
        var cloudRefined = false
        var cloudDetail = ""
        if (!wavPath.isNullOrBlank() && !manualHoldMode && !intakeAlreadyCloud) {
            val cloud = withTimeoutOrNull(MANUAL_INTAKE_CLOUD_REFINE_TIMEOUT_MS) {
                CloudSpeechTranscriptionRefiner.transcribeSingleClip(
                    context = appContext,
                    wavPath = wavPath,
                )
            }
            if (cloud == null) {
                cloudDetail = "cloud_refine_timeout_${MANUAL_INTAKE_CLOUD_REFINE_TIMEOUT_MS / 1000}s"
            } else {
                cloudDetail = cloud.detail
                if (cloud.status == "recognized" && cloud.transcript.isNotBlank()) {
                    finalTranscript = cloud.transcript.trim()
                    cloudRefined = true
                }
            }
        } else if (manualHoldMode || intakeAlreadyCloud) {
            cloudDetail = "cloud_refine_not_needed_or_already_applied"
        }

        if (finalTranscript.isBlank() || isNoSpeechText(finalTranscript)) {
            return ManualSpeechIntakeResult(
                status = "no_speech",
                transcript = "",
                cloudRefined = cloudRefined,
                wavPath = wavPath,
                detail = "Manual intake detected no clear speech.${if (cloudDetail.isNotBlank()) " $cloudDetail" else ""}",
            )
        }

        val snapshot = buildManualSystemSnapshot()
        val transcriptionEventAt = System.currentTimeMillis()
        val clipSnapshot = readManualClipSnapshot(wavPath.orEmpty())
        val stitched = finalTranscript.take(UI_MAX_STITCH_CHARS)
        val locationLabel = snapshot.locationLabel
        val indoorOutdoor = inferIndoorOutdoor(
            wifi = snapshot.wifi,
            cellular = snapshot.cellular,
        )
        val strategy = if (cloudRefined) "manual_intake_gpt4o_transcribe" else intake.strategy
        val modelStatus = if (cloudRefined) "cloud_refined" else intake.modelStatus

        ContextEventStore.getInstance(appContext).insert(
            ContextEvent(
                eventId = java.util.UUID.randomUUID().toString(),
                occurredAt = clipSnapshot.occurredAt,
                source = "assistant_manual_audio_intake",
                category = "audio",
                summary = "Manual intake speech: $stitched",
                payload = buildMap<String, Any> {
                    put("status", "recognized")
                    put("manualIntake", true)
                    put("transcript", finalTranscript)
                    put("stitchedTranscript", stitched)
                    put("strategy", strategy)
                    put("modelStatus", modelStatus)
                    put("refinedByCloud", cloudRefined)
                    put("wavPath", wavPath.orEmpty())
                    put("metaPath", clipSnapshot.metaPath.orEmpty().ifBlank { intake.metaPath.orEmpty() })
                    put("durationMs", if (clipSnapshot.durationMs > 0L) clipSnapshot.durationMs else (intake.durationMs ?: -1L))
                    put("clipRmsDb", clipSnapshot.clipRmsDb.takeIf { it > -120.0 } ?: (intake.clipRmsDb?.toDouble() ?: -120.0))
                    put("clipPeakDb", clipSnapshot.clipPeakDb.takeIf { it > -120.0 } ?: (intake.clipPeakDb?.toDouble() ?: -120.0))
                    put("clipOccurredAt", clipSnapshot.occurredAt)
                    put("transcriptionEventAt", transcriptionEventAt)
                    put("lightLux", snapshot.lightLux ?: -1.0)
                    put("ambientState", snapshot.ambientState)
                    put("activityState", snapshot.activityState)
                    if (snapshot.wifi != null) put("wifi", snapshot.wifi)
                    if (snapshot.cellular != null) put("cellular", snapshot.cellular)
                    if (snapshot.internet != null) put("internet", snapshot.internet)
                    if (snapshot.bluetoothEnabled != null) put("bluetoothEnabled", snapshot.bluetoothEnabled)
                    if (!snapshot.wifiSsid.isNullOrBlank()) put("wifiSsid", snapshot.wifiSsid)
                    put("indoorOutdoor", indoorOutdoor)
                    put("locationLabel", locationLabel)
                    if (snapshot.latitude != null) put("latitude", snapshot.latitude)
                    if (snapshot.longitude != null) put("longitude", snapshot.longitude)
                    put("motionState", snapshot.motionState)
                },
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        return ManualSpeechIntakeResult(
            status = "recognized",
            transcript = finalTranscript,
            cloudRefined = cloudRefined,
            wavPath = wavPath,
            detail = buildString {
                append("Manual intake inserted with system snapshot | strategy=$strategy")
                append(", indoor=$indoorOutdoor")
                append(", location=$locationLabel")
                if (cloudDetail.isNotBlank()) append(", cloud=$cloudDetail")
            },
        )
    }

    suspend fun transcribeManualSpeechIntakeContextFromWav(wavPath: String): ManualSpeechIntakeResult {
        if (AppPrefs.isGlobalLockEnabled(appContext)) {
            return ManualSpeechIntakeResult(
                status = "blocked",
                transcript = "",
                cloudRefined = false,
                wavPath = wavPath,
                detail = "Global lock enabled: manual speech cloud transcription blocked.",
            )
        }

        val clip = readManualClipSnapshot(wavPath)
        val cloud = withTimeoutOrNull(MANUAL_INTAKE_CLOUD_REFINE_TIMEOUT_MS) {
            CloudSpeechTranscriptionRefiner.transcribeSingleClip(
                context = appContext,
                wavPath = wavPath,
                manualTrigger = true,
            )
        } ?: return ManualSpeechIntakeResult(
            status = "timeout",
            transcript = "",
            cloudRefined = false,
            wavPath = wavPath,
            detail = "Cloud transcription timeout after ${MANUAL_INTAKE_CLOUD_REFINE_TIMEOUT_MS / 1000}s.",
        )

        val transcript = cloud.transcript.trim()
        if (cloud.status != "recognized" || transcript.isBlank() || isNoSpeechText(transcript)) {
            return ManualSpeechIntakeResult(
                status = if (cloud.status == "recognized") "no_speech" else cloud.status,
                transcript = "",
                cloudRefined = false,
                wavPath = wavPath,
                detail = buildString {
                    append("Manual intake cloud transcription ${cloud.status}")
                    if (cloud.detail.isNotBlank()) append(": ${cloud.detail}")
                },
            )
        }

        val snapshot = buildManualSystemSnapshot()
        val stitched = transcript.take(UI_MAX_STITCH_CHARS)
        val locationLabel = snapshot.locationLabel
        val indoorOutdoor = inferIndoorOutdoor(
            wifi = snapshot.wifi,
            cellular = snapshot.cellular,
        )
        val transcriptionEventAt = System.currentTimeMillis()

        ContextEventStore.getInstance(appContext).insert(
            ContextEvent(
                eventId = java.util.UUID.randomUUID().toString(),
                occurredAt = clip.occurredAt,
                source = "assistant_manual_audio_intake",
                category = "audio",
                summary = "Manual intake speech: $stitched",
                payload = buildMap<String, Any> {
                    put("status", "recognized")
                    put("manualIntake", true)
                    put("transcript", transcript)
                    put("stitchedTranscript", stitched)
                    put("strategy", "manual_intake_gpt4o_transcribe")
                    put("modelStatus", "cloud_refined")
                    put("refinedByCloud", true)
                    put("wavPath", wavPath)
                    put("metaPath", clip.metaPath.orEmpty())
                    put("durationMs", clip.durationMs)
                    put("clipRmsDb", clip.clipRmsDb)
                    put("clipPeakDb", clip.clipPeakDb)
                    put("clipOccurredAt", clip.occurredAt)
                    put("transcriptionEventAt", transcriptionEventAt)
                    put("lightLux", snapshot.lightLux ?: -1.0)
                    put("ambientState", snapshot.ambientState)
                    put("activityState", snapshot.activityState)
                    if (snapshot.wifi != null) put("wifi", snapshot.wifi)
                    if (snapshot.cellular != null) put("cellular", snapshot.cellular)
                    if (snapshot.internet != null) put("internet", snapshot.internet)
                    if (snapshot.bluetoothEnabled != null) put("bluetoothEnabled", snapshot.bluetoothEnabled)
                    if (!snapshot.wifiSsid.isNullOrBlank()) put("wifiSsid", snapshot.wifiSsid)
                    put("indoorOutdoor", indoorOutdoor)
                    put("locationLabel", locationLabel)
                    if (snapshot.latitude != null) put("latitude", snapshot.latitude)
                    if (snapshot.longitude != null) put("longitude", snapshot.longitude)
                    put("motionState", snapshot.motionState)
                },
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        return ManualSpeechIntakeResult(
            status = "recognized",
            transcript = transcript,
            cloudRefined = true,
            wavPath = wavPath,
            detail = buildString {
                append("Manual intake transcribed and inserted | strategy=manual_intake_gpt4o_transcribe")
                append(", indoor=$indoorOutdoor")
                append(", location=$locationLabel")
            },
        )
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

    fun updateAssistantSessions(rows: List<AssistantSessionRowUiState>, status: String) {
        _assistantSessionRows.clear()
        _assistantSessionRows.addAll(rows)
        assistantSessionStatusMessage = if (rows.isEmpty()) {
            "$status | no 15-minute sessions available"
        } else {
            "$status | sessions=${rows.size}"
        }
    }

    fun refreshAssistantSessions(limit: Int = 24) {
        val store = ContextEventStore.getInstance(appContext)
        val recentRows = store.getRecent(limit = 2200)
        val sessionSpeechByStart = buildAssistantSessionSpeechMap(
            recentRows = recentRows,
            maxSegments = Int.MAX_VALUE,
        )

        val rows = recentRows
            .filter { it.category == "assistant_session" }
            .mapNotNull { row ->
                val payload = kotlin.runCatching { JSONObject(row.payloadJson).toMap() }.getOrNull().orEmpty()
                val sessionId = payload["sessionId"]?.toString().orEmpty().ifBlank {
                    "session_${payload["sessionStartMs"]?.toString().orEmpty()}"
                }
                val sessionLabel = payload["sessionLabel"]?.toString().orEmpty()
                if (sessionLabel.isBlank()) return@mapNotNull null
                val sessionStartMs = payload["sessionStartMs"]?.toString()?.toLongOrNull()
                    ?: sessionId.removePrefix("session_").toLongOrNull()
                    ?: 0L
                val persistedSpeech = payload["speechFull"]?.toString().orEmpty()
                    .ifBlank { payload["speechSummary"]?.toString().orEmpty() }
                val rebuiltSpeech = sessionSpeechByStart[sessionStartMs].orEmpty()
                val speechSummary = when {
                    rebuiltSpeech.isNotBlank() && !isNoSpeechText(rebuiltSpeech) -> rebuiltSpeech
                    persistedSpeech.isNotBlank() -> persistedSpeech
                    else -> ASSISTANT_NO_SPEECH_TEXT
                }
                val sparklingSignalCount = valueAsInt(payload["sparklingSignalCount"]) ?: 0
                val sparklingTriggerValues = valueAsStringList(payload["sparklingTriggers"])
                val sparklingSession = valueAsBoolean(payload["sparklingSession"]) == true ||
                    valueAsBoolean(payload["sparkling"]) == true ||
                    sparklingSignalCount > 0 ||
                    sparklingTriggerValues.isNotEmpty()
                val sparklingTriggers = sparklingTriggerValues
                    .take(4)
                    .joinToString(separator = ", ")
                    .ifBlank { if (sparklingSession) "manual marker" else "-" }
                val positionSummary = payload["positionSummary"]?.toString().orEmpty()
                val indoorOutdoor = payload["indoorOutdoor"]?.toString().orEmpty()
                val locationLabel = payload["locationLabel"]?.toString().orEmpty()
                val calendarSummary = payload["calendarSummary"]?.toString().orEmpty()
                val guessedUserScenario = payload["guessedUserScenario"]?.toString()
                    .orEmpty()
                    .ifBlank { payload["suggestion"]?.toString().orEmpty() }
                val actionPlan = payload["actionPlan"]?.toString().orEmpty()
                val quickActions = AssistantQuickActionPlanner.inferQuickActions(
                    speechSummary = speechSummary,
                    guessedUserScenario = guessedUserScenario,
                    actionPlan = actionPlan,
                    locationLabel = locationLabel,
                    calendarSummary = calendarSummary,
                )

                AssistantSessionSortableRow(
                    row = AssistantSessionRowUiState(
                        sessionId = sessionId,
                        sessionLabel = sessionLabel,
                        eventCount = payload["eventCount"].toString().toIntOrNull() ?: 0,
                        sparklingSession = sparklingSession,
                        sparklingSignalCount = sparklingSignalCount,
                        sparklingTriggers = sparklingTriggers,
                        speechSummary = speechSummary,
                        positionSummary = positionSummary,
                        indoorOutdoor = indoorOutdoor,
                        locationLabel = locationLabel,
                        calendarSummary = calendarSummary,
                        guessedUserScenario = guessedUserScenario,
                        actionPlan = actionPlan,
                        quickActions = quickActions,
                        modelLabel = payload["modelLabel"]?.toString().orEmpty(),
                    ),
                    sessionStartMs = sessionStartMs,
                    updatedAtMs = row.occurredAt,
                )
            }
            .distinctBy { it.row.sessionId }
            .sortedWith(
                compareByDescending<AssistantSessionSortableRow> { it.sessionStartMs }
                    .thenByDescending { it.updatedAtMs }
            )
            .map { it.row }
            .take(limit)

        _assistantSessionRows.clear()
        _assistantSessionRows.addAll(rows)
        assistantSessionStatusMessage = if (rows.isEmpty()) {
            "No 15-minute session analysis yet"
        } else {
            "Showing latest ${rows.size} 15-minute assistant sessions"
        }
    }

    private fun buildAssistantSessionSpeechMap(
        recentRows: List<StoredContextEvent>,
        maxSegments: Int,
    ): Map<Long, String> {
        val grouped = linkedMapOf<Long, MutableList<SessionSpeechSignal>>()
        recentRows.forEach { row ->
            val payload = safePayloadMap(row.payloadJson)
            val signal = extractAssistantSessionSpeechSignal(
                row = row,
                payload = payload,
            ) ?: return@forEach
            val sessionStart = (row.occurredAt / ASSISTANT_SESSION_WINDOW_MS) * ASSISTANT_SESSION_WINDOW_MS
            grouped.getOrPut(sessionStart) { mutableListOf() }.add(signal)
        }

        return grouped.mapValues { (_, signals) ->
            buildSpeechSummaryFromSessionSignals(
                signals = signals,
                maxSegments = maxSegments,
            )
        }
    }

    private fun extractAssistantSessionSpeechSignal(
        row: StoredContextEvent,
        payload: Map<String, Any?>,
    ): SessionSpeechSignal? {
        val sourceLower = row.source.lowercase(Locale.US)
        val categoryLower = row.category.lowercase(Locale.US)
        val isEngagedSession = categoryLower == "engaged_session" || sourceLower.contains("engaged_session")
        if (categoryLower != "audio" && !sourceLower.contains("audio") && !isEngagedSession) return null

        val status = valueAsString(payload["status"])?.lowercase(Locale.US).orEmpty()
        if (status == "no_speech" || status == "error") return null

        val stitched = valueAsString(payload["stitchedTranscript"])
        val transcript = valueAsString(payload["transcript"])
        val summaryTranscript = when {
            row.summary.startsWith("Ambient speech transcript", ignoreCase = true) ->
                row.summary.substringAfter(":", "").trim()
            row.summary.startsWith("Manual intake speech", ignoreCase = true) ->
                row.summary.substringAfter(":", "").trim()
            row.summary.startsWith("Engaged session transcript", ignoreCase = true) ->
                row.summary.substringAfter(":", "").trim()
            else -> ""
        }
        val pickedText = listOf(stitched, transcript, summaryTranscript)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
            .trim()
        if (pickedText.isBlank() || isNoSpeechText(pickedText)) return null

        val isCloudRefined = valueAsBoolean(payload["refinedByCloud"]) == true ||
            valueAsString(payload["modelStatus"])?.contains("cloud_refined", ignoreCase = true) == true ||
            valueAsString(payload["strategy"])?.contains("gpt-4o-transcribe", ignoreCase = true) == true ||
            sourceLower.contains("refiner")

        val clipKey = valueAsString(payload["wavPath"])
            ?: valueAsString(payload["sessionStartedAt"])?.let { "engaged:$it" }
            ?: valueAsString(payload["clipOccurredAt"])?.let { "clipAt:$it" }
            ?: "${row.occurredAt}:${pickedText.take(72).lowercase(Locale.US)}"

        return SessionSpeechSignal(
            occurredAt = row.occurredAt,
            clipKey = clipKey,
            text = pickedText,
            priority = if (isCloudRefined) 2 else 1,
        )
    }

    private fun buildSpeechSummaryFromSessionSignals(
        signals: List<SessionSpeechSignal>,
        maxSegments: Int,
    ): String {
        if (signals.isEmpty()) return ASSISTANT_NO_SPEECH_TEXT

        val bestByClip = linkedMapOf<String, SessionSpeechSignal>()
        signals
            .sortedWith(
                compareByDescending<SessionSpeechSignal> { it.occurredAt }
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
                compareByDescending<SessionSpeechSignal> { it.occurredAt }
                    .thenByDescending { it.priority }
            )
            .asSequence()
            .map { normalizeSpeechSnippet(it.text) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .toList()

        if (normalized.isEmpty()) return ASSISTANT_NO_SPEECH_TEXT

        val effectiveLimit = when {
            maxSegments <= 0 -> normalized.size
            else -> minOf(maxSegments, normalized.size)
        }
        val shown = normalized.take(effectiveLimit)
        val extra = normalized.size - shown.size
        val composed = if (extra > 0) {
            "${shown.joinToString(separator = " | ")} | (+$extra more speech clips)"
        } else {
            shown.joinToString(separator = " | ")
        }
        return composed.take(ASSISTANT_SPEECH_MAX_CHARS)
    }

    private fun normalizeSpeechSnippet(raw: String): String {
        return raw
            .trim()
            .replace("\u0000", "")
            .replace(Regex("\\s+"), " ")
    }

    private fun normalizeEngagedTranscript(raw: String): String {
        return raw
            .replace("\u0000", "")
            .replace(Regex("\\s+"), " ")
            .trim()
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
        if (AppPrefs.isGlobalLockEnabled(appContext)) {
            queueStatusMessage = "Global lock enabled: queue execution blocked."
            return
        }
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

    private fun formatQueueSummary(
        connector: String,
        operation: String,
        stepId: String,
        argsJson: String,
    ): String {
        if (connector.equals("focus", ignoreCase = true) && operation.equals("today_top3", ignoreCase = true)) {
            val args = kotlin.runCatching { JSONObject(argsJson) }.getOrNull()
            val rank = args?.optInt("rank", -1)?.takeIf { it > 0 } ?: 0
            val title = args?.optString("title").orEmpty().trim()
            val importance = args?.optInt("importance", -1) ?: -1
            val urgency = args?.optInt("urgency", -1) ?: -1
            val missRisk = args?.optInt("missRisk", -1) ?: -1
            val scoreLabel = if (importance >= 0 && urgency >= 0 && missRisk >= 0) {
                " | I/U/M ${importance}/${urgency}/${missRisk}"
            } else {
                ""
            }
            val prefix = if (rank > 0) "Focus #$rank" else "Focus"
            return if (title.isNotBlank()) {
                "$prefix: $title$scoreLabel"
            } else {
                "$prefix item$scoreLabel"
            }
        }
        return "${connector}.${operation} (${stepId.take(8)})"
    }

    private fun formatQueueDetail(
        connector: String,
        operation: String,
        argsJson: String,
    ): String? {
        if (!connector.equals("focus", ignoreCase = true) || !operation.equals("today_top3", ignoreCase = true)) {
            return null
        }
        val args = kotlin.runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        val evidence = args.optString("evidence").trim()
        val reason = args.optString("reason").trim()
        val action = args.optString("action").trim()
        val lines = buildList {
            if (evidence.isNotBlank()) add("Evidence: $evidence")
            if (reason.isNotBlank()) add("Why: $reason")
            if (action.isNotBlank()) add("Next: $action")
        }
        return lines.joinToString("\n").take(520).ifBlank { null }
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

    private fun buildManualSystemSnapshot(): ManualSystemSnapshot {
        val rows = ContextEventStore.getInstance(appContext).getRecent(320)

        var lightLux: Double? = null
        var ambientState = "unknown"
        var activityState = "unknown"
        var wifi: Boolean? = null
        var cellular: Boolean? = null
        var internet: Boolean? = null
        var bluetoothEnabled: Boolean? = null
        var wifiSsid: String? = null
        var locationLabel = "Unknown location"
        var latitude: Double? = null
        var longitude: Double? = null
        var motionState = "unknown"

        rows.forEach { row ->
            val category = row.category.lowercase(Locale.US)
            val payload = safePayloadMap(row.payloadJson)

            when (category) {
                "sensor" -> {
                    if (lightLux == null) lightLux = valueAsDouble(payload["lightLux"])
                    if (ambientState == "unknown") {
                        ambientState = valueAsString(payload["ambientState"]).orEmpty().ifBlank { ambientState }
                    }
                    if (activityState == "unknown") {
                        activityState = valueAsString(payload["activityState"]).orEmpty().ifBlank { activityState }
                    }
                }

                "connectivity" -> {
                    if (wifi == null) wifi = valueAsBoolean(payload["wifi"])
                    if (cellular == null) cellular = valueAsBoolean(payload["cellular"])
                    if (internet == null) internet = valueAsBoolean(payload["internet"])
                    if (bluetoothEnabled == null) bluetoothEnabled = valueAsBoolean(payload["bluetoothEnabled"])
                    if (wifiSsid.isNullOrBlank()) wifiSsid = valueAsString(payload["wifiSsid"])
                }

                "location" -> {
                    if (latitude == null) latitude = valueAsDouble(payload["latitude"])
                    if (longitude == null) longitude = valueAsDouble(payload["longitude"])
                    if (motionState == "unknown") {
                        motionState = valueAsString(payload["motionState"]).orEmpty().ifBlank { motionState }
                    }

                    if (locationLabel.startsWith("Unknown", ignoreCase = true)) {
                        val city = valueAsString(payload["city"]).orEmpty()
                        locationLabel = when {
                            city.isNotBlank() -> city
                            latitude != null && longitude != null ->
                                "GPS ${"%.4f".format(Locale.US, latitude)}, ${"%.4f".format(Locale.US, longitude)}"
                            else -> row.summary.take(120)
                        }
                    }
                }
            }
        }

        if (locationLabel.startsWith("Unknown", ignoreCase = true) && latitude != null && longitude != null) {
            locationLabel = "GPS ${"%.4f".format(Locale.US, latitude)}, ${"%.4f".format(Locale.US, longitude)}"
        }

        return ManualSystemSnapshot(
            lightLux = lightLux,
            ambientState = ambientState,
            activityState = activityState,
            wifi = wifi,
            cellular = cellular,
            internet = internet,
            bluetoothEnabled = bluetoothEnabled,
            wifiSsid = wifiSsid,
            locationLabel = locationLabel,
            latitude = latitude,
            longitude = longitude,
            motionState = motionState,
        )
    }

    private fun readManualClipSnapshot(wavPath: String): ManualClipSnapshot {
        if (wavPath.isBlank()) {
            val now = System.currentTimeMillis()
            return ManualClipSnapshot(
                occurredAt = now,
                durationMs = -1L,
                clipRmsDb = -120.0,
                clipPeakDb = -120.0,
                metaPath = null,
            )
        }

        val wavFile = File(wavPath)
        val metaFile = wavFile.parentFile?.let { File(it, "${wavFile.nameWithoutExtension}.json") }
        val meta = if (metaFile != null && metaFile.exists()) {
            kotlin.runCatching { JSONObject(metaFile.readText()).toMap() }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val occurredAt = valueAsLong(meta["createdAt"])
            ?: wavFile.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val durationMs = valueAsLong(meta["durationMs"]) ?: estimateWavDurationMs(wavFile.length())
        val clipRmsDb = valueAsDouble(meta["clipRmsDb"]) ?: -120.0
        val clipPeakDb = valueAsDouble(meta["clipPeakDb"]) ?: -120.0

        return ManualClipSnapshot(
            occurredAt = occurredAt,
            durationMs = durationMs,
            clipRmsDb = clipRmsDb,
            clipPeakDb = clipPeakDb,
            metaPath = metaFile?.absolutePath,
        )
    }

    private fun safePayloadMap(payloadJson: String): Map<String, Any?> {
        return kotlin.runCatching { JSONObject(payloadJson).toMap() }.getOrDefault(emptyMap())
    }

    private fun valueAsString(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value.trim()
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun valueAsDouble(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun valueAsLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun valueAsInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun valueAsBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                when (value.trim().lowercase(Locale.US)) {
                    "1", "true", "yes", "on" -> true
                    "0", "false", "no", "off" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun valueAsStringList(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { item -> valueAsString(item) }.filter { it.isNotBlank() }
            is String -> value
                .split(",", "|")
                .map { it.trim().trimStart('[', ']').trimEnd('[', ']') }
                .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            else -> emptyList()
        }
    }

    private fun inferIndoorOutdoor(
        wifi: Boolean?,
        cellular: Boolean?,
    ): String {
        return when {
            wifi == true && cellular != true -> "Indoor likely (wifi)"
            cellular == true && wifi != true -> "Outdoor likely (cellular)"
            wifi == true && cellular == true -> "Transition or mixed"
            else -> "Unknown"
        }
    }

    private fun isNoSpeechText(raw: String): Boolean {
        val lower = raw.trim().lowercase(Locale.US)
        if (lower.isBlank()) return true
        return lower == "<no-speech>" ||
            lower == "no speech" ||
            lower == "no_speech" ||
            lower == "[silence]" ||
            lower == "silence" ||
            lower.contains("no clear speech") ||
            lower.contains("speech_error_") ||
            lower.contains("未识别") ||
            lower.contains("无法识别")
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
