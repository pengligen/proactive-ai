package com.proactiveai.extreme.ui.permission

import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.EdgeInferenceTrace
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.OnDeviceInferenceEngine
import com.proactiveai.extreme.core.model.PermissionGate
import com.proactiveai.extreme.core.model.RiskLevel
import com.proactiveai.extreme.core.policy.ActionCandidate
import com.proactiveai.extreme.core.policy.ActionPolicy
import com.proactiveai.extreme.core.policy.ExecutionMode
import com.proactiveai.extreme.orchestrator.ActionPlanPayload
import com.proactiveai.extreme.orchestrator.ActionStepPayload
import com.proactiveai.extreme.orchestrator.ContextEventPayload
import com.proactiveai.extreme.orchestrator.HitlDecisionPayload
import com.proactiveai.extreme.orchestrator.HttpOrchestratorGateway
import com.proactiveai.extreme.orchestrator.OrchestratorConfig
import com.proactiveai.extreme.orchestrator.PlanRequestPayload
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.permission.PermissionStatusResolver
import com.proactiveai.extreme.service.ProactiveCollectionService
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.storage.toPayload
import com.proactiveai.extreme.sync.SyncScheduler
import com.proactiveai.extreme.ui.AppTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@Composable
fun PermissionCommandCenterScreen(
    modifier: Modifier = Modifier,
    state: PermissionCommandCenterState,
    activeTab: AppTab,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val gateway = remember { HttpOrchestratorGateway() }
    val store = remember { ContextEventStore.getInstance(context) }
    var showInferenceTestDialog by remember { mutableStateOf(false) }
    var showInferenceResultDialog by remember { mutableStateOf(false) }
    var showModelConfigDialog by remember { mutableStateOf(false) }
    var inferenceTestInput by rememberSaveable {
        mutableStateOf("I have 5 unread emails and a meeting in 30 minutes, prepare me a concise brief.")
    }
    var inferenceResultText by rememberSaveable { mutableStateOf("") }
    var modelConfigEnabled by rememberSaveable { mutableStateOf(state.localModelEnabled) }
    var modelConfigPath2B by rememberSaveable { mutableStateOf(state.localModelPath2B) }
    var modelConfigPath4B by rememberSaveable { mutableStateOf(state.localModelPath4B) }
    var activeAudioClipPath by rememberSaveable { mutableStateOf("") }
    var audioReplayRunning by rememberSaveable { mutableStateOf(false) }
    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun refreshConnectors() {
        scope.launch(Dispatchers.IO) {
            val userId = AppPrefs.getUserId(context)
            val connectors = gateway.connectorStatus(userId).getOrNull()
            withContext(Dispatchers.Main) {
                if (connectors != null) {
                    state.setConnectors(connectors)
                } else {
                    state.updateConnectorStatusMessage("Failed to load connectors")
                }
            }
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            state.refreshGrantStates()
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refreshGrantStates()
                refreshConnectors()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            kotlin.runCatching { audioPlayer?.stop() }
            kotlin.runCatching { audioPlayer?.release() }
            audioPlayer = null
            activeAudioClipPath = ""
        }
    }

    fun openSettings(permission: PermissionUiState) {
        val intent = PermissionStatusResolver.buildSettingsIntent(context, permission.id)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun runGrantAllWizard() {
        state.refreshGrantStates()

        val runtimePermissions = state.pendingRuntimePermissions()
        if (runtimePermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(runtimePermissions.toTypedArray())
            return
        }

        state.firstPendingSettingsPermission()?.let { openSettings(it) }
    }

    fun stopAudioClipPlayback() {
        kotlin.runCatching { audioPlayer?.stop() }
        kotlin.runCatching { audioPlayer?.release() }
        audioPlayer = null
        activeAudioClipPath = ""
    }

    fun playAudioClip(path: String) {
        if (path.isBlank()) return
        if (activeAudioClipPath == path) {
            stopAudioClipPlayback()
            return
        }
        stopAudioClipPlayback()
        kotlin.runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    kotlin.runCatching { it.release() }
                    audioPlayer = null
                    activeAudioClipPath = ""
                }
                prepare()
                start()
            }
        }.onSuccess { player ->
            audioPlayer = player
            activeAudioClipPath = path
        }.onFailure {
            stopAudioClipPlayback()
        }
    }

    fun replayLatestWavClips() {
        if (audioReplayRunning) return
        audioReplayRunning = true
        scope.launch(Dispatchers.IO) {
            state.replayLatestAudioClips(limit = 10)
            withContext(Dispatchers.Main) {
                audioReplayRunning = false
            }
        }
    }

    fun replaySingleWavClip(path: String) {
        if (path.isBlank() || audioReplayRunning) return
        audioReplayRunning = true
        scope.launch(Dispatchers.IO) {
            state.replaySingleAudioClip(path)
            withContext(Dispatchers.Main) {
                audioReplayRunning = false
            }
        }
    }

    fun buildContextWindow(limit: Int = 60): List<ContextEventPayload> {
        return store.getRecent(limit).map { event ->
            val payloadMap = jsonStringToPayloadMap(event.payloadJson)
            event.toPayload(payloadMap)
        }
    }

    fun persistModelTrace(
        trigger: String,
        trace: EdgeInferenceTrace,
        contextEventCount: Int,
    ) {
        val result = trace.result
        val response = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary
        val record = ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            source = "local_model",
            category = "model_io",
            summary = "Model IO [$trigger] ${result.model.label} | ${result.strategyLabel}",
            payload = mapOf(
                "trigger" to trigger,
                "mode" to trace.mode,
                "model" to result.model.label,
                "strategy" to result.strategyLabel,
                "prompt" to trace.prompt,
                "response" to response,
                "nativeUsed" to result.nativeModelUsed,
                "nativeStatus" to result.nativeModelMessage,
                "urgency" to result.urgencyScore,
                "suggestedActions" to result.suggestedActions,
                "contextEventCount" to contextEventCount,
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 7 * 24 * 3600,
        )
        store.insert(record)
    }

    fun updateAssistantFromInference(
        inferenceSummary: String,
        suggestedActions: List<String>,
        contextEventCount: Int,
        status: String,
    ) {
        val highlights = buildList {
            addAll(suggestedActions)
            inferenceSummary
                .lineSequence()
                .map { it.trim().trimStart('-', '*', '•').trim() }
                .filter { it.length >= 12 }
                .take(3)
                .forEach { add(it) }
        }
        state.updateAssistantBrief(
            briefText = inferenceSummary,
            highlights = highlights,
            eventCount = contextEventCount,
            status = status,
        )
    }

    fun fetchPlanWithEdgeInference() {
        scope.launch(Dispatchers.IO) {
            val contextWindow = buildContextWindow()
            val edgeModel = EdgeModelProfile.fromId(state.edgeModelId)
            val trace = OnDeviceInferenceEngine.inferWithTrace(
                context = context,
                model = edgeModel,
                events = contextWindow,
                runtimeConfig = state.localRuntimeConfig(),
            )
            val inference = trace.result

            val userId = AppPrefs.getUserId(context)
            val plan = gateway.requestPlan(
                PlanRequestPayload(
                    userId = userId,
                    now = System.currentTimeMillis(),
                    contextWindow = contextWindow,
                )
            ).getOrNull()

            persistModelTrace(
                trigger = "plan_inference",
                trace = trace,
                contextEventCount = contextWindow.size,
            )

            withContext(Dispatchers.Main) {
                state.setInference(inference)
                state.updateCurrentPlan(plan)
                state.refreshModelInteractions()
                state.refreshContextTimeline()
                if (plan != null) {
                    state.recordSuggestionGenerated()
                }

                val planDigest = plan?.let {
                    "Current plan goal: ${it.goal}\nRisk: ${it.riskLevel.name}\nRequires confirmation: ${it.requiresUserConfirmation}"
                } ?: "No active cloud plan yet."
                val brief = buildString {
                    appendLine("User understanding:")
                    appendLine(inference.summary)
                    appendLine()
                    appendLine(planDigest)
                }
                updateAssistantFromInference(
                    inferenceSummary = brief,
                    suggestedActions = inference.suggestedActions,
                    contextEventCount = contextWindow.size,
                    status = "Assistant brief refreshed from plan inference",
                )
            }
        }
    }

    fun runInferenceTest(prompt: String) {
        scope.launch(Dispatchers.IO) {
            val edgeModel = EdgeModelProfile.fromId(state.edgeModelId)
            val trace = OnDeviceInferenceEngine.inferFromPromptWithTrace(
                context = context,
                model = edgeModel,
                prompt = prompt,
                runtimeConfig = state.localRuntimeConfig(),
            )
            val result = trace.result
            persistModelTrace(
                trigger = "manual_inference_test",
                trace = trace,
                contextEventCount = 0,
            )
            withContext(Dispatchers.Main) {
                state.setInference(result)
                state.setPlanPreview("Inference test complete with ${result.model.label}/${result.strategyLabel}")
                state.refreshModelInteractions()
                state.refreshContextTimeline()
                val rawOutput = result.nativeModelOutput.orEmpty().trim()
                inferenceResultText = buildString {
                    appendLine("Model: ${result.model.label}")
                    appendLine("Strategy: ${result.strategyLabel}")
                    appendLine("Native used: ${if (result.nativeModelUsed) "YES" else "NO"}")
                    appendLine("Native status: ${result.nativeModelMessage}")
                    appendLine()
                    appendLine("Raw model output:")
                    if (rawOutput.isNotBlank()) {
                        appendLine(rawOutput)
                    } else {
                        appendLine("<empty>")
                        appendLine()
                        appendLine("Fallback summary:")
                        appendLine(result.summary)
                    }
                    if (result.suggestedActions.isNotEmpty()) {
                        appendLine()
                        appendLine("Suggested actions:")
                        result.suggestedActions.forEach { action ->
                            appendLine("- $action")
                        }
                    }
                }
                showInferenceResultDialog = true
            }
        }
    }

    fun generateContextInsight() {
        scope.launch(Dispatchers.IO) {
            val contextWindow = buildContextWindow(limit = 120)
            val edgeModel = EdgeModelProfile.fromId(state.edgeModelId)
            val trace = OnDeviceInferenceEngine.inferWithTrace(
                context = context,
                model = edgeModel,
                events = contextWindow,
                runtimeConfig = state.localRuntimeConfig(),
            )
            val result = trace.result
            val summary = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary

            persistModelTrace(
                trigger = "context_insight",
                trace = trace,
                contextEventCount = contextWindow.size,
            )

            withContext(Dispatchers.Main) {
                state.updateContextInsight(
                    summary = summary,
                    actions = result.suggestedActions,
                    eventCount = contextWindow.size,
                    status = "Local context inference (${result.strategyLabel})",
                )
                state.refreshModelInteractions()
                state.refreshContextTimeline()
            }
        }
    }

    fun generateAssistantBrief() {
        scope.launch(Dispatchers.IO) {
            val contextWindow = buildContextWindow(limit = 120)
            val edgeModel = EdgeModelProfile.fromId(state.edgeModelId)
            val prompt = buildAssistantPromptForBrief(contextWindow, state.currentPlan)
            val trace = OnDeviceInferenceEngine.inferFromPromptWithTrace(
                context = context,
                model = edgeModel,
                prompt = prompt,
                runtimeConfig = state.localRuntimeConfig(),
            )
            val result = trace.result
            val brief = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary

            persistModelTrace(
                trigger = "assistant_brief",
                trace = trace,
                contextEventCount = contextWindow.size,
            )

            withContext(Dispatchers.Main) {
                state.updateAssistantBrief(
                    briefText = brief,
                    highlights = result.suggestedActions + extractHighlights(brief),
                    eventCount = contextWindow.size,
                    status = "Assistant brief generated (${result.strategyLabel})",
                )
                state.refreshModelInteractions()
                state.refreshContextTimeline()
            }
        }
    }

    LaunchedEffect(activeTab, state.contextTimeline.size, state.contextInsightStatusMessage, state.assistantBriefStatusMessage) {
        if (activeTab == AppTab.CONTEXTS) {
            state.refreshAudioClips()
            state.refreshContextLogs()
            state.refreshContextTimeline()
        }
        if (
            activeTab == AppTab.CONTEXTS &&
            state.contextTimeline.isNotEmpty() &&
            state.contextInsightStatusMessage.startsWith("No context insight")
        ) {
            generateContextInsight()
        }
        if (
            activeTab == AppTab.ASSISTANT &&
            state.contextTimeline.isNotEmpty() &&
            state.assistantBriefStatusMessage.startsWith("No proactive assistant brief")
        ) {
            generateAssistantBrief()
        }
    }

    fun submitHitlDecision(plan: ActionPlanPayload, step: ActionStepPayload, approved: Boolean) {
        scope.launch(Dispatchers.IO) {
            val submitted = gateway.submitHitlDecision(
                HitlDecisionPayload(
                    planId = plan.planId,
                    approved = approved,
                    note = if (approved) "approved_from_android" else "denied_from_android",
                )
            ).getOrNull() == true

            withContext(Dispatchers.Main) {
                if (submitted) {
                    state.markStepDecision(step.stepId, approved)
                    state.recordInterruptionDecision(positive = approved)
                    if (approved) {
                        state.recordSuggestionAccepted()
                    }
                } else {
                    state.markStepExecution(step.stepId, "Decision submit failed")
                }
            }
        }
    }

    fun executeStep(plan: ActionPlanPayload, step: ActionStepPayload) {
        val queueId = state.enqueueAction(plan.planId, step)
        state.markStepExecution(step.stepId, "Queued action #$queueId")
        state.triggerQueueExecutionNow()
    }

    fun runAutoExecuteForCurrentPlan() {
        val plan = state.currentPlan ?: return
        val autoSteps = plan.steps.filter { step ->
            executionModeFor(plan.riskLevel, step) == ExecutionMode.AUTO_EXECUTE
        }
        if (autoSteps.isEmpty()) {
            state.markStepExecution("auto", "No auto-executable step in current plan")
            return
        }
        autoSteps.forEach { step ->
            executeStep(plan, step)
        }
    }

    fun authorizeConnector(connectorId: String) {
        scope.launch(Dispatchers.IO) {
            val userId = AppPrefs.getUserId(context)
            val result = gateway.authorizeConnector(connectorId, userId).getOrNull()
            val connectors = gateway.connectorStatus(userId).getOrNull()
            withContext(Dispatchers.Main) {
                if (result != null) {
                    state.updateConnectorStatusMessage(result.message)
                } else {
                    state.updateConnectorStatusMessage("Connect failed for $connectorId")
                }
                if (connectors != null) {
                    state.setConnectors(connectors)
                }
            }
        }
    }

    fun disconnectConnector(connectorId: String) {
        scope.launch(Dispatchers.IO) {
            val userId = AppPrefs.getUserId(context)
            val result = gateway.disconnectConnector(connectorId, userId).getOrNull()
            val connectors = gateway.connectorStatus(userId).getOrNull()
            withContext(Dispatchers.Main) {
                if (result != null) {
                    state.updateConnectorStatusMessage(result.message)
                } else {
                    state.updateConnectorStatusMessage("Disconnect failed for $connectorId")
                }
                if (connectors != null) {
                    state.setConnectors(connectors)
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .background(Color(0xFFF5F7FA))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (activeTab) {
            AppTab.PERMISSIONS -> {
                item {
                    SummaryCard(
                        state = state,
                        onToggleMaster = { state.toggleMaster(it) },
                        onGrantAll = ::runGrantAllWizard,
                        onOpenNextSettings = {
                            state.firstPendingSettingsPermission()?.let { openSettings(it) }
                        },
                        onCheckBackend = {
                            state.setOrchestratorHealth(null)
                            scope.launch(Dispatchers.IO) {
                                val healthy = gateway.health().getOrNull() == true
                                withContext(Dispatchers.Main) {
                                    state.setOrchestratorHealth(healthy)
                                }
                            }
                        },
                        onStartCollection = {
                            ProactiveCollectionService.start(context)
                            state.persistCollectionEnabled(true)
                        },
                        onStopCollection = {
                            ProactiveCollectionService.stop(context)
                            state.persistCollectionEnabled(false)
                        },
                        onSyncNow = { SyncScheduler.enqueueImmediate(context) },
                        onFetchPlan = ::fetchPlanWithEdgeInference,
                    )
                }

                item {
                    ConnectorCenterCard(
                        state = state,
                        onRefresh = ::refreshConnectors,
                        onAuthorize = ::authorizeConnector,
                        onDisconnect = ::disconnectConnector,
                    )
                }

                items(state.plugins, key = { it.id }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        onTogglePlugin = { enabled -> state.togglePlugin(plugin.id, enabled) },
                        onOpenSettings = { openSettings(it) },
                        onRefresh = { state.refreshGrantStates() },
                    )
                }
            }

            AppTab.MODELS -> {
                item {
                    ModelManagementCard(
                        state = state,
                        onModelSelected = { modelId -> state.setEdgeModel(modelId) },
                        onOpenInferenceTest = { showInferenceTestDialog = true },
                        onOpenModelConfig = {
                            modelConfigEnabled = state.localModelEnabled
                            modelConfigPath2B = state.localModelPath2B
                            modelConfigPath4B = state.localModelPath4B
                            showModelConfigDialog = true
                        },
                        onRefreshModelCalls = { state.refreshModelInteractions() },
                    )
                }

                item {
                    ModelInteractionJournalCard(
                        state = state,
                        onRefresh = { state.refreshModelInteractions() },
                    )
                }
            }

            AppTab.CONTEXTS -> {
                item {
                    AudioClipDebugCard(
                        state = state,
                        activeClipPath = activeAudioClipPath,
                        replayRunning = audioReplayRunning,
                        onRefresh = { state.refreshAudioClips() },
                        onReplayLatest = { replayLatestWavClips() },
                        onClear = {
                            stopAudioClipPlayback()
                            state.clearAudioClips()
                        },
                        onReplaySingle = { path -> replaySingleWavClip(path) },
                        onPlayOrStop = { path -> playAudioClip(path) },
                    )
                }

                item {
                    ContextTimelineCard(
                        state = state,
                        onRefresh = { state.refreshContextTimeline() },
                    )
                }

                item {
                    ContextLogCard(
                        state = state,
                        onRefresh = {
                            state.refreshContextLogs()
                            state.refreshContextTimeline()
                            state.refreshAudioClips()
                        },
                    )
                }

                item {
                    ContextInsightCard(
                        state = state,
                        onAnalyze = ::generateContextInsight,
                    )
                }
            }

            AppTab.ASSISTANT -> {
                item {
                    AssistantBriefCard(
                        state = state,
                        onGenerate = ::generateAssistantBrief,
                    )
                }

                item {
                    ActionCenterCard(
                        state = state,
                        onModelSelected = { modelId -> state.setEdgeModel(modelId) },
                        onToggleAutoExecuteLowRisk = { enabled -> state.persistAutoExecuteLowRisk(enabled) },
                        onGeneratePlan = ::fetchPlanWithEdgeInference,
                        onOpenInferenceTest = { showInferenceTestDialog = true },
                        onOpenModelConfig = {
                            modelConfigEnabled = state.localModelEnabled
                            modelConfigPath2B = state.localModelPath2B
                            modelConfigPath4B = state.localModelPath4B
                            showModelConfigDialog = true
                        },
                        onAutoExecute = ::runAutoExecuteForCurrentPlan,
                        onResetMetrics = { state.resetMetrics() },
                        onApproveStep = { plan, step -> submitHitlDecision(plan, step, approved = true) },
                        onDenyStep = { plan, step -> submitHitlDecision(plan, step, approved = false) },
                        onExecuteStep = ::executeStep,
                    )
                }

                item {
                    ExecutionQueueCard(
                        state = state,
                        onRefresh = { state.refreshQueue() },
                        onRunNow = { state.triggerQueueExecutionNow() },
                        onRetry = { queueId ->
                            state.retryQueueItem(queueId)
                            state.triggerQueueExecutionNow()
                        },
                        onClearSucceeded = { state.clearSucceededQueueItems() },
                    )
                }

                item {
                    ActionHistoryCard(
                        state = state,
                        onClear = { state.clearActionHistory() },
                        onCycleFilter = { state.cycleHistoryFilter() },
                        onExport = { state.exportActionHistory() },
                    )
                }
            }
        }
    }

    if (showInferenceTestDialog) {
        InferenceTestDialog(
            value = inferenceTestInput,
            onValueChange = { inferenceTestInput = it },
            onDismiss = { showInferenceTestDialog = false },
            onRun = {
                runInferenceTest(inferenceTestInput)
                showInferenceTestDialog = false
            },
        )
    }

    if (showInferenceResultDialog) {
        InferenceResultDialog(
            value = inferenceResultText,
            onDismiss = { showInferenceResultDialog = false },
        )
    }

    if (showModelConfigDialog) {
        LocalModelConfigDialog(
            enabled = modelConfigEnabled,
            path2B = modelConfigPath2B,
            path4B = modelConfigPath4B,
            onEnabledChange = { modelConfigEnabled = it },
            onPath2BChange = { modelConfigPath2B = it },
            onPath4BChange = { modelConfigPath4B = it },
            onDismiss = { showModelConfigDialog = false },
            onSave = {
                state.persistLocalModelConfig(
                    enabled = modelConfigEnabled,
                    modelPath2B = modelConfigPath2B.trim(),
                    modelPath4B = modelConfigPath4B.trim(),
                    backendId = LocalModelBackend.LITERT_LM.id,
                    ggufPath2B = state.localGgufPath2B,
                    ggufPath4B = state.localGgufPath4B,
                    llamaContextSize = state.localLlamaContextSize,
                    llamaThreads = state.localLlamaThreads,
                )
                showModelConfigDialog = false
                state.setPlanPreview("Local model config updated")
            },
        )
    }
}

@Composable
private fun SummaryCard(
    state: PermissionCommandCenterState,
    onToggleMaster: (Boolean) -> Unit,
    onGrantAll: () -> Unit,
    onOpenNextSettings: () -> Unit,
    onCheckBackend: () -> Unit,
    onStartCollection: () -> Unit,
    onStopCollection: () -> Unit,
    onSyncNow: () -> Unit,
    onFetchPlan: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A),
            contentColor = Color.White,
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Extreme Mode",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Readiness: ${state.readinessScore}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = state.masterEnabled, onCheckedChange = onToggleMaster)
            }

            Text(
                text = "Plugins: ${state.plugins.count { it.enabled }}/${state.plugins.size} | Service: ${if (state.collectionEnabled) "ON" else "OFF"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Queued Events: ${state.unsyncedEvents}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
            )
            Text(
                text = "Orchestrator: ${OrchestratorConfig.baseUrl()}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
            )
            Text(
                text = "Backend Status: ${state.orchestratorHealthLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
            )
            Text(
                text = state.lastPlanPreview,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrantAll) {
                    Text("Grant All Wizard")
                }
                OutlinedButton(onClick = onOpenNextSettings) {
                    Text("Next Settings")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheckBackend) {
                    Text("Ping Backend")
                }
                OutlinedButton(onClick = onSyncNow) {
                    Text("Sync Now")
                }
                OutlinedButton(onClick = onFetchPlan) {
                    Text("Refresh Plan")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartCollection) {
                    Text("Start Service")
                }
                OutlinedButton(onClick = onStopCollection) {
                    Text("Stop Service")
                }
            }
        }
    }
}

@Composable
private fun ModelManagementCard(
    state: PermissionCommandCenterState,
    onModelSelected: (String) -> Unit,
    onOpenInferenceTest: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onRefreshModelCalls: () -> Unit,
) {
    val selectedModel = EdgeModelProfile.fromId(state.edgeModelId)
    val activePath = if (selectedModel == EdgeModelProfile.GEMMA_EFFECTIVE_2B) {
        state.localModelPath2B
    } else {
        state.localModelPath4B
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "LiteRT-LM Model Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Use local LiteRT-LM model, run inference tests, and review all prompt/response calls.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.edgeModelOptions.forEach { option ->
                    val selected = option.id == state.edgeModelId
                    if (selected) {
                        Button(onClick = { onModelSelected(option.id) }) {
                            Text(option.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onModelSelected(option.id) }) {
                            Text(option.label)
                        }
                    }
                }
            }

            Text(
                text = EdgeModelProfile.fromId(state.edgeModelId).description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Native model: ${if (state.localModelEnabled) "ON" else "OFF"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Backend: ${LocalModelBackend.fromId(state.localModelBackendId).label}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Active model file: ${activePath.ifBlank { "<unset>" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Native status: ${state.latestNativeModelStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            Text(
                text = "Latest output: ${state.latestNativeModelOutput.ifBlank { "<empty>" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            Text(
                text = "Logged local calls: ${state.modelInteractions.size}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenInferenceTest) {
                    Text("Inference Test")
                }
                OutlinedButton(onClick = onOpenModelConfig) {
                    Text("Model Config")
                }
                OutlinedButton(onClick = onRefreshModelCalls) {
                    Text("Refresh Calls")
                }
            }
        }
    }
}

@Composable
private fun ModelInteractionJournalCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Local Model Prompt/Response Journal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Text(
                text = state.modelInteractionStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            if (state.modelInteractions.isEmpty()) {
                Text(
                    text = "No model call records yet. Run Inference Test or generate assistant/context insights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.modelInteractions.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${item.timestampLabel} | ${item.trigger} | ${item.modelLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Status: ${item.status}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                            )
                            Text(
                                text = "Prompt:\n${item.prompt.ifBlank { "<empty>" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                            )
                            Text(
                                text = "Response:\n${item.response.ifBlank { "<empty>" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBriefCard(
    state: PermissionCommandCenterState,
    onGenerate: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Proactive Understanding Brief",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onGenerate) {
                    Text("Generate")
                }
            }

            Text(
                text = state.assistantBriefStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = state.assistantBriefText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )

            if (state.assistantHighlights.isNotEmpty()) {
                Text(
                    text = "Potential help:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF334155),
                )
                state.assistantHighlights.forEach { highlight ->
                    Text(
                        text = "- $highlight",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCenterCard(
    state: PermissionCommandCenterState,
    onModelSelected: (String) -> Unit,
    onToggleAutoExecuteLowRisk: (Boolean) -> Unit,
    onGeneratePlan: () -> Unit,
    onOpenInferenceTest: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onAutoExecute: () -> Unit,
    onResetMetrics: () -> Unit,
    onApproveStep: (ActionPlanPayload, ActionStepPayload) -> Unit,
    onDenyStep: (ActionPlanPayload, ActionStepPayload) -> Unit,
    onExecuteStep: (ActionPlanPayload, ActionStepPayload) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Action Center",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "On-device model",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF334155),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.edgeModelOptions.forEach { option ->
                    val selected = option.id == state.edgeModelId
                    if (selected) {
                        Button(onClick = { onModelSelected(option.id) }) {
                            Text(option.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onModelSelected(option.id) }) {
                            Text(option.label)
                        }
                    }
                }
            }
            Text(
                text = EdgeModelProfile.fromId(state.edgeModelId).description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Native model: ${if (state.localModelEnabled) "ON" else "OFF"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Backend: ${LocalModelBackend.fromId(state.localModelBackendId).label}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Auto execute low-risk plan",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = state.autoExecuteLowRisk,
                    onCheckedChange = onToggleAutoExecuteLowRisk,
                )
            }

            HorizontalDivider()

            Text(
                text = "Inference urgency: ${state.latestInferenceUrgency}/100",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Strategy: ${state.latestInferenceStrategy}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = "Native status: ${state.latestNativeModelStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.latestNativeModelStatus.startsWith("Native model used")) Color(0xFF15803D) else Color(0xFFB45309),
            )
            Text(
                text = "Model inference raw output:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF334155),
            )
            Text(
                text = if (state.latestNativeModelOutput.isNotBlank()) {
                    state.latestNativeModelOutput
                } else {
                    "<empty - check Native status above>"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            Text(
                text = "Heuristic summary:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF64748B),
            )
            Text(
                text = state.latestInferenceSummary,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            if (state.intentHints.isNotEmpty()) {
                state.intentHints.forEach { hint ->
                    Text(
                        text = "- ${hint.label} (${(hint.confidence * 100).toInt()}%): ${hint.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569),
                    )
                }
            }
            if (state.suggestedActions.isNotEmpty()) {
                state.suggestedActions.forEach { action ->
                    Text(
                        text = "Suggested: $action",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569),
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Intent Match ${state.metrics.intentMatchRate}% | Helpfulness ${state.metrics.actionHelpfulness}% | Interruption Quality ${state.metrics.interruptionQuality}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            Text(
                text = "Suggestions ${state.metrics.suggestionsAccepted}/${state.metrics.suggestionsTotal}, Executions ${state.metrics.executionsSuccessful}/${state.metrics.executionsTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGeneratePlan) {
                    Text("Generate Plan")
                }
                OutlinedButton(onClick = onOpenInferenceTest) {
                    Text("Inference Test")
                }
                OutlinedButton(onClick = onOpenModelConfig) {
                    Text("Model Config")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAutoExecute) {
                    Text("Run Auto Steps")
                }
                OutlinedButton(onClick = onResetMetrics) {
                    Text("Reset Metrics")
                }
            }

            Text(
                text = state.latestExecutionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )

            val plan = state.currentPlan
            if (plan == null) {
                Text(
                    text = "No active plan. Generate a plan to start HITL and execution.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B),
                )
            } else {
                HorizontalDivider()
                Text(
                    text = "Goal: ${plan.goal}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Risk=${plan.riskLevel.name} | Confirmation=${plan.requiresUserConfirmation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF334155),
                )
                Text(
                    text = plan.explainWhy,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF334155),
                )

                plan.steps.forEach { step ->
                    val mode = executionModeFor(plan.riskLevel, step)
                    val decision = state.stepDecision(step.stepId)
                    val executionResult = state.stepExecutionResult(step.stepId)
                    ActionStepCard(
                        step = step,
                        mode = mode,
                        decision = decision,
                        executionResult = executionResult,
                        onApprove = { onApproveStep(plan, step) },
                        onDeny = { onDenyStep(plan, step) },
                        onExecute = { onExecuteStep(plan, step) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InferenceTestDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Inference Test")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "输入一段你当前场景，端上策略会生成 synthetic context 并即时推理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRun, enabled = value.isNotBlank()) {
                Text("Run Test")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun InferenceResultDialog(
    value: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Inference Result")
        },
        text = {
            TextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun LocalModelConfigDialog(
    enabled: Boolean,
    path2B: String,
    path4B: String,
    onEnabledChange: (Boolean) -> Unit,
    onPath2BChange: (String) -> Unit,
    onPath4BChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Local Model Config")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Enable native model",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }
                Text(
                    text = "Backend is fixed to LiteRT-LM in this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
                Text(
                    text = "LiteRT-LM model paths (.litertlm):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
                TextField(
                    value = path2B,
                    onValueChange = onPath2BChange,
                    label = { Text("2B lite model path") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = path4B,
                    onValueChange = onPath4BChange,
                    label = { Text("4B lite model path") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Example path: /data/user/0/com.proactiveai.extreme/files/models/gemma-4-E2B-it.litertlm",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ActionStepCard(
    step: ActionStepPayload,
    mode: ExecutionMode,
    decision: Boolean?,
    executionResult: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onExecute: () -> Unit,
) {
    val executeEnabled = when (mode) {
        ExecutionMode.AUTO_EXECUTE -> true
        ExecutionMode.REQUIRE_CONFIRMATION -> decision == true
        ExecutionMode.BLOCK -> false
    }

    val modeLabel = when (mode) {
        ExecutionMode.AUTO_EXECUTE -> "AUTO_EXECUTE"
        ExecutionMode.REQUIRE_CONFIRMATION -> "REQUIRE_CONFIRMATION"
        ExecutionMode.BLOCK -> "BLOCK"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${step.connector}.${step.operation}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Policy: $modeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            if (step.args.isNotEmpty()) {
                Text(
                    text = "Args keys: ${step.args.keys.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }

            if (decision != null) {
                Text(
                    text = "Decision: ${if (decision) "Approved" else "Denied"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (decision) Color(0xFF15803D) else Color(0xFFB91C1C),
                )
            }

            if (!executionResult.isNullOrBlank()) {
                Text(
                    text = executionResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF334155),
                )
            }

            if (mode == ExecutionMode.REQUIRE_CONFIRMATION) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onApprove) {
                        Text("Approve")
                    }
                    OutlinedButton(onClick = onDeny) {
                        Text("Deny")
                    }
                }
            }

            OutlinedButton(onClick = onExecute, enabled = executeEnabled) {
                Text("Execute Step")
            }
        }
    }
}

@Composable
private fun ConnectorCenterCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
    onAuthorize: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Connector Center",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Text(
                text = state.connectorStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            if (state.connectors.isEmpty()) {
                Text(
                    text = "No connector status loaded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.connectors.forEach { connector ->
                    ConnectorRow(
                        connector = connector,
                        onAuthorize = { onAuthorize(connector.id) },
                        onDisconnect = { onDisconnect(connector.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectorRow(
    connector: ConnectorUiState,
    onAuthorize: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = connector.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (connector.connected) "Connected" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (connector.connected) Color(0xFF15803D) else Color(0xFFB91C1C),
            )
            connector.accountLabel?.let {
                Text(
                    text = "Account: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }
            connector.lastConnectedLabel?.let {
                Text(
                    text = "Last connected: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connector.connected) {
                    OutlinedButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                } else {
                    Button(onClick = onAuthorize) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextTimelineCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All Context Events",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Text(
                text = state.contextTimelineStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            if (state.contextTimeline.isEmpty()) {
                Text(
                    text = "No context events captured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.contextTimeline.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${item.timestampLabel} | ${item.source}.${item.category}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF475569),
                            )
                            Text(
                                text = "sensitivity=${item.sensitivity} | ${if (item.synced) "synced" else "pending_sync"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextInsightCard(
    state: PermissionCommandCenterState,
    onAnalyze: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Context Potential Help Inference",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onAnalyze) {
                    Text("Analyze")
                }
            }

            Text(
                text = state.contextInsightStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = state.contextInsightSummary,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )

            if (state.contextInsightActions.isNotEmpty()) {
                Text(
                    text = "Potential actions:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF334155),
                )
                state.contextInsightActions.forEach { action ->
                    Text(
                        text = "- $action",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioClipDebugCard(
    state: PermissionCommandCenterState,
    activeClipPath: String,
    replayRunning: Boolean,
    onRefresh: () -> Unit,
    onReplayLatest: () -> Unit,
    onClear: () -> Unit,
    onReplaySingle: (String) -> Unit,
    onPlayOrStop: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Audio Clips Debug",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh) {
                        Text("Refresh")
                    }
                    OutlinedButton(
                        onClick = onReplayLatest,
                        enabled = !replayRunning,
                    ) {
                        Text(if (replayRunning) "Re-run..." else "Re-run STT")
                    }
                    OutlinedButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }

            Text(
                text = state.audioClipStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            if (state.audioClips.isEmpty()) {
                Text(
                    text = "No wav clips yet. Keep service on and speak near the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.audioClips.forEach { clip ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${clip.capturedAtLabel} | ${clip.fileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "status=${clip.status} | strategy=${clip.strategy} | duration=${clip.durationLabel} | size=${clip.sizeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                            )
                            if (clip.reason.isNotBlank()) {
                                Text(
                                    text = "reason: ${clip.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF64748B),
                                )
                            }
                            if (clip.modelStatus.isNotBlank()) {
                                Text(
                                    text = "detail: ${clip.modelStatus}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF64748B),
                                )
                            }
                            if (clip.transcript.isNotBlank()) {
                                Text(
                                    text = "transcript: ${clip.transcript}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF334155),
                                )
                            }
                            if (clip.stitchedTranscript.isNotBlank()) {
                                Text(
                                    text = "full sentence: ${clip.stitchedTranscript}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF0F766E),
                                )
                            }
                            Text(
                                text = clip.filePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF475569),
                            )

                            val isPlaying = activeClipPath == clip.filePath
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isPlaying) {
                                    OutlinedButton(onClick = { onPlayOrStop(clip.filePath) }) {
                                        Text("Stop")
                                    }
                                } else {
                                    Button(onClick = { onPlayOrStop(clip.filePath) }) {
                                        Text("Play")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onReplaySingle(clip.filePath) },
                                    enabled = !replayRunning,
                                ) {
                                    Text("Re-run")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextLogCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Context Log (1 min)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Text(
                text = state.contextLogStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            if (state.contextLogs.isEmpty()) {
                Text(
                    text = "No logs yet. Keep collection service ON for 1+ minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.contextLogs.forEach { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${log.timestampLabel} | ${if (log.synced) "synced" else "pending_sync"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                            )
                            Text(
                                text = log.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF334155),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionHistoryCard(
    state: PermissionCommandCenterState,
    onClear: () -> Unit,
    onCycleFilter: () -> Unit,
    onExport: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Action History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onClear) {
                    Text("Clear")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCycleFilter) {
                    Text("Filter: ${state.historyFilter.label}")
                }
                OutlinedButton(onClick = onExport) {
                    Text("Export")
                }
            }
            Text(
                text = state.historyExportLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            if (state.actionHistory.isEmpty()) {
                Text(
                    text = "No action history yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.actionHistory.forEach { item ->
                    Text(
                        text = "${item.timestampLabel} | ${item.eventType} | ${item.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF334155),
                    )
                    if (!item.detail.isNullOrBlank()) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ExecutionQueueCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
    onRunNow: () -> Unit,
    onRetry: (Long) -> Unit,
    onClearSucceeded: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Execution Queue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Text(
                text = state.queueStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRunNow) {
                    Text("Run Now")
                }
                OutlinedButton(onClick = onClearSucceeded) {
                    Text("Clear Done")
                }
            }

            if (state.executionQueue.isEmpty()) {
                Text(
                    text = "No queued action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            } else {
                state.executionQueue.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Status=${item.status} Attempts=${item.attempts} Updated=${item.updatedAtLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                            )
                            item.nextRetryLabel?.let {
                                Text(
                                    text = "Next retry: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF64748B),
                                )
                            }
                            item.lastError?.let {
                                Text(
                                    text = "Error: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB91C1C),
                                )
                            }
                            if (item.status == "FAILED") {
                                OutlinedButton(onClick = { onRetry(item.id) }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginUiState,
    onTogglePlugin: (Boolean) -> Unit,
    onOpenSettings: (PermissionUiState) -> Unit,
    onRefresh: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Risk: ${plugin.riskLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB45309),
                    )
                }
                Switch(checked = plugin.enabled, onCheckedChange = onTogglePlugin)
            }

            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF334155),
            )

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plugin.permissions.forEach { permission ->
                    PermissionRow(
                        permission = permission,
                        onOpenSettings = onOpenSettings,
                        onRefresh = onRefresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    permission: PermissionUiState,
    onOpenSettings: (PermissionUiState) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = permission.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Gate=${permission.gate} | TTL=${permission.ttlHours}h",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Text(
                text = permission.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (permission.granted) "GRANTED" else "PENDING",
                style = MaterialTheme.typography.labelMedium,
                color = if (permission.granted) Color(0xFF15803D) else Color(0xFFB91C1C),
            )

            val needsSettingsResolution =
                permission.gate != PermissionGate.RUNTIME_DIALOG ||
                    (permission.id == "background_location" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

            if (!permission.granted && needsSettingsResolution) {
                OutlinedButton(onClick = { onOpenSettings(permission) }) {
                    Text("Open")
                }
            }

            if (
                !permission.granted &&
                permission.gate == PermissionGate.RUNTIME_DIALOG &&
                permission.id != "background_location" &&
                permission.androidPermission != null
            ) {
                val requestLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = {
                        onRefresh()
                    },
                )

                OutlinedButton(onClick = {
                    requestLauncher.launch(permission.androidPermission)
                }) {
                    Text("Request")
                }
            }
        }
    }
}

private fun executionModeFor(planRisk: RiskLevel, step: ActionStepPayload): ExecutionMode {
    val sensitiveConnectors = setOf("gmail", "slack", "github")
    return ActionPolicy.decide(
        ActionCandidate(
            actionId = step.stepId,
            riskLevel = planRisk,
            touchesSensitiveConnector = step.connector.lowercase() in sensitiveConnectors,
        )
    )
}

private fun jsonStringToPayloadMap(payloadJson: String): Map<String, Any> {
    val raw = JSONObject(payloadJson).toMap()
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
                if (k != null) {
                    nested[k.toString()] = normalized
                }
            }
            nested
        }
        is List<*> -> value.mapNotNull { normalizeAny(it) }
        else -> value
    }
}

private fun extractHighlights(text: String): List<String> {
    return text
        .lineSequence()
        .map { it.trim().trimStart('-', '*', '•').trim() }
        .filter { it.length >= 12 }
        .distinct()
        .take(6)
        .toList()
}

private fun buildAssistantPromptForBrief(
    contextWindow: List<ContextEventPayload>,
    plan: ActionPlanPayload?,
): String {
    val recentDigest = contextWindow
        .sortedByDescending { it.occurredAt }
        .take(24)
        .joinToString(separator = "\n") { event ->
            "- [${event.category}/${event.source}] ${event.summary.take(160)}"
        }
        .ifBlank { "- No recent context events." }

    val planDigest = if (plan == null) {
        "No active plan."
    } else {
        buildString {
            appendLine("Goal: ${plan.goal}")
            appendLine("Risk: ${plan.riskLevel.name}")
            appendLine("Requires confirmation: ${plan.requiresUserConfirmation}")
            appendLine("Top steps:")
            plan.steps.take(4).forEach { step ->
                appendLine("- ${step.connector}.${step.operation}")
            }
        }.trim()
    }

    return """
        You are a proactive on-device executive assistant.
        Based on the user's latest context, generate a useful daily brief in plain language.
        Output must include:
        1) "Current understanding" (1-2 lines)
        2) "Potential help" (3 bullet points)
        3) "What to confirm with user" (1-2 bullet points)
        Keep it actionable and specific.

        Active plan:
        $planDigest

        Recent contexts:
        $recentDigest
    """.trimIndent()
}
