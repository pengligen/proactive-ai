package com.proactiveai.extreme.ui.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.assistant.AssistantQuickAction
import com.proactiveai.extreme.assistant.AssistantQuickActionPlanner
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.EdgeInferenceTrace
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelStorageManager
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.core.edge.ModelDownloadPlan
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
import com.proactiveai.extreme.orchestrator.HttpMobileSyncHealthGateway
import com.proactiveai.extreme.orchestrator.MobileSyncConfig
import com.proactiveai.extreme.orchestrator.OrchestratorConfig
import com.proactiveai.extreme.orchestrator.PlanRequestPayload
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.permission.PermissionStatusResolver
import com.proactiveai.extreme.service.AssistantSessionAutoRunner
import com.proactiveai.extreme.service.CloudAssistantActionPlanner
import com.proactiveai.extreme.service.CloudSpeechTranscriptionRefiner
import com.proactiveai.extreme.service.DailyFocusTop3AutoRunner
import com.proactiveai.extreme.service.ProactiveCollectionService
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.storage.toPayload
import com.proactiveai.extreme.sync.SyncScheduler
import com.proactiveai.extreme.ui.AppTab
import com.proactiveai.extreme.ui.theme.Amber600
import com.proactiveai.extreme.ui.theme.Emerald500
import com.proactiveai.extreme.ui.theme.Emerald600
import com.proactiveai.extreme.ui.theme.Gold200
import com.proactiveai.extreme.ui.theme.Gold500
import com.proactiveai.extreme.ui.theme.Rose600
import com.proactiveai.extreme.ui.theme.Sky700
import com.proactiveai.extreme.ui.theme.Slate100
import com.proactiveai.extreme.ui.theme.Slate200
import com.proactiveai.extreme.ui.theme.Slate50
import com.proactiveai.extreme.ui.theme.Slate500
import com.proactiveai.extreme.ui.theme.Slate600
import com.proactiveai.extreme.ui.theme.Slate700
import com.proactiveai.extreme.ui.theme.Slate800
import com.proactiveai.extreme.ui.theme.Slate900
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val ASSISTANT_SESSION_UI_REFRESH_MS = 60_000L
private const val CONTEXT_CARD_MAX_ITEMS = 10
private val CONTEXT_CARD_SCROLL_MAX_HEIGHT = 420.dp
private const val MODEL_JOURNAL_MAX_ITEMS = 20
private val MODEL_JOURNAL_SCROLL_MAX_HEIGHT = 520.dp
private const val ASSISTANT_DIARY_MAX_ITEMS = 12
private const val ASSISTANT_DIARY_WINDOW_MS = 24 * 60 * 60 * 1000L
private const val ASSISTANT_DIARY_REFRESH_MS = ASSISTANT_DIARY_WINDOW_MS
private val ASSISTANT_DIARY_SCROLL_MAX_HEIGHT = 520.dp
private const val ASSISTANT_SESSION_FUTURE_TOLERANCE_MS = 10 * 60 * 1000L
private const val ASSISTANT_SESSION_LOOKBACK_MS = 10 * 24 * 60 * 60 * 1000L
private const val LOCATION_SESSION_FALLBACK_LOOKBACK_MS = 12 * 60 * 60 * 1000L
private const val EXECUTION_QUEUE_RENDER_MAX_ITEMS = 12
private val EXECUTION_QUEUE_SCROLL_MAX_HEIGHT = 420.dp
private const val ENGAGED_SESSION_RENDER_MAX_ITEMS = 10

@Composable
fun PermissionCommandCenterScreen(
    modifier: Modifier = Modifier,
    state: PermissionCommandCenterState,
    activeTab: AppTab,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val gateway = remember(context) { HttpOrchestratorGateway(appContext = context.applicationContext) }
    val mobileSyncHealthGateway = remember(context) {
        HttpMobileSyncHealthGateway(context = context.applicationContext)
    }
    val store = remember { ContextEventStore.getInstance(context) }
    var showInferenceTestDialog by remember { mutableStateOf(false) }
    var showInferenceResultDialog by remember { mutableStateOf(false) }
    var showModelConfigDialog by remember { mutableStateOf(false) }
    var showMobileSyncConfigDialog by remember { mutableStateOf(false) }
    var inferenceTestInput by rememberSaveable {
        mutableStateOf("你是谁，给我描述一下你的功能")
    }
    var inferenceResultText by rememberSaveable { mutableStateOf("") }
    var modelConfigEnabled by rememberSaveable { mutableStateOf(state.localModelEnabled) }
    var modelConfigPathActive by rememberSaveable {
        mutableStateOf(state.localModelPathFor(EdgeModelProfile.fromId(state.edgeModelId)))
    }
    var modelConfigActiveModelId by rememberSaveable { mutableStateOf(state.edgeModelId) }
    var modelConfigOpenAiKey by rememberSaveable { mutableStateOf(state.openAiApiKey) }
    var selectedModelDownloadProfile by rememberSaveable { mutableStateOf(EdgeModelProfile.GEMMA_EFFECTIVE_2B.id) }
    var modelDownloadInFlight by rememberSaveable { mutableStateOf(false) }
    var mobileSyncBaseUrl by rememberSaveable { mutableStateOf(state.mobileApiBaseUrl) }
    var activeAudioClipPath by rememberSaveable { mutableStateOf("") }
    var audioReplayRunning by rememberSaveable { mutableStateOf(false) }
    var cloudTranscribeRunning by rememberSaveable { mutableStateOf(false) }
    var engagedSessionRunning by rememberSaveable { mutableStateOf(false) }
    var engagedSessionListening by rememberSaveable { mutableStateOf(false) }
    var engagedSessionStopInFlight by rememberSaveable { mutableStateOf(false) }
    var engagedSessionCloudTranscribing by rememberSaveable { mutableStateOf(false) }
    var engagedSessionStatus by rememberSaveable { mutableStateOf("Tap Engage to start an engaged session.") }
    var engagedSessionLatestSavedTranscript by rememberSaveable { mutableStateOf("") }
    var engagedSessionStartedAt by rememberSaveable { mutableStateOf(0L) }
    var engagedSessionLocaleTag by rememberSaveable { mutableStateOf(Locale.getDefault().toLanguageTag()) }
    var engagedSessionToggleCooldownUntil by rememberSaveable { mutableStateOf(0L) }
    var engagedSessionCurrentWavPath by rememberSaveable { mutableStateOf("") }
    var engagedSessionCaptureJob by remember { mutableStateOf<Job?>(null) }
    val engagedSessionStopSignal = remember { AtomicBoolean(false) }
    var assistantSessionGenerating by remember { mutableStateOf(false) }
    var manualSpeechIntakeRunning by rememberSaveable { mutableStateOf(false) }
    var manualSpeechHoldActive by rememberSaveable { mutableStateOf(false) }
    var manualSpeechBackgroundTranscribeCount by rememberSaveable { mutableStateOf(0) }
    var manualSpeechRequestSeq by rememberSaveable { mutableStateOf(0L) }
    var manualSpeechLatestHandledSeq by rememberSaveable { mutableStateOf(0L) }
    var manualSpeechIntakeStatus by rememberSaveable { mutableStateOf("No manual speech intake yet.") }
    var manualSpeechLatestTranscript by rememberSaveable { mutableStateOf("") }
    var manualSpeechIntakeJob by remember { mutableStateOf<Job?>(null) }
    val manualSpeechHoldStopSignal = remember { AtomicBoolean(false) }
    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var dailyFocusGenerating by rememberSaveable { mutableStateOf(false) }
    var manualSyncInFlight by rememberSaveable { mutableStateOf(false) }
    var fourHourDiaryRows by remember { mutableStateOf(emptyList<FourHourDiaryRow>()) }
    var fourHourDiaryStatus by remember { mutableStateOf("No daily diary yet") }
    var fourHourDiaryRefreshing by remember { mutableStateOf(false) }
    var fourHourDiaryQueuedRefresh by remember { mutableStateOf(false) }
    var lastFourHourDiaryRefreshAt by remember { mutableStateOf(0L) }
    var inAppSearchAction by remember { mutableStateOf<AssistantQuickAction?>(null) }
    var inAppSearchUrl by remember { mutableStateOf("") }
    var inAppSearchAiMode by remember { mutableStateOf(false) }

    fun isGlobalLocked(): Boolean = state.globalLockEnabled

    fun lockBlockedMessage(action: String): String {
        return "Global lock enabled: blocked $action."
    }

    fun markLockBlocked(action: String) {
        val message = lockBlockedMessage(action)
        state.setPlanPreview(message)
        state.updateConnectorStatusMessage(message)
    }

    fun openQuickActionInApp(action: AssistantQuickAction) {
        if (isGlobalLocked()) {
            markLockBlocked("in-app AI search")
            return
        }
        val resolved = resolveInAppQuickActionUrl(action.url)
        inAppSearchAction = action
        inAppSearchUrl = resolved.url
        inAppSearchAiMode = resolved.aiMode
    }

    fun refreshConnectors() {
        if (isGlobalLocked()) {
            markLockBlocked("connector sync")
            return
        }
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

    fun downloadSelectedModelToPrivateStorage(
        profileOverride: EdgeModelProfile? = null,
    ) {
        if (modelDownloadInFlight) return
        val selectedProfile = profileOverride ?: EdgeModelProfile.fromId(selectedModelDownloadProfile)
        selectedModelDownloadProfile = selectedProfile.id
        if (isGlobalLocked()) {
            val profileLabel = ModelDownloadPlan.shortLabelFor(selectedProfile)
            markLockBlocked("$profileLabel model download")
            state.updateModelDownloadProgress(-1)
            state.updateModelDownloadStatus("Global lock enabled: $profileLabel model download blocked.")
            return
        }

        modelDownloadInFlight = true
        scope.launch {
            try {
                state.downloadAndActivateModel(
                    profile = selectedProfile,
                    downloadUrl = ModelDownloadPlan.defaultUrlFor(selectedProfile),
                    huggingFaceToken = state.huggingFaceToken,
                )
            } finally {
                modelDownloadInFlight = false
            }
        }
    }

    suspend fun awaitManualSyncResult(previousQueued: Int): Int {
        var latest = previousQueued
        repeat(8) {
            delay(750)
            latest = store.countUnsyncedMobileItems()
            if (latest != previousQueued || latest == 0) {
                return latest
            }
        }
        return latest
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
            manualSpeechHoldStopSignal.set(true)
            manualSpeechHoldActive = false
            manualSpeechIntakeRunning = false
            manualSpeechIntakeJob?.cancel()
            engagedSessionStopSignal.set(true)
            engagedSessionCaptureJob?.cancel()
            engagedSessionRunning = false
            engagedSessionListening = false
            engagedSessionStopInFlight = false
            engagedSessionCloudTranscribing = false
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

    fun cloudTranscribeSingleWavClip(path: String) {
        if (path.isBlank() || cloudTranscribeRunning) return
        if (isGlobalLocked()) {
            markLockBlocked("cloud transcription")
            return
        }
        cloudTranscribeRunning = true
        scope.launch(Dispatchers.IO) {
            state.runCloudTranscribeSingleAudioClip(path)
            withContext(Dispatchers.Main) {
                cloudTranscribeRunning = false
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
        runCatching {
            LocalModelStorageManager.appendInferenceLog(
                context = context.applicationContext,
                entry = com.proactiveai.extreme.core.edge.ModelInferenceLogEntry(
                    occurredAtMs = record.occurredAt,
                    trigger = trigger,
                    mode = trace.mode,
                    modelId = result.model.id,
                    modelLabel = result.model.label,
                    prompt = trace.prompt,
                    response = response,
                    status = result.nativeModelMessage,
                    nativeUsed = result.nativeModelUsed,
                ),
            )
        }
    }

    fun persistCloudAssistantTrace(
        trigger: String,
        outcome: CloudAssistantActionPlanner.Outcome,
        contextEventCount: Int,
        sessionId: String,
        sessionLabel: String,
    ) {
        if (!outcome.attempted) return
        val response = outcome.rawResponse.ifBlank { outcome.detail }
        val record = ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            source = "cloud_model",
            category = "model_io",
            summary = "Model IO [$trigger] ${outcome.modelLabel} | ${outcome.status}",
            payload = mapOf(
                "trigger" to trigger,
                "mode" to "cloud",
                "model" to outcome.modelLabel,
                "strategy" to "cloud_action_planner",
                "prompt" to outcome.prompt,
                "response" to response,
                "status" to outcome.status,
                "detail" to outcome.detail,
                "latencyMs" to outcome.latencyMs,
                "contextEventCount" to contextEventCount,
                "sessionId" to sessionId,
                "sessionLabel" to sessionLabel,
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 7 * 24 * 3600,
        )
        store.insert(record)
        runCatching {
            LocalModelStorageManager.appendInferenceLog(
                context = context.applicationContext,
                entry = com.proactiveai.extreme.core.edge.ModelInferenceLogEntry(
                    occurredAtMs = record.occurredAt,
                    trigger = trigger,
                    mode = "cloud",
                    modelId = outcome.modelLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_"),
                    modelLabel = outcome.modelLabel,
                    prompt = outcome.prompt,
                    response = response,
                    status = outcome.status,
                    nativeUsed = false,
                ),
            )
        }
    }

    fun persistAssistantSessionRow(
        session: FifteenMinuteSession,
        row: AssistantSessionRowUiState,
    ) {
        val record = ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            source = "assistant_engine",
            category = "assistant_session",
            summary = "Assistant session ${row.sessionLabel} | ${row.cloudGuessedUserScenario.ifBlank { row.guessedUserScenario }.take(120)}",
            payload = mapOf(
                "sessionId" to row.sessionId,
                "sessionLabel" to row.sessionLabel,
                "sessionStartMs" to session.startMs,
                "sessionEndMs" to session.endMs,
                "eventCount" to row.eventCount,
                "sparklingSession" to row.sparklingSession,
                "sparklingSignalCount" to row.sparklingSignalCount,
                "sparklingTriggers" to session.sparklingTriggers,
                "speechSummary" to row.speechSummary,
                "positionSummary" to row.positionSummary,
                "indoorOutdoor" to row.indoorOutdoor,
                "locationLabel" to row.locationLabel,
                "calendarSummary" to row.calendarSummary,
                "guessedUserScenario" to row.guessedUserScenario,
                "suggestion" to row.guessedUserScenario,
                "actionPlan" to row.actionPlan,
                "cloudGuessedUserScenario" to row.cloudGuessedUserScenario,
                "cloudActionPlan" to row.cloudActionPlan,
                "cloudComparison" to row.cloudComparison,
                "cloudModelLabel" to row.cloudModelLabel,
                "cloudStatus" to row.cloudStatus,
                "quickActions" to AssistantQuickActionPlanner.toPayload(row.quickActions),
                "modelLabel" to row.modelLabel,
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
        if (isGlobalLocked()) {
            markLockBlocked("plan generation and model inference")
            return
        }
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
        if (isGlobalLocked()) {
            markLockBlocked("manual inference test")
            inferenceResultText = "Global lock enabled: prompt inference is blocked."
            showInferenceResultDialog = true
            return
        }
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
        if (isGlobalLocked()) {
            state.updateContextInsight(
                summary = "Global lock enabled: local context inference is blocked.",
                actions = listOf("Disable Global Lock to allow inference."),
                eventCount = 0,
                status = "Context inference blocked",
            )
            markLockBlocked("context insight inference")
            return
        }
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
        if (isGlobalLocked()) {
            state.updateAssistantBrief(
                briefText = "Global lock enabled: assistant brief inference is blocked.",
                highlights = listOf("Disable Global Lock to generate assistant brief."),
                eventCount = 0,
                status = "Assistant brief blocked",
            )
            markLockBlocked("assistant brief inference")
            return
        }
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

    fun generateAssistantSessions() {
        if (assistantSessionGenerating) return
        if (isGlobalLocked()) {
            state.updateAssistantSessions(
                rows = state.assistantSessionRows,
                status = "Assistant session generation blocked by Global Lock",
            )
            markLockBlocked("assistant session inference")
            return
        }
        assistantSessionGenerating = true
        scope.launch(Dispatchers.IO) {
            try {
                val contextWindow = buildContextWindow(limit = 960)
                val sessions = buildFifteenMinuteSessions(
                    events = contextWindow,
                    maxSessions = 1,
                )
                if (sessions.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        state.refreshAssistantSessions()
                    }
                    return@launch
                }

                val edgeModel = EdgeModelProfile.fromId(state.edgeModelId)
                val rows = sessions.map { session ->
                    val snapshot = buildSessionContextSnapshot(context, session.events)
                    val heuristicGuess = buildHeuristicScenarioGuess(
                        session = session,
                        snapshot = snapshot,
                        allEvents = contextWindow,
                    )
                    val localPrompt = buildAssistantPromptForSession(session, snapshot)
                    val localTrace = OnDeviceInferenceEngine.inferFromPromptWithTrace(
                        context = context,
                        model = edgeModel,
                        prompt = localPrompt,
                        runtimeConfig = state.localRuntimeConfig(),
                    )
                    persistModelTrace(
                        trigger = "assistant_session_15m",
                        trace = localTrace,
                        contextEventCount = session.events.size,
                    )
                    val localResult = localTrace.result
                    val localRaw = localResult.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: localResult.summary
                    val localParsed = parseSessionInferenceOutput(
                        output = localRaw,
                        suggestedActions = localResult.suggestedActions,
                        fallbackScenario = heuristicGuess.scenario,
                        fallbackActionPlan = heuristicGuess.actionPlan,
                        preferFallback = !localResult.nativeModelUsed,
                    )

                    val cloudOutcome = CloudAssistantActionPlanner.generate(
                        context = context.applicationContext,
                        request = CloudAssistantActionPlanner.SessionRequest(
                            sessionLabel = formatSessionRange(session.startMs, session.endMs),
                            eventCount = session.events.size,
                            sparklingSession = session.isSparkling,
                            sparklingSignalCount = session.sparklingSignalCount,
                            sparklingTriggers = session.sparklingTriggers.joinToString(separator = ", ").ifBlank { "-" },
                            speechSummary = snapshot.speechSummary,
                            positionSummary = snapshot.positionSummary,
                            indoorOutdoor = snapshot.indoorOutdoor,
                            locationLabel = snapshot.locationLabel,
                            calendarSummary = snapshot.calendarSummary,
                            localScenario = localParsed.guessedUserScenario,
                            localActionPlan = localParsed.actionPlan,
                            eventDigest = buildCloudSessionEventDigest(session.events),
                        ),
                    )
                    persistCloudAssistantTrace(
                        trigger = "assistant_session_15m_cloud",
                        outcome = cloudOutcome,
                        contextEventCount = session.events.size,
                        sessionId = session.sessionId,
                        sessionLabel = formatSessionRange(session.startMs, session.endMs),
                    )

                    val scenarioForActions = cloudOutcome.guessedUserScenario.ifBlank { localParsed.guessedUserScenario }
                    val planForActions = cloudOutcome.actionPlan.ifBlank { localParsed.actionPlan }
                    val quickActions = AssistantQuickActionPlanner.inferQuickActions(
                        speechSummary = snapshot.speechSummary,
                        guessedUserScenario = scenarioForActions,
                        actionPlan = planForActions,
                        locationLabel = snapshot.locationLabel,
                        calendarSummary = snapshot.calendarSummary,
                        extraText = listOf(localRaw, cloudOutcome.rawResponse).joinToString("\n"),
                    )

                    AssistantSessionRowUiState(
                        sessionId = session.sessionId,
                        sessionLabel = formatSessionRange(session.startMs, session.endMs),
                        eventCount = session.events.size,
                        sparklingSession = session.isSparkling,
                        sparklingSignalCount = session.sparklingSignalCount,
                        sparklingTriggers = session.sparklingTriggers.joinToString(separator = ", ").ifBlank { "-" },
                        speechSummary = snapshot.speechSummary,
                        positionSummary = snapshot.positionSummary,
                        indoorOutdoor = snapshot.indoorOutdoor,
                        locationLabel = snapshot.locationLabel,
                        calendarSummary = snapshot.calendarSummary,
                        guessedUserScenario = localParsed.guessedUserScenario,
                        actionPlan = localParsed.actionPlan,
                        cloudGuessedUserScenario = cloudOutcome.guessedUserScenario,
                        cloudActionPlan = cloudOutcome.actionPlan,
                        cloudComparison = cloudOutcome.comparison,
                        cloudModelLabel = cloudOutcome.modelLabel,
                        cloudStatus = cloudOutcome.status,
                        quickActions = quickActions,
                        modelLabel = "${localResult.model.label} | ${localResult.strategyLabel}",
                    )
                }

                sessions.zip(rows).forEach { (session, row) ->
                    persistAssistantSessionRow(
                        session = session,
                        row = row,
                    )
                }

                withContext(Dispatchers.Main) {
                    state.refreshAssistantSessions()
                    state.refreshModelInteractions()
                    state.refreshContextTimeline()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    assistantSessionGenerating = false
                }
            }
        }
    }

    fun refreshDailyDiary(
        force: Boolean = false,
        userInitiated: Boolean = false,
    ) {
        if (fourHourDiaryRefreshing) {
            if (force && userInitiated) {
                fourHourDiaryQueuedRefresh = true
                fourHourDiaryStatus = "Diary generation in progress. Queued one more refresh."
            }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastFourHourDiaryRefreshAt < ASSISTANT_DIARY_REFRESH_MS) return
        if (isGlobalLocked()) {
            fourHourDiaryStatus = "Global lock enabled: daily diary generation is blocked."
            return
        }

        if (userInitiated) {
            fourHourDiaryStatus = "Refreshing daily diary..."
        }
        fourHourDiaryRefreshing = true
        scope.launch(Dispatchers.IO) {
            try {
                val contextWindow = buildContextWindow(limit = 2400)
                val edgeModel = EdgeModelProfile.fromId(state.edgeModelId)
                val rows = buildFourHourDiaryRows(
                    context = context,
                    events = contextWindow,
                    maxRows = ASSISTANT_DIARY_MAX_ITEMS,
                    model = edgeModel,
                    runtimeConfig = state.localRuntimeConfig(),
                    onTrace = { trace, eventCount ->
                        persistModelTrace(
                            trigger = "assistant_diary_daily",
                            trace = trace,
                            contextEventCount = eventCount,
                        )
                    },
                )
                withContext(Dispatchers.Main) {
                    fourHourDiaryRows = rows
                    fourHourDiaryStatus = if (rows.isEmpty()) {
                        "No daily diary rows yet. Keep collection running."
                    } else {
                        "Showing latest ${rows.size} daily diary rows (newest-first)"
                    }
                    lastFourHourDiaryRefreshAt = System.currentTimeMillis()
                }
            } finally {
                var rerunQueued = false
                withContext(Dispatchers.Main) {
                    fourHourDiaryRefreshing = false
                    if (fourHourDiaryQueuedRefresh) {
                        rerunQueued = true
                        fourHourDiaryQueuedRefresh = false
                        fourHourDiaryStatus = "Running queued diary refresh..."
                    }
                }
                if (rerunQueued) {
                    withContext(Dispatchers.Main) {
                        refreshDailyDiary(force = true, userInitiated = false)
                    }
                }
            }
        }
    }

    fun startManualSpeechIntakeHold() {
        if (manualSpeechIntakeRunning || manualSpeechIntakeJob != null || manualSpeechHoldActive) return
        if (isGlobalLocked()) {
            markLockBlocked("manual speech intake")
            manualSpeechIntakeStatus = "Global lock enabled: manual speech intake blocked."
            return
        }

        val requestSeq = manualSpeechRequestSeq + 1L
        manualSpeechRequestSeq = requestSeq
        manualSpeechLatestHandledSeq = requestSeq
        manualSpeechHoldStopSignal.set(false)
        manualSpeechIntakeRunning = true
        manualSpeechHoldActive = true
        manualSpeechIntakeStatus = "Listening... keep pressing and speak. Release to stop."
        manualSpeechIntakeJob = scope.launch(Dispatchers.IO) {
            try {
                val result = state.captureManualSpeechIntakeContext(
                    shouldStop = { manualSpeechHoldStopSignal.get() },
                )
                withContext(Dispatchers.Main) {
                    manualSpeechIntakeRunning = false
                    manualSpeechHoldActive = false
                    manualSpeechIntakeJob = null
                    scope.launch {
                        state.refreshAudioClips()
                        state.refreshContextTimeline()
                    }

                    when (result.status) {
                        "captured" -> {
                            manualSpeechIntakeStatus = "WAV saved. Transcribing in background with GPT-4o..."
                            val wav = result.wavPath.orEmpty().trim()
                            if (wav.isBlank()) {
                                manualSpeechIntakeStatus = "WAV saved but path is missing; cannot transcribe."
                                return@withContext
                            }
                            manualSpeechBackgroundTranscribeCount += 1
                            scope.launch(Dispatchers.IO) {
                                val transcribed = kotlin.runCatching {
                                    state.transcribeManualSpeechIntakeContextFromWav(wav)
                                }.getOrElse { err ->
                                    ManualSpeechIntakeResult(
                                        status = "error",
                                        transcript = "",
                                        cloudRefined = false,
                                        wavPath = wav,
                                        detail = "Background transcription failed: ${err.message.orEmpty().ifBlank { "unknown_error" }}",
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    manualSpeechBackgroundTranscribeCount =
                                        (manualSpeechBackgroundTranscribeCount - 1).coerceAtLeast(0)
                                    if (requestSeq >= manualSpeechLatestHandledSeq) {
                                        manualSpeechLatestHandledSeq = requestSeq
                                        manualSpeechIntakeStatus = transcribed.detail
                                        if (transcribed.transcript.isNotBlank()) {
                                            manualSpeechLatestTranscript = transcribed.transcript.trim()
                                        }
                                    }
                                    scope.launch {
                                        state.refreshAudioClips()
                                        state.refreshContextTimeline()
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        if (transcribed.status == "recognized") {
                                            kotlin.runCatching {
                                                AssistantSessionAutoRunner.refreshSessionForTimestamp(
                                                    context = context.applicationContext,
                                                    occurredAtMs = System.currentTimeMillis(),
                                                )
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            state.refreshAssistantSessions()
                                            refreshDailyDiary(force = true)
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            if (requestSeq >= manualSpeechLatestHandledSeq) {
                                manualSpeechLatestHandledSeq = requestSeq
                                manualSpeechIntakeStatus = result.detail
                                if (result.transcript.isNotBlank()) {
                                    manualSpeechLatestTranscript = result.transcript.trim()
                                }
                            }
                            scope.launch(Dispatchers.IO) {
                                if (result.status == "recognized") {
                                    kotlin.runCatching {
                                        AssistantSessionAutoRunner.refreshSessionForTimestamp(
                                            context = context.applicationContext,
                                            occurredAtMs = System.currentTimeMillis(),
                                        )
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    state.refreshAssistantSessions()
                                    refreshDailyDiary(force = true)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    manualSpeechIntakeRunning = false
                    manualSpeechHoldActive = false
                    manualSpeechIntakeJob = null
                    manualSpeechIntakeStatus = "Manual intake failed: ${t.message.orEmpty().ifBlank { "unknown_error" }}"
                }
            }
        }
    }

    fun stopManualSpeechIntakeHold() {
        if (!manualSpeechIntakeRunning && !manualSpeechHoldActive) return
        manualSpeechHoldStopSignal.set(true)
        manualSpeechHoldActive = false
        if (manualSpeechIntakeRunning) {
            manualSpeechIntakeStatus = "Stopping capture... saving WAV."
        }
    }

    fun startEngagedSession() {
        if (engagedSessionRunning || engagedSessionStopInFlight || engagedSessionCloudTranscribing) return
        val now = System.currentTimeMillis()
        if (now < engagedSessionToggleCooldownUntil) return
        engagedSessionToggleCooldownUntil = now + 420L
        if (isGlobalLocked()) {
            markLockBlocked("engaged session")
            engagedSessionStatus = "Global lock enabled: engaged session blocked."
            return
        }
        val hasMicPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            runtimePermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            engagedSessionStatus = "Microphone permission required. Enable RECORD_AUDIO first."
            return
        }
        engagedSessionStartedAt = System.currentTimeMillis()
        engagedSessionCurrentWavPath = ""
        engagedSessionRunning = true
        engagedSessionListening = true
        engagedSessionStopInFlight = false
        engagedSessionStatus = "Engaged recording started. Tap Engage again to stop and transcribe."
        engagedSessionStopSignal.set(false)
        engagedSessionCaptureJob?.cancel()
        engagedSessionCaptureJob = scope.launch(Dispatchers.IO) {
            val captureResult = state.captureManualSpeechIntakeContext(
                shouldStop = { engagedSessionStopSignal.get() },
            )
            withContext(Dispatchers.Main) {
                engagedSessionCaptureJob = null
                engagedSessionRunning = false
                engagedSessionListening = false
                val wavPath = captureResult.wavPath.orEmpty().trim()
                if (captureResult.status == "captured" && wavPath.isNotBlank()) {
                    engagedSessionCurrentWavPath = wavPath
                    engagedSessionCloudTranscribing = true
                    engagedSessionStopInFlight = false
                    engagedSessionStatus = "WAV saved. Uploading to GPT-4o-transcribe..."
                    scope.launch(Dispatchers.IO) {
                        val cloud = CloudSpeechTranscriptionRefiner.transcribeSingleClip(
                            context = context.applicationContext,
                            wavPath = wavPath,
                            manualTrigger = true,
                        )
                        val persisted = if (cloud.status == "recognized" && cloud.transcript.isNotBlank()) {
                            state.persistEngagedSession(
                                sessionStartedAt = engagedSessionStartedAt,
                                sessionEndedAt = System.currentTimeMillis(),
                                transcript = cloud.transcript,
                                localeTag = engagedSessionLocaleTag,
                                segmentCount = 1,
                                wavPath = wavPath,
                                strategy = "engaged_gpt4o_transcribe",
                            )
                        } else {
                            EngagedSessionPersistResult(
                                status = cloud.status,
                                transcript = "",
                                detail = "Engaged session transcription ${cloud.status}: ${cloud.detail}",
                            )
                        }

                        withContext(Dispatchers.Main) {
                            engagedSessionCloudTranscribing = false
                            engagedSessionStopInFlight = false
                            engagedSessionStatus = persisted.detail
                            if (persisted.transcript.isNotBlank()) {
                                engagedSessionLatestSavedTranscript = persisted.transcript
                            }
                            state.refreshEngagedSessions()
                            state.refreshContextTimeline()
                            state.refreshAudioClips()
                            state.refreshAssistantSessions()
                            refreshDailyDiary(force = true)
                        }
                    }
                    return@withContext
                }

                if (captureResult.status == "recognized" && captureResult.transcript.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val persisted = state.persistEngagedSession(
                            sessionStartedAt = engagedSessionStartedAt,
                            sessionEndedAt = System.currentTimeMillis(),
                            transcript = captureResult.transcript,
                            localeTag = engagedSessionLocaleTag,
                            segmentCount = 1,
                            wavPath = wavPath.ifBlank { null },
                            strategy = "engaged_local_transcribe_fallback",
                        )
                        withContext(Dispatchers.Main) {
                            engagedSessionStatus = persisted.detail
                            if (persisted.transcript.isNotBlank()) {
                                engagedSessionLatestSavedTranscript = persisted.transcript
                            }
                            state.refreshEngagedSessions()
                            state.refreshContextTimeline()
                            state.refreshAudioClips()
                            state.refreshAssistantSessions()
                            refreshDailyDiary(force = true)
                        }
                    }
                    return@withContext
                }

                engagedSessionStopInFlight = false
                engagedSessionStatus = captureResult.detail
                state.refreshAudioClips()
            }
        }
    }

    fun stopEngagedSession() {
        if (!engagedSessionRunning || engagedSessionCloudTranscribing) return
        val now = System.currentTimeMillis()
        if (now < engagedSessionToggleCooldownUntil) return
        engagedSessionToggleCooldownUntil = now + 420L
        engagedSessionStopInFlight = true
        engagedSessionListening = false
        engagedSessionStatus = "Stopping recording... saving WAV."
        engagedSessionStopSignal.set(true)
        scope.launch {
            delay(3_500L)
            if (engagedSessionStopInFlight && engagedSessionRunning) {
                engagedSessionCaptureJob?.cancel()
                engagedSessionCaptureJob = null
                engagedSessionRunning = false
                engagedSessionStopInFlight = false
                engagedSessionStatus = "Stop timeout: recording was cancelled. Tap Engage to retry."
            }
        }
    }

    fun toggleEngagedSession() {
        if (engagedSessionCloudTranscribing) {
            engagedSessionStatus = "Cloud transcription in progress. Please wait..."
            return
        }
        if (engagedSessionRunning) {
            stopEngagedSession()
        } else {
            startEngagedSession()
        }
    }

    LaunchedEffect(activeTab) {
        when (activeTab) {
            AppTab.CONTEXTS -> {
                state.refreshAudioClips()
                state.refreshEngagedSessions()
                state.refreshContextLogs()
                state.refreshContextTimeline()
                if (
                    state.contextTimeline.isNotEmpty() &&
                    state.contextInsightStatusMessage.startsWith("No context insight")
                ) {
                    generateContextInsight()
                }
            }

            AppTab.ASSISTANT -> {
                state.refreshContextTimeline()
                state.refreshAssistantSessions()
                refreshDailyDiary(force = false)
                if (
                    state.contextTimeline.isNotEmpty() &&
                    state.assistantBriefStatusMessage.startsWith("No proactive assistant brief")
                ) {
                    generateAssistantBrief()
                }
                if (state.assistantSessionRows.isEmpty() && state.contextTimeline.isNotEmpty()) {
                    generateAssistantSessions()
                }
            }

            else -> Unit
        }
    }

    LaunchedEffect(activeTab) {
        if (activeTab != AppTab.ASSISTANT) return@LaunchedEffect
        while (true) {
            state.refreshAssistantSessions()
            delay(ASSISTANT_SESSION_UI_REFRESH_MS)
        }
    }

    LaunchedEffect(activeTab) {
        if (activeTab != AppTab.ASSISTANT) return@LaunchedEffect
        while (true) {
            delay(ASSISTANT_DIARY_REFRESH_MS)
            refreshDailyDiary(force = false)
        }
    }

    fun submitHitlDecision(plan: ActionPlanPayload, step: ActionStepPayload, approved: Boolean) {
        if (isGlobalLocked()) {
            state.markStepExecution(step.stepId, lockBlockedMessage("HITL submit"))
            markLockBlocked("HITL submit")
            return
        }
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
        if (isGlobalLocked()) {
            state.markStepExecution(step.stepId, lockBlockedMessage("action execution"))
            markLockBlocked("action execution")
            return
        }
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

    fun generateDailyFocusNow() {
        if (dailyFocusGenerating) return
        if (isGlobalLocked()) {
            markLockBlocked("daily focus generation")
            state.updateQueueStatusMessage("Global lock enabled: daily focus generation blocked.")
            return
        }
        dailyFocusGenerating = true
        state.updateQueueStatusMessage("Generating today's Top3 focus from last 48h context...")
        scope.launch(Dispatchers.IO) {
            val generated = kotlin.runCatching {
                DailyFocusTop3AutoRunner.runNow(
                    context = context.applicationContext,
                    replaceExistingForDay = true,
                )
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                dailyFocusGenerating = false
                state.refreshQueue()
                state.refreshContextTimeline()
                state.refreshModelInteractions()
                state.updateQueueStatusMessage(if (generated) {
                    "Daily Focus Top3 generated and queued."
                } else {
                    "Daily Focus Top3 not generated (insufficient context yet)."
                })
            }
        }
    }

    fun authorizeConnector(connectorId: String) {
        if (isGlobalLocked()) {
            markLockBlocked("connector authorize")
            return
        }
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
        if (isGlobalLocked()) {
            markLockBlocked("connector disconnect")
            return
        }
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
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (activeTab) {
            AppTab.PERMISSIONS -> {
                item {
                    SummaryCard(
                        state = state,
                        onToggleMaster = { state.toggleMaster(it) },
                        onToggleGlobalLock = { enabled -> state.persistGlobalLockEnabled(enabled) },
                        onGrantAll = ::runGrantAllWizard,
                        onOpenNextSettings = {
                            state.firstPendingSettingsPermission()?.let { openSettings(it) }
                        },
                        onCheckBackend = {
                            if (isGlobalLocked()) {
                                markLockBlocked("backend ping")
                            } else {
                                state.setOrchestratorHealth(null)
                                scope.launch(Dispatchers.IO) {
                                    val healthy = mobileSyncHealthGateway.health().getOrNull() == true
                                    withContext(Dispatchers.Main) {
                                        state.setOrchestratorHealth(healthy)
                                    }
                                }
                            }
                        },
                        onStartCollection = {
                            ProactiveCollectionService.start(context)
                            state.persistCollectionEnabled(true)
                            state.setPlanPreview("Starting collection service. Android should keep an ongoing notification visible while foreground capture is active.")
                        },
                        onStopCollection = {
                            ProactiveCollectionService.stop(context)
                            state.persistCollectionEnabled(false)
                            state.setPlanPreview("Stopping collection service. The ongoing notification should disappear once Android finishes shutting it down.")
                        },
                        onSyncNow = {
                            if (!manualSyncInFlight) {
                                manualSyncInFlight = true
                                val queuedBefore = state.unsyncedEvents
                                state.setPlanPreview(
                                    if (queuedBefore > 0) {
                                        "Sync requested for $queuedBefore queued uploads..."
                                    } else {
                                        "Sync requested. Queue is already empty, checking backend..."
                                    },
                                )
                                SyncScheduler.enqueueImmediate(context)
                                scope.launch(Dispatchers.IO) {
                                    val queuedAfter = awaitManualSyncResult(queuedBefore)
                                    withContext(Dispatchers.Main) {
                                        manualSyncInFlight = false
                                        state.refreshGrantStates()
                                        state.setPlanPreview(
                                            manualSyncResultMessage(
                                                queuedBefore = queuedBefore,
                                                queuedAfter = queuedAfter,
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        onOpenSyncConfig = {
                            mobileSyncBaseUrl = state.mobileApiBaseUrl
                            showMobileSyncConfigDialog = true
                        },
                        onFetchPlan = ::fetchPlanWithEdgeInference,
                        syncInFlight = manualSyncInFlight,
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
                            val activeProfile = EdgeModelProfile.fromId(state.edgeModelId)
                            modelConfigEnabled = state.localModelEnabled
                            modelConfigActiveModelId = activeProfile.id
                            modelConfigPathActive = state.localModelPathFor(activeProfile)
                            modelConfigOpenAiKey = state.openAiApiKey
                            showModelConfigDialog = true
                        },
                        selectedDownloadProfile = EdgeModelProfile.fromId(selectedModelDownloadProfile),
                        modelDownloadInFlight = modelDownloadInFlight,
                        onDownloadTargetSelected = { profile ->
                            selectedModelDownloadProfile = profile.id
                        },
                        onDownloadModel = { downloadSelectedModelToPrivateStorage() },
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
                        cloudTranscribeRunning = cloudTranscribeRunning,
                        onRefresh = { state.refreshAudioClips() },
                        onReplayLatest = { replayLatestWavClips() },
                        onClear = {
                            stopAudioClipPlayback()
                            state.clearAudioClips()
                        },
                        onReplaySingle = { path -> replaySingleWavClip(path) },
                        onCloudTranscribeSingle = { path -> cloudTranscribeSingleWavClip(path) },
                        onPlayOrStop = { path -> playAudioClip(path) },
                    )
                }

                item {
                    EngagedSessionContextCard(
                        state = state,
                        onRefresh = { state.refreshEngagedSessions() },
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
                            state.refreshEngagedSessions()
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
                    EngagedSessionControlCard(
                        running = engagedSessionRunning,
                        listening = engagedSessionListening,
                        stopping = engagedSessionStopInFlight,
                        transcribing = engagedSessionCloudTranscribing,
                        status = engagedSessionStatus,
                        latestTranscript = engagedSessionLatestSavedTranscript,
                        onToggle = ::toggleEngagedSession,
                    )
                }

                item {
                    ManualSpeechIntakeCard(
                        running = manualSpeechHoldActive,
                        status = manualSpeechIntakeStatus,
                        latestTranscript = manualSpeechLatestTranscript,
                        onPressStart = ::startManualSpeechIntakeHold,
                        onPressEnd = ::stopManualSpeechIntakeHold,
                    )
                }

                item {
                    AssistantSessionTableCard(
                        state = state,
                        onGenerate = ::generateAssistantSessions,
                        onOpenQuickAction = ::openQuickActionInApp,
                    )
                }

                item {
                    AssistantBriefCard(
                        state = state,
                        onGenerate = ::generateAssistantBrief,
                    )
                }

                item {
                    AssistantDiaryListCard(
                        rows = fourHourDiaryRows,
                        status = fourHourDiaryStatus,
                        onRefresh = { refreshDailyDiary(force = true, userInitiated = true) },
                    )
                }

                item {
                    ExecutionQueueCard(
                        state = state,
                        onRefresh = { state.refreshQueue() },
                        onRunNow = { state.triggerQueueExecutionNow() },
                        onGenerateDailyFocus = ::generateDailyFocusNow,
                        generatingDailyFocus = dailyFocusGenerating,
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
            activeModel = EdgeModelProfile.fromId(modelConfigActiveModelId),
            activePath = modelConfigPathActive,
            onEnabledChange = { modelConfigEnabled = it },
            onActivePathChange = { modelConfigPathActive = it },
            openAiApiKey = modelConfigOpenAiKey,
            onOpenAiApiKeyChange = { modelConfigOpenAiKey = it },
            onDismiss = { showModelConfigDialog = false },
            onSave = {
                val activeModel = EdgeModelProfile.fromId(modelConfigActiveModelId)
                val merged = state.localModelPathMap.toMutableMap().apply {
                    this[activeModel.id] = modelConfigPathActive.trim()
                }
                state.persistLocalModelConfig(
                    enabled = modelConfigEnabled,
                    modelPathMap = merged,
                    backendId = LocalModelBackend.LITERT_LM.id,
                    ggufPath2B = state.localGgufPath2B,
                    ggufPath4B = state.localGgufPath4B,
                    llamaContextSize = state.localLlamaContextSize,
                    llamaThreads = state.localLlamaThreads,
                )
                state.persistOpenAiApiKey(modelConfigOpenAiKey.trim())
                showModelConfigDialog = false
                state.setPlanPreview("Local model config updated")
            },
        )
    }

    if (showMobileSyncConfigDialog) {
        MobileSyncConfigDialog(
            baseUrl = mobileSyncBaseUrl,
            deviceId = state.deviceId,
            onBaseUrlChange = { mobileSyncBaseUrl = it },
            onDismiss = { showMobileSyncConfigDialog = false },
            onSave = {
                state.persistMobileSyncConfig(baseUrl = mobileSyncBaseUrl.trim())
                showMobileSyncConfigDialog = false
            },
        )
    }

    if (inAppSearchAction != null && inAppSearchUrl.isNotBlank()) {
        InAppAiSearchDialog(
            title = inAppSearchAction?.label.orEmpty().ifBlank { "AI Search" },
            url = inAppSearchUrl,
            aiMode = inAppSearchAiMode,
            onDismiss = {
                inAppSearchAction = null
                inAppSearchUrl = ""
                inAppSearchAiMode = false
            },
        )
    }
}

@Composable
private fun SummaryCard(
    state: PermissionCommandCenterState,
    onToggleMaster: (Boolean) -> Unit,
    onToggleGlobalLock: (Boolean) -> Unit,
    onGrantAll: () -> Unit,
    onOpenNextSettings: () -> Unit,
    onCheckBackend: () -> Unit,
    onStartCollection: () -> Unit,
    onStopCollection: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenSyncConfig: () -> Unit,
    onFetchPlan: () -> Unit,
    syncInFlight: Boolean = false,
) {
    val context = LocalContext.current
    val heroChrome = panelChrome(PanelTone.Hero)
    val collectionServiceUi = collectionServiceStatusUi(state.collectionEnabled)
    val manualSyncUi = manualSyncStatusUi(syncInFlight)
    val primaryActions = listOf(
        CompactActionButtonSpec(label = "Grant All Wizard", onClick = onGrantAll, outlined = false),
        CompactActionButtonSpec(label = "Next Settings", onClick = onOpenNextSettings),
    )
    val secondaryActions = listOf(
        CompactActionButtonSpec(label = "Ping Backend", onClick = onCheckBackend),
        CompactActionButtonSpec(label = manualSyncUi.actionLabel, onClick = onSyncNow, enabled = manualSyncUi.actionEnabled),
        CompactActionButtonSpec(label = "Sync Config", onClick = onOpenSyncConfig),
        CompactActionButtonSpec(label = "Refresh Plan", onClick = onFetchPlan),
    )
    val serviceActions = listOf(
        CompactActionButtonSpec(
            label = collectionServiceUi.startActionLabel,
            onClick = onStartCollection,
            outlined = false,
            enabled = collectionServiceUi.startActionEnabled,
        ),
        CompactActionButtonSpec(
            label = collectionServiceUi.stopActionLabel,
            onClick = onStopCollection,
            enabled = collectionServiceUi.stopActionEnabled,
        ),
    )
    CommandCenterPanel(tone = PanelTone.Hero) {
        SectionEyebrow("Permissions & Sync", tone = PanelTone.Hero)
        PanelHeader(
            title = "Extreme Mode",
            subtitle = "Readiness ${state.readinessScore}% with the core collection and sync controls.",
            tone = PanelTone.Hero,
            action = {
                Switch(
                    checked = state.masterEnabled,
                    onCheckedChange = onToggleMaster,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Slate900,
                        checkedTrackColor = Gold500,
                        checkedBorderColor = Gold500,
                        uncheckedThumbColor = Gold200,
                        uncheckedTrackColor = Slate800,
                        uncheckedBorderColor = Gold500.copy(alpha = 0.7f),
                    ),
                )
            },
        )

        SignalPillRow(
            items = listOf(
                "Plugins ${state.plugins.count { it.enabled }}/${state.plugins.size}" to SignalTone.Info,
                "Collection ${collectionServiceUi.statusLabel}" to collectionServiceUi.statusTone,
                "Backend ${state.orchestratorHealthLabel}" to when (state.orchestratorHealthLabel.lowercase(Locale.US)) {
                    "healthy" -> SignalTone.Success
                    "unknown" -> SignalTone.Neutral
                    else -> SignalTone.Warning
                },
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val lockOn = state.globalLockEnabled
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = heroChrome.subtleColor)) {
                        append("Global Lock: ")
                    }
                    withStyle(style = SpanStyle(color = if (lockOn) Rose600 else Emerald500)) {
                        append(if (lockOn) "LOCKED" else "UNLOCKED")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { onToggleGlobalLock(!lockOn) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold500,
                    contentColor = Slate900,
                ),
            ) {
                Text(if (lockOn) "LOCK ON" else "LOCK OFF")
            }
        }

        InfoSurface(tone = PanelTone.Hero) {
            SignalValueRow(label = "Queued uploads", value = state.unsyncedEvents.toString(), toneContainer = PanelTone.Hero, tone = SignalTone.Info)
            SignalValueRow(
                label = "Collection service",
                value = collectionServiceUi.statusLabel,
                toneContainer = PanelTone.Hero,
                tone = collectionServiceUi.statusTone,
            )
            SignalValueRow(label = "Sync mode", value = "Base URL", toneContainer = PanelTone.Hero, tone = SignalTone.Success)
            Text(
                text = "Mobile Sync API: ${MobileSyncConfig.baseUrl(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = heroChrome.subtleColor,
            )
            Text(
                text = "Legacy Orchestrator: ${OrchestratorConfig.baseUrl()}",
                style = MaterialTheme.typography.bodySmall,
                color = heroChrome.subtleColor,
            )
            Text(
                text = collectionServiceUi.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.collectionEnabled) Emerald500 else heroChrome.subtleColor,
            )
            Text(
                text = state.lastPlanPreview,
                style = MaterialTheme.typography.bodySmall,
                color = heroChrome.subtleColor,
            )
        }

        CompactActionButtonGrid(actions = primaryActions, surfaceTone = ActionSurfaceTone.Dark)
        CompactActionButtonGrid(actions = secondaryActions, surfaceTone = ActionSurfaceTone.Dark)
        CompactActionButtonGrid(actions = serviceActions, surfaceTone = ActionSurfaceTone.Dark)
    }
}

@Composable
private fun ModelManagementCard(
    state: PermissionCommandCenterState,
    onModelSelected: (String) -> Unit,
    onOpenInferenceTest: () -> Unit,
    onOpenModelConfig: () -> Unit,
    selectedDownloadProfile: EdgeModelProfile,
    modelDownloadInFlight: Boolean,
    onDownloadTargetSelected: (EdgeModelProfile) -> Unit,
    onDownloadModel: () -> Unit,
    onRefreshModelCalls: () -> Unit,
) {
    val selectedModel = EdgeModelProfile.fromId(state.edgeModelId)
    val activePath = state.localModelPathFor(selectedModel)
    val selectedDownloadInstalled = state.localModelInstalled(selectedDownloadProfile)
    val installedModels = state.edgeModelOptions.filter { state.localModelInstalled(it) }
    var downloadTargetExpanded by remember { mutableStateOf(false) }
    var inUseModelExpanded by remember { mutableStateOf(false) }
    val progressFraction = when {
        selectedDownloadInstalled -> 1f
        modelDownloadInFlight && state.modelDownloadProgress >= 0 -> state.modelDownloadProgress.coerceIn(0, 100) / 100f
        modelDownloadInFlight -> 0.12f
        else -> 0f
    }

    CommandCenterPanel {
        SectionEyebrow("Models")
        PanelHeader(
            title = "LiteRT-LM Model Management",
            subtitle = "Download, switch, and run on-device models with per-model storage + inference logs.",
        )

        SignalPillRow(
            items = listOf(
                LocalModelBackend.fromId(state.localModelBackendId).label to SignalTone.Info,
                "Native ${if (state.localModelEnabled) "ON" else "OFF"}" to if (state.localModelEnabled) SignalTone.Success else SignalTone.Warning,
                "Calls ${state.modelInteractions.size}" to SignalTone.Neutral,
                "Cloud STT ${if (state.openAiApiKey.isBlank()) "missing" else "configured"}" to if (state.openAiApiKey.isBlank()) SignalTone.Warning else SignalTone.Success,
            ),
        )

        InfoSurface {
            Text(
                text = "${selectedModel.label}: ${selectedModel.description}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Current in-use local model: ${selectedModel.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Active model file: ${activePath.ifBlank { "<unset>" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Native status: ${state.latestNativeModelStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.latestNativeModelStatus.startsWith("Native model used")) Emerald600 else Amber600,
            )
            Text(
                text = "Model storage: ${state.modelStorageDirPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CommandCenterInsetPanel {
            Text(
                text = "Model Hub Download",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Download LiteRT-LM models into app private storage and activate the selected profile immediately after success.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Available model to download",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                DropdownSelectorButton(
                    text = "${selectedDownloadProfile.label} (${selectedDownloadProfile.approxSizeLabel})",
                    onClick = { downloadTargetExpanded = true },
                )
                DropdownMenu(
                    expanded = downloadTargetExpanded,
                    onDismissRequest = { downloadTargetExpanded = false },
                ) {
                    state.edgeModelOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${option.label} (${option.approxSizeLabel})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                onDownloadTargetSelected(option)
                                downloadTargetExpanded = false
                            },
                        )
                    }
                }
            }
            if (selectedDownloadInstalled) {
                Text(
                    text = "INSTALLED",
                    style = MaterialTheme.typography.labelMedium,
                    color = Emerald600,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Emerald500.copy(alpha = 0.14f))
                        .border(width = 1.dp, color = Emerald500.copy(alpha = 0.45f), shape = RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            Text(
                text = "Current in use local model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (installedModels.isEmpty()) {
                Text(
                    text = "No installed models yet. Download one model first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber600,
                )
            } else {
                Box {
                    DropdownSelectorButton(
                        text = "${selectedModel.label} (in use)",
                        onClick = { inUseModelExpanded = true },
                    )
                    DropdownMenu(
                        expanded = inUseModelExpanded,
                        onDismissRequest = { inUseModelExpanded = false },
                    ) {
                        installedModels.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    onModelSelected(option.id)
                                    inUseModelExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = "Download status: ${state.modelDownloadStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Download progress: ${if (state.modelDownloadProgress >= 0) "${state.modelDownloadProgress}%" else "<idle>"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onDownloadModel,
                enabled = !selectedDownloadInstalled && !modelDownloadInFlight,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(Slate200),
                    )
                    if (progressFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(Emerald500),
                        )
                    }
                    Text(
                        text = when {
                            selectedDownloadInstalled -> "INSTALLED"
                            modelDownloadInFlight && state.modelDownloadProgress >= 0 ->
                                "DOWNLOADING ${state.modelDownloadProgress.coerceIn(0, 100)}%"
                            modelDownloadInFlight -> "DOWNLOADING..."
                            else -> "DOWNLOAD"
                        },
                        fontWeight = FontWeight.Bold,
                        color = if (progressFraction >= 0.18f) Color.White else Slate700,
                    )
                }
            }
        }

        CompactActionButtonGrid(
            actions = listOf(
                CompactActionButtonSpec(label = "Inference Test", onClick = onOpenInferenceTest, outlined = false),
                CompactActionButtonSpec(label = "Model Config", onClick = onOpenModelConfig),
                CompactActionButtonSpec(label = "Refresh Calls", onClick = onRefreshModelCalls),
            ),
        )
    }
}

@Composable
private fun DropdownSelectorButton(
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "▼",
                color = Slate500,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ModelInteractionJournalCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
) {
    val journalItems = state.modelInteractions.take(MODEL_JOURNAL_MAX_ITEMS)
    CommandCenterPanel {
        PanelHeader(
            title = "Local Model Prompt/Response Journal",
            subtitle = "Review recent prompts, outputs, and trigger sources.",
            action = {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            },
        )

        Text(
            text = state.modelInteractionStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.modelInteractions.isEmpty()) {
            Text(
                text = "No model call records yet. Run Inference Test or generate assistant/context insights.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Showing ${journalItems.size} of ${state.modelInteractions.size} calls (newest first).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MODEL_JOURNAL_SCROLL_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                journalItems.forEach { item ->
                    CommandCenterInsetPanel {
                        Text(
                            text = "${item.timestampLabel} | ${item.trigger} | ${item.modelLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Status: ${item.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Prompt:\n${item.prompt.ifBlank { "<empty>" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700,
                        )
                        Text(
                            text = "Response:\n${item.response.ifBlank { "<empty>" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSessionTableCard(
    state: PermissionCommandCenterState,
    onGenerate: () -> Unit,
    onOpenQuickAction: (AssistantQuickAction) -> Unit,
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
                    text = "15-Minute Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onGenerate) {
                    Text("Generate")
                }
            }

            Text(
                text = state.assistantSessionStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
            )

            if (state.assistantSessionRows.isEmpty()) {
                Text(
                    text = "No session rows yet. Generate to analyze the latest 15-minute context windows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                )
            } else {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    SessionTableHeaderRow()
                    state.assistantSessionRows.forEach { row ->
                        SessionTableDataRow(
                            row = row,
                            onOpenQuickAction = onOpenQuickAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngagedSessionControlCard(
    running: Boolean,
    listening: Boolean,
    stopping: Boolean,
    transcribing: Boolean,
    status: String,
    latestTranscript: String,
    onToggle: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "engagedSessionBreathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "engagedSessionBreathingScale",
    )
    val targetScale = if (running) breathingScale else 1f
    val buttonBg by animateColorAsState(
        targetValue = if (running) Emerald500 else Color.White,
        label = "engagedSessionButtonBg",
    )
    val buttonBorder by animateColorAsState(
        targetValue = when {
            running -> Emerald600
            else -> Sky700
        },
        label = "engagedSessionButtonBorder",
    )
    val buttonTextColor by animateColorAsState(
        targetValue = if (running) Color.White else Sky700,
        label = "engagedSessionButtonTextColor",
    )
    val displayTranscript = latestTranscript.trim()

    CommandCenterPanel {
        PanelHeader(
            title = "Engaged Session",
            subtitle = "Hands-free capture mode for continuous in-person context.",
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onToggle,
                enabled = !stopping && !transcribing,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBg,
                    contentColor = buttonTextColor,
                ),
                modifier = Modifier
                    .size(170.dp)
                    .graphicsLayer {
                        scaleX = targetScale
                        scaleY = targetScale
                    }
                    .border(width = 3.dp, color = buttonBorder, shape = CircleShape),
            ) {
                Text(
                    text = when {
                        transcribing -> "TRANSCRIBING"
                        stopping -> "STOPPING"
                        running && listening -> "ENGAGED\nRECORDING"
                        running -> "ENGAGED\nRECORDING"
                        else -> "Engage"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600,
                modifier = Modifier.fillMaxWidth(),
            )

            if (displayTranscript.isNotBlank()) {
                Text(
                    text = "Latest Engaged Transcript",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = displayTranscript,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate900,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(16.dp))
                        .background(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ManualSpeechIntakeCard(
    running: Boolean,
    status: String,
    latestTranscript: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val canStartState by rememberUpdatedState(!running)
    val onPressStartState by rememberUpdatedState(onPressStart)
    val onPressEndState by rememberUpdatedState(onPressEnd)
    val infiniteTransition = rememberInfiniteTransition(label = "manualSpeechBreathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "manualSpeechBreathingScale",
    )
    val targetScale = if (running) breathingScale else 1f
    val buttonBg by animateColorAsState(
        targetValue = if (running) Emerald500 else Color.White,
        label = "manualSpeechButtonBg",
    )
    val buttonBorder by animateColorAsState(
        targetValue = when {
            running -> Emerald600
            else -> Sky700
        },
        label = "manualSpeechButtonBorder",
    )
    val buttonTextColor by animateColorAsState(
        targetValue = when {
            running -> Color.White
            else -> Sky700
        },
        label = "manualSpeechButtonTextColor",
    )

    CommandCenterPanel {
        PanelHeader(
            title = "Manual Speech Intake",
            subtitle = "Press and hold to capture a focused voice note.",
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(168.dp)
                    .graphicsLayer {
                        scaleX = targetScale
                        scaleY = targetScale
                    }
                    .background(color = buttonBg, shape = CircleShape)
                    .border(width = 3.dp, color = buttonBorder, shape = CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!canStartState) return@awaitEachGesture
                            down.consume()
                            onPressStartState()
                            try {
                                var released = false
                                while (!released) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    if (change == null) continue
                                    change.consume()
                                    released = !change.pressed
                                }
                            } finally {
                                onPressEndState()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        running -> "LISTENING\nRELEASE TO STOP"
                        else -> "Anything"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = buttonTextColor,
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600,
                modifier = Modifier.fillMaxWidth(),
            )
            if (latestTranscript.isNotBlank()) {
                Text(
                    text = "Latest Intake Transcript",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = latestTranscript,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate900,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionTableHeaderRow() {
    Row(
        modifier = Modifier
            .background(Slate200)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionTableCell("Session", 120.dp, header = true)
        SessionTableCell("Events", 70.dp, header = true)
        SessionTableCell("Sparkling", 140.dp, header = true)
        SessionTableCell("Speech", 210.dp, header = true)
        SessionTableCell("Position", 165.dp, header = true)
        SessionTableCell("Indoor/Outdoor", 120.dp, header = true)
        SessionTableCell("Location", 170.dp, header = true)
        SessionTableCell("Calendar", 180.dp, header = true)
        SessionTableCell("Local Scenario", 260.dp, header = true)
        SessionTableCell("Local Action Plan", 260.dp, header = true)
        SessionTableCell("Cloud Scenario", 260.dp, header = true)
        SessionTableCell("Cloud Action Plan", 280.dp, header = true)
        SessionTableCell("Cloud Status", 150.dp, header = true)
        SessionTableCell("Quick Actions", 250.dp, header = true)
        SessionTableCell("Model", 170.dp, header = true)
        SessionTableCell("Cloud Model", 140.dp, header = true)
    }
}

@Composable
private fun SessionTableDataRow(
    row: AssistantSessionRowUiState,
    onOpenQuickAction: (AssistantQuickAction) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SessionTableCell(row.sessionLabel, 120.dp)
            SessionTableCell(row.eventCount.toString(), 70.dp)
            SessionTableCell(
                text = if (row.sparklingSession) {
                    buildString {
                        append("YES (${row.sparklingSignalCount})")
                        if (row.sparklingTriggers.isNotBlank() && row.sparklingTriggers != "-") {
                            append("\n")
                            append(row.sparklingTriggers)
                        }
                    }
                } else {
                    "-"
                },
                width = 140.dp,
                maxLines = 4,
            )
            SessionTableCell(
                text = row.speechSummary,
                width = 210.dp,
                maxLines = Int.MAX_VALUE,
                scrollable = true,
                scrollMaxHeight = 200.dp,
            )
            SessionTableCell(row.positionSummary, 165.dp)
            SessionTableCell(row.indoorOutdoor, 120.dp)
            SessionTableCell(row.locationLabel, 170.dp)
            SessionTableCell(row.calendarSummary, 180.dp)
            SessionTableCell(row.guessedUserScenario, 260.dp, maxLines = 8)
            SessionTableCell(row.actionPlan, 260.dp, maxLines = 8)
            SessionTableCell(row.cloudGuessedUserScenario.ifBlank { "-" }, 260.dp, maxLines = 8)
            SessionTableCell(row.cloudActionPlan.ifBlank { "-" }, 280.dp, maxLines = 8)
            SessionTableCell(row.cloudStatus.ifBlank { "-" }, 150.dp, maxLines = 3)
            SessionTableQuickActionsCell(
                actions = row.quickActions,
                width = 250.dp,
                onOpen = onOpenQuickAction,
            )
            SessionTableCell(row.modelLabel, 170.dp)
            SessionTableCell(row.cloudModelLabel.ifBlank { "-" }, 140.dp, maxLines = 3)
        }
        HorizontalDivider(color = Slate200)
    }
}

@Composable
private fun SessionTableQuickActionsCell(
    actions: List<AssistantQuickAction>,
    width: Dp,
    onOpen: (AssistantQuickAction) -> Unit,
) {
    if (actions.isEmpty()) {
        SessionTableCell("-", width = width)
        return
    }

    Column(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions.take(4).forEach { action ->
            OutlinedButton(
                onClick = { onOpen(action) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = 4.dp,
                ),
            ) {
                Text(
                    text = compactQuickActionButtonLabel(action.label),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                    ),
                    softWrap = false,
                )
            }
        }
    }
}

private fun compactQuickActionButtonLabel(raw: String): String {
    if (raw.isBlank()) return "Open"
    val cleaned = raw
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?i)\\b(ai|immediately|current|context|device|please|kindly)\\b"), "")
        .replace("立刻", "")
        .replace("立即", "")
        .replace("当前", "")
        .replace("请", "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isBlank()) return "Open"
    val hasChinese = cleaned.any { it.code in 0x4E00..0x9FFF }
    return if (hasChinese) {
        cleaned
            .replace(Regex("[，。！？、；：]+"), "")
            .take(12)
            .trim()
    } else {
        val compact = cleaned
            .split(' ')
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString(" ")
            .trim()
        compact.take(24).trim()
    }.ifBlank { "Open" }
}

@Composable
private fun SessionTableCell(
    text: String,
    width: Dp,
    header: Boolean = false,
    maxLines: Int = 4,
    scrollable: Boolean = false,
    scrollMaxHeight: Dp = 180.dp,
) {
    val resolvedText = text.ifBlank { "-" }
    if (scrollable && !header) {
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .width(width)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .heightIn(min = 84.dp, max = scrollMaxHeight)
                .background(
                    color = Slate50,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = resolvedText,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Normal,
                color = Slate700,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        }
        return
    }

    Text(
        text = resolvedText,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (header) Slate900 else Slate700,
        maxLines = if (header) 1 else maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private data class InAppQuickActionUrl(
    val url: String,
    val aiMode: Boolean,
)

private fun resolveInAppQuickActionUrl(rawUrl: String): InAppQuickActionUrl {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return InAppQuickActionUrl(url = "https://www.google.com", aiMode = false)
    val parsed = kotlin.runCatching { Uri.parse(trimmed) }.getOrNull()
        ?: return InAppQuickActionUrl(url = trimmed, aiMode = false)

    val host = parsed.host?.lowercase(Locale.US).orEmpty()
    val path = parsed.path.orEmpty()
    val isGoogle = host.contains("google.")

    if (isGoogle && path.startsWith("/search")) {
        val query = parsed.getQueryParameter("q").orEmpty().trim()
        if (query.isNotBlank()) {
            return InAppQuickActionUrl(
                url = "https://www.google.com/search?udm=50&q=${encodeQuery(query)}",
                aiMode = true,
            )
        }
    }

    if (isGoogle && path.startsWith("/maps/search")) {
        val query = parsed.getQueryParameter("query").orEmpty().trim()
        if (query.isNotBlank()) {
            return InAppQuickActionUrl(
                url = "https://www.google.com/search?udm=50&q=${encodeQuery(query)}",
                aiMode = true,
            )
        }
    }

    if (isGoogle && path.contains("/travel/", ignoreCase = true)) {
        val q = listOf(
            parsed.getQueryParameter("q"),
            parsed.getQueryParameter("destination"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        if (q.isNotBlank()) {
            return InAppQuickActionUrl(
                url = "https://www.google.com/search?udm=50&q=${encodeQuery(q)}",
                aiMode = true,
            )
        }
    }

    return InAppQuickActionUrl(url = trimmed, aiMode = false)
}

private fun encodeQuery(input: String): String {
    return URLEncoder.encode(input, Charsets.UTF_8.name())
}

@Composable
private fun InAppAiSearchDialog(
    title: String,
    url: String,
    aiMode: Boolean,
    onDismiss: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.ifBlank { "AI Search" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (aiMode) "AI Mode Search (In-App)" else "In-App Preview",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (aiMode) Sky700 else Slate500,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { webView?.reload() }) {
                            Text("Reload")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }

                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.userAgentString = "${settings.userAgentString} ProactiveAI-InAppWebView/1.0"
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {}
                            loadUrl(url)
                            webView = this
                        }
                    },
                    update = { view ->
                        if (view.url.isNullOrBlank()) {
                            view.loadUrl(url)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 420.dp, max = 760.dp),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            kotlin.runCatching { webView?.stopLoading() }
            kotlin.runCatching { webView?.destroy() }
            webView = null
        }
    }
}

@Composable
private fun AssistantDiaryListCard(
    rows: List<FourHourDiaryRow>,
    status: String,
    onRefresh: () -> Unit,
) {
    CommandCenterPanel {
        PanelHeader(
            title = "Daily Assistant Diary",
            subtitle = "Four-hour windows with summaries, help opportunities, and self TODOs.",
            action = {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            },
        )

            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
            )

            if (rows.isEmpty()) {
                Text(
                    text = "No diary rows yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
            } else {
                val xScrollState = rememberScrollState()
                val yScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ASSISTANT_DIARY_SCROLL_MAX_HEIGHT)
                        .verticalScroll(yScrollState)
                        .horizontalScroll(xScrollState),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SessionTableCell("Window", 130.dp, header = true)
                        SessionTableCell("Summary", 260.dp, header = true)
                        SessionTableCell("Proactive AI Can Help", 260.dp, header = true)
                        SessionTableCell("Self TODO", 240.dp, header = true)
                    }

                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            SessionTableCell(row.windowLabel, 130.dp, maxLines = 3)
                            SessionTableCell(
                                row.summary,
                                260.dp,
                                maxLines = Int.MAX_VALUE,
                                scrollable = true,
                                scrollMaxHeight = 220.dp,
                            )
                            SessionTableCell(
                                row.proactiveHelp,
                                260.dp,
                                maxLines = Int.MAX_VALUE,
                                scrollable = true,
                                scrollMaxHeight = 220.dp,
                            )
                            SessionTableCell(
                                row.selfTodo,
                                240.dp,
                                maxLines = Int.MAX_VALUE,
                                scrollable = true,
                                scrollMaxHeight = 220.dp,
                            )
                        }
                        HorizontalDivider(color = Slate200)
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
    CommandCenterPanel {
        PanelHeader(
            title = "Proactive Understanding Brief",
            subtitle = "A compact synthesis of what the assistant thinks matters right now.",
            action = {
                Button(onClick = onGenerate) {
                    Text("Generate")
                }
            },
        )

        Text(
            text = state.assistantBriefStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoSurface {
            Text(
                text = state.assistantBriefText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.assistantHighlights.isNotEmpty()) {
                Text(
                    text = "Potential help:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.assistantHighlights.forEach { highlight ->
                    Text(
                        text = "- $highlight",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = Slate700,
            )
            CompactActionButtonGrid(
                actions = state.edgeModelOptions.map { option ->
                    CompactActionButtonSpec(
                        label = option.label,
                        onClick = { onModelSelected(option.id) },
                        outlined = option.id != state.edgeModelId,
                    )
                },
            )
            Text(
                text = EdgeModelProfile.fromId(state.edgeModelId).description,
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
            )
            Text(
                text = "Native model: ${if (state.localModelEnabled) "ON" else "OFF"}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
            )
            Text(
                text = "Backend: ${LocalModelBackend.fromId(state.localModelBackendId).label}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
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
                color = Slate500,
            )
            Text(
                text = "Native status: ${state.latestNativeModelStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.latestNativeModelStatus.startsWith("Native model used")) Emerald600 else Amber600,
            )
            Text(
                text = "Model inference raw output:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Slate700,
            )
            Text(
                text = if (state.latestNativeModelOutput.isNotBlank()) {
                    state.latestNativeModelOutput
                } else {
                    "<empty - check Native status above>"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Slate700,
            )
            Text(
                text = "Heuristic summary:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Slate500,
            )
            Text(
                text = state.latestInferenceSummary,
                style = MaterialTheme.typography.bodySmall,
                color = Slate700,
            )
            if (state.intentHints.isNotEmpty()) {
                state.intentHints.forEach { hint ->
                    Text(
                        text = "- ${hint.label} (${(hint.confidence * 100).toInt()}%): ${hint.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600,
                    )
                }
            }
            if (state.suggestedActions.isNotEmpty()) {
                state.suggestedActions.forEach { action ->
                    Text(
                        text = "Suggested: $action",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600,
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Intent Match ${state.metrics.intentMatchRate}% | Helpfulness ${state.metrics.actionHelpfulness}% | Interruption Quality ${state.metrics.interruptionQuality}%",
                style = MaterialTheme.typography.bodySmall,
                color = Slate700,
            )
            Text(
                text = "Suggestions ${state.metrics.suggestionsAccepted}/${state.metrics.suggestionsTotal}, Executions ${state.metrics.executionsSuccessful}/${state.metrics.executionsTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
            )

            CompactActionButtonGrid(
                actions = listOf(
                    CompactActionButtonSpec(label = "Generate Plan", onClick = onGeneratePlan, outlined = false),
                    CompactActionButtonSpec(label = "Inference Test", onClick = onOpenInferenceTest),
                    CompactActionButtonSpec(label = "Model Config", onClick = onOpenModelConfig),
                    CompactActionButtonSpec(label = "Run Auto Steps", onClick = onAutoExecute),
                    CompactActionButtonSpec(label = "Reset Metrics", onClick = onResetMetrics),
                ),
            )

            Text(
                text = state.latestExecutionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Slate700,
            )

            val plan = state.currentPlan
            if (plan == null) {
                Text(
                    text = "No active plan. Generate a plan to start HITL and execution.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate500,
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
                    color = Slate700,
                )
                Text(
                    text = plan.explainWhy,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate700,
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
                    color = Slate500,
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
    activeModel: EdgeModelProfile,
    activePath: String,
    openAiApiKey: String,
    onEnabledChange: (Boolean) -> Unit,
    onActivePathChange: (String) -> Unit,
    onOpenAiApiKeyChange: (String) -> Unit,
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
                    color = Slate500,
                )
                Text(
                    text = "LiteRT-LM model path (.litertlm):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
                TextField(
                    value = activePath,
                    onValueChange = onActivePathChange,
                    label = { Text("${activeModel.label} model path") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Example path: /data/user/0/com.proactiveai.extreme/files/models/${activeModel.defaultLiteRtFileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
                Text(
                    text = "Cloud STT (15-min refine via gpt-4o-transcribe):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
                TextField(
                    value = openAiApiKey,
                    onValueChange = onOpenAiApiKeyChange,
                    label = { Text("OpenAI API key (sk-...)") },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth(),
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
private fun MobileSyncConfigDialog(
    baseUrl: String,
    deviceId: String,
    onBaseUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Mobile Sync Config")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "本地填 ngrok 地址；线上填你自己的后端地址。现在只需要 base URL。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
                TextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("API base URL") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Device ID: $deviceId",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
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
        colors = CardDefaults.cardColors(containerColor = Slate50),
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
                color = Slate500,
            )
            if (step.args.isNotEmpty()) {
                Text(
                    text = "Args keys: ${step.args.keys.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
            }

            if (decision != null) {
                Text(
                    text = "Decision: ${if (decision) "Approved" else "Denied"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (decision) Emerald600 else Rose600,
                )
            }

            if (!executionResult.isNullOrBlank()) {
                Text(
                    text = executionResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate700,
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
    CommandCenterPanel {
        PanelHeader(
            title = "Connector Center",
            subtitle = "Connection health and account bindings for external services.",
            action = {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            },
        )

        Text(
            text = state.connectorStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.connectors.isEmpty()) {
            Text(
                text = "No connector status loaded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ConnectorRow(
    connector: ConnectorUiState,
    onAuthorize: () -> Unit,
    onDisconnect: () -> Unit,
) {
    CommandCenterInsetPanel {
        Text(
            text = connector.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        SignalPill(
            text = if (connector.connected) "Connected" else "Not connected",
            tone = if (connector.connected) SignalTone.Success else SignalTone.Warning,
        )
        connector.accountLabel?.let {
            Text(
                text = "Account: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        connector.lastConnectedLabel?.let {
            Text(
                text = "Last connected: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun EngagedSessionContextCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
) {
    val sessions = state.engagedSessions.take(ENGAGED_SESSION_RENDER_MAX_ITEMS)
    CommandCenterPanel {
        PanelHeader(
            title = "Engaged Sessions",
            subtitle = "Recent long-form captures with location and sync state.",
            action = {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            },
        )

        Text(
            text = state.engagedSessionStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.engagedSessions.isEmpty()) {
            Text(
                text = "No engaged sessions yet. Turn on Engage in Assistant to capture one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Showing ${sessions.size} of ${state.engagedSessions.size} sessions (newest first).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = CONTEXT_CARD_SCROLL_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sessions.forEach { item ->
                    CommandCenterInsetPanel {
                        Text(
                            text = "${item.sessionLabel} | duration=${item.durationLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${item.indoorOutdoor} | ${item.locationLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = item.transcript,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate900,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .verticalScroll(rememberScrollState())
                                .clip(RoundedCornerShape(14.dp))
                                .background(color = MaterialTheme.colorScheme.surface)
                                .padding(10.dp),
                        )
                        SignalPill(
                            text = if (item.synced) "synced" else "pending_sync",
                            tone = if (item.synced) SignalTone.Success else SignalTone.Warning,
                        )
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
    val timelineItems = state.contextTimeline.take(CONTEXT_CARD_MAX_ITEMS)
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
                color = Slate500,
            )

            if (state.contextTimeline.isEmpty()) {
                Text(
                    text = "No context events captured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
            } else {
                Text(
                    text = "Showing ${timelineItems.size} of ${state.contextTimeline.size} events (newest first).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = CONTEXT_CARD_SCROLL_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    timelineItems.forEach { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Slate50),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "${item.timestampLabel} | ${item.source}.${item.category}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate700,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = item.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate600,
                                )
                                Text(
                                    text = "sensitivity=${item.sensitivity} | ${if (item.synced) "synced" else "pending_sync"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate500,
                                )
                            }
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
                color = Slate500,
            )
            Text(
                text = state.contextInsightSummary,
                style = MaterialTheme.typography.bodySmall,
                color = Slate700,
            )

            if (state.contextInsightActions.isNotEmpty()) {
                Text(
                    text = "Potential actions:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Slate700,
                )
                state.contextInsightActions.forEach { action ->
                    Text(
                        text = "- $action",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600,
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
    cloudTranscribeRunning: Boolean,
    onRefresh: () -> Unit,
    onReplayLatest: () -> Unit,
    onClear: () -> Unit,
    onReplaySingle: (String) -> Unit,
    onCloudTranscribeSingle: (String) -> Unit,
    onPlayOrStop: (String) -> Unit,
) {
    val clipItems = state.audioClips.take(CONTEXT_CARD_MAX_ITEMS)
    CommandCenterPanel {
        PanelHeader(
            title = "Audio Clips Debug",
            subtitle = "Recent captured clips, transcription attempts, and replay controls.",
        )
        CompactActionButtonGrid(
            actions = listOf(
                CompactActionButtonSpec(label = "Refresh", onClick = onRefresh),
                CompactActionButtonSpec(
                    label = if (replayRunning) "Re-run..." else "Re-run STT",
                    onClick = onReplayLatest,
                    enabled = !replayRunning,
                ),
                CompactActionButtonSpec(label = "Clear", onClick = onClear),
            ),
        )

        Text(
            text = state.audioClipStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.audioClips.isEmpty()) {
            Text(
                text = "No wav clips yet. Keep service on and speak near the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Showing ${clipItems.size} of ${state.audioClips.size} clips (newest first).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = CONTEXT_CARD_SCROLL_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                clipItems.forEach { clip ->
                    CommandCenterInsetPanel {
                        Text(
                            text = "${clip.capturedAtLabel} | ${clip.fileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "status=${clip.status} | strategy=${clip.strategy} | duration=${clip.durationLabel} | size=${clip.sizeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (clip.reason.isNotBlank()) {
                            Text(
                                text = "reason: ${clip.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (clip.modelStatus.isNotBlank()) {
                            Text(
                                text = "detail: ${clip.modelStatus}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (clip.transcript.isNotBlank()) {
                            Text(
                                text = "transcript: ${clip.transcript}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate700,
                            )
                        }
                        if (clip.stitchedTranscript.isNotBlank()) {
                            Text(
                                text = "full sentence: ${clip.stitchedTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Sky700,
                            )
                        }
                        if (clip.refinedStatus.isNotBlank()) {
                            Text(
                                text = "cloud refine status: ${clip.refinedStatus}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (clip.refinedStatus == "recognized") Emerald600 else Amber600,
                            )
                        }
                        if (clip.refinedTranscript.isNotBlank()) {
                            Text(
                                text = "cloud transcript: ${clip.refinedTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Sky700,
                            )
                        }
                        Text(
                            text = clip.filePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600,
                        )

                        val isPlaying = activeClipPath == clip.filePath
                        CompactActionButtonGrid(
                            actions = listOf(
                                CompactActionButtonSpec(
                                    label = if (isPlaying) "Stop" else "Play",
                                    onClick = { onPlayOrStop(clip.filePath) },
                                    outlined = isPlaying,
                                ),
                                CompactActionButtonSpec(
                                    label = "Re-run",
                                    onClick = { onReplaySingle(clip.filePath) },
                                    enabled = !replayRunning,
                                ),
                                CompactActionButtonSpec(
                                    label = if (cloudTranscribeRunning) "GPT..." else "GPT Transcribe",
                                    onClick = { onCloudTranscribeSingle(clip.filePath) },
                                    enabled = !cloudTranscribeRunning,
                                ),
                            ),
                        )
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
                color = Slate500,
            )

            if (state.contextLogs.isEmpty()) {
                Text(
                    text = "No logs yet. Keep collection service ON for 1+ minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
            } else {
                state.contextLogs.forEach { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Slate50),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${log.timestampLabel} | ${if (log.synced) "synced" else "pending_sync"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate500,
                            )
                            Text(
                                text = log.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate700,
                            )
                            if (log.detail.isNotBlank()) {
                                Text(
                                    text = log.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate600,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
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
    CommandCenterPanel {
        PanelHeader(
            title = "Action History",
            subtitle = "Recent assistant decisions, exports, and execution notes.",
            action = {
                OutlinedButton(onClick = onClear) {
                    Text("Clear")
                }
            },
        )

        CompactActionButtonGrid(
            actions = listOf(
                CompactActionButtonSpec(label = "Filter: ${state.historyFilter.label}", onClick = onCycleFilter),
                CompactActionButtonSpec(label = "Export", onClick = onExport),
            ),
        )
        Text(
            text = state.historyExportLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.actionHistory.isEmpty()) {
            Text(
                text = "No action history yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.actionHistory.forEach { item ->
                Text(
                    text = "${item.timestampLabel} | ${item.eventType} | ${item.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate700,
                )
                if (!item.detail.isNullOrBlank()) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ExecutionQueueCard(
    state: PermissionCommandCenterState,
    onRefresh: () -> Unit,
    onRunNow: () -> Unit,
    onGenerateDailyFocus: () -> Unit,
    generatingDailyFocus: Boolean,
    onRetry: (Long) -> Unit,
    onClearSucceeded: () -> Unit,
) {
    CommandCenterPanel {
        PanelHeader(
            title = "Execution Queue",
            subtitle = "Queued assistant work and retries, optimized for narrow mobile screens.",
            action = {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            },
        )

        Text(
            text = state.queueStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CompactActionButtonGrid(
            actions = listOf(
                CompactActionButtonSpec(label = "Run Now", onClick = onRunNow),
                CompactActionButtonSpec(
                    label = if (generatingDailyFocus) "Generating..." else "Generate Focus Top3",
                    onClick = onGenerateDailyFocus,
                    enabled = !generatingDailyFocus,
                ),
                CompactActionButtonSpec(label = "Clear Done", onClick = onClearSucceeded),
            ),
        )

        if (state.executionQueue.isEmpty()) {
            Text(
                text = "No queued action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val visibleQueueItems = state.executionQueue.take(EXECUTION_QUEUE_RENDER_MAX_ITEMS)
            if (state.executionQueue.size > visibleQueueItems.size) {
                Text(
                    text = "Showing latest ${visibleQueueItems.size} / ${state.executionQueue.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val queueScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = EXECUTION_QUEUE_SCROLL_MAX_HEIGHT)
                    .verticalScroll(queueScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleQueueItems.forEach { item ->
                    CommandCenterInsetPanel {
                        Text(
                            text = item.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        item.detail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate700,
                            )
                        }
                        Text(
                            text = "Status=${item.status} Attempts=${item.attempts} Updated=${item.updatedAtLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.nextRetryLabel?.let {
                            Text(
                                text = "Next retry: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item.lastError?.let {
                            Text(
                                text = "Error: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = Rose600,
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
                        color = Amber600,
                    )
                }
                Switch(checked = plugin.enabled, onCheckedChange = onTogglePlugin)
            }

            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Slate700,
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
                color = Slate500,
            )
            Text(
                text = permission.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = Slate500,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (permission.granted) "GRANTED" else "PENDING",
                style = MaterialTheme.typography.labelMedium,
                color = if (permission.granted) Emerald600 else Amber600,
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

private data class FifteenMinuteSession(
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val events: List<ContextEventPayload>,
    val sparklingSignalCount: Int,
    val sparklingTriggers: List<String>,
    val isSparkling: Boolean,
)

private data class FourHourDiaryRow(
    val windowLabel: String,
    val summary: String,
    val proactiveHelp: String,
    val selfTodo: String,
)

private data class FourHourDiaryWindow(
    val startMs: Long,
    val endMs: Long,
    val allEvents: List<ContextEventPayload>,
    val events: List<ContextEventPayload>,
    val snapshot: SessionContextSnapshot,
)

private data class FourHourDiaryParsed(
    val summary: String,
    val proactiveHelp: String,
    val selfTodo: String,
)

private data class SessionContextSnapshot(
    val speechSummary: String,
    val positionSummary: String,
    val indoorOutdoor: String,
    val locationLabel: String,
    val calendarSummary: String,
    val sparklingSession: Boolean,
    val sparklingSignalCount: Int,
    val sparklingTriggers: String,
)

private data class SessionInferenceParsed(
    val guessedUserScenario: String,
    val actionPlan: String,
)

private data class SessionHeuristicGuess(
    val scenario: String,
    val actionPlan: String,
)

private const val ASSISTANT_SESSION_WINDOW_MS = 15 * 60 * 1000L

private fun buildFourHourDiaryRows(
    context: android.content.Context,
    events: List<ContextEventPayload>,
    maxRows: Int,
    model: EdgeModelProfile,
    runtimeConfig: LocalModelRuntimeConfig,
    onTrace: (EdgeInferenceTrace, Int) -> Unit,
): List<FourHourDiaryRow> {
    if (events.isEmpty()) return emptyList()
    val contextOnly = events.filterNot {
        it.source == "local_model" ||
            it.category == "model_io" ||
            it.category == "assistant_session"
    }
    if (contextOnly.isEmpty()) return emptyList()

    val windows = contextOnly
        .groupBy { event -> startOfLocalDayMs(event.occurredAt) }
        .entries
        .sortedByDescending { it.key }
        .take(maxRows)
        .map { (startMs, groupedEvents) ->
            val ordered = groupedEvents.sortedByDescending { it.occurredAt }
            val signal = selectDiarySignalEvents(ordered).ifEmpty { ordered.take(96) }
            FourHourDiaryWindow(
                startMs = startMs,
                endMs = endOfLocalDayExclusiveMs(startMs),
                allEvents = ordered,
                events = signal,
                snapshot = buildSessionContextSnapshot(
                    context = context,
                    events = ordered,
                    maxSpeechSegments = 96,
                ),
            )
        }

    return windows.map { window ->
        val fallback = buildFourHourFallbackDiary(
            events = window.events,
            snapshot = window.snapshot,
        )
        val windowLabel = formatDailyDiaryRange(window.startMs)
        val prompt = buildFourHourDiaryPrompt(
            windowLabel = windowLabel,
            snapshot = window.snapshot,
            events = window.events,
            allEvents = window.allEvents,
        )
        val trace = OnDeviceInferenceEngine.inferFromPromptWithTrace(
            context = context,
            model = model,
            prompt = prompt,
            runtimeConfig = runtimeConfig,
        )
        onTrace(trace, window.events.size)

        val result = trace.result
        val raw = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary
        val parsed = parseFourHourDiaryOutput(
            output = raw,
            fallback = fallback,
            preferFallback = !result.nativeModelUsed,
        )
        FourHourDiaryRow(
            windowLabel = windowLabel,
            summary = parsed.summary,
            proactiveHelp = parsed.proactiveHelp,
            selfTodo = parsed.selfTodo,
        )
    }
}

private fun buildFourHourDiaryPrompt(
    windowLabel: String,
    snapshot: SessionContextSnapshot,
    events: List<ContextEventPayload>,
    allEvents: List<ContextEventPayload>,
): String {
    val speechEvidence = extractDiarySpeechEvidence(allEvents, maxItems = 256)
    val speechDigest = speechEvidence
        .asSequence()
        .map { sanitizeDiaryNarrativeLine(it) }
        .filter { it.isNotBlank() && !looksLikeTechnicalDiaryLine(it) }
        .distinctBy { it.lowercase(Locale.US) }
        .take(128)
        .joinToString(separator = "\n") { "- $it" }
        .ifBlank { "- No valid speech transcript evidence" }

    val eventsDigest = events
        .asSequence()
        .map { sanitizeDiaryNarrativeLine(it.summary) }
        .filter { it.isNotBlank() && !looksLikeTechnicalDiaryLine(it) }
        .distinctBy { it.lowercase(Locale.US) }
        .take(64)
        .joinToString(separator = "\n") { "- $it" }
        .ifBlank { "- No strong non-technical event evidence yet" }

    return """
        You are an on-device proactive personal assistant.
        Write a DAILY diary entry (00:00-24:00) that summarizes what truly happened to this user.
        Prioritize unique, personal, and human moments over system noise.
        Do not output event counters or pipeline/process logs.
        If evidence is weak, explicitly say uncertainty.
        Tone: natural, personal, reflective, and specific.
        Output language MUST be English only.
        Keep 1-3 vivid quotes from speech evidence when they reveal real thoughts, plans, emotions, names, or places.
        Merge repetitive signals into concise narrative blocks.
        Never output technical key-value logs such as:
        events= / category= / source= / status= / payload / lat= / lon= / wifi= / cellular= / context tick / processed / strategy / model / backend.
        If such terms appear in evidence, rewrite into human-readable language.

        Window: $windowLabel
        Speech signal: ${snapshot.speechSummary}
        High-priority speech evidence:
        $speechDigest
        Position signal: ${snapshot.positionSummary}
        Indoor/Outdoor: ${snapshot.indoorOutdoor}
        Location: ${snapshot.locationLabel}
        Calendar signal: ${snapshot.calendarSummary}
        Key events:
        $eventsDigest

        Output in this exact plain-text format:
        Diary Summary: <6-16 sentences, personal diary style, specific and evidence-grounded>
        Proactive AI Can Help:
        - <immediate help 1>
        - <immediate help 2>
        - <optional immediate help 3>
        Self TODO:
        - <personal todo 1>
        - <personal todo 2>
        - <optional personal todo 3>
    """.trimIndent()
}

private fun buildFourHourFallbackDiary(
    events: List<ContextEventPayload>,
    snapshot: SessionContextSnapshot,
): FourHourDiaryParsed {
    return FourHourDiaryParsed(
        summary = buildFourHourSummary(events, snapshot),
        proactiveHelp = buildFourHourProactiveHelp(events, snapshot),
        selfTodo = buildFourHourSelfTodo(events, snapshot),
    )
}

private fun parseFourHourDiaryOutput(
    output: String,
    fallback: FourHourDiaryParsed,
    preferFallback: Boolean,
): FourHourDiaryParsed {
    if (preferFallback) return fallback
    val cleaned = output.trim()
    if (cleaned.isBlank()) return fallback

    val lines = cleaned
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("```") }
    if (lines.isEmpty()) return fallback

    var section = "summary"
    val summaryLines = mutableListOf<String>()
    val helpLines = mutableListOf<String>()
    val todoLines = mutableListOf<String>()

    lines.forEach { line ->
        val lower = line.lowercase(Locale.US)
        val summaryHeader = lower.startsWith("diary summary") || lower.startsWith("summary:") || lower.startsWith("日记总结") || lower.startsWith("总结:")
        val helpHeader = lower.startsWith("proactive ai can help") || lower.startsWith("proactive help") || lower.contains("能帮忙") || lower.startsWith("可帮")
        val todoHeader = lower.startsWith("self todo") || lower.startsWith("todo:") || lower.contains("给自己的待办") || lower.startsWith("待办")

        when {
            summaryHeader -> {
                section = "summary"
                val inline = line.substringAfter(":", "").substringAfter("：", "").trim()
                if (inline.isNotBlank()) summaryLines += inline
                return@forEach
            }

            helpHeader -> {
                section = "help"
                val inline = line.substringAfter(":", "").substringAfter("：", "").trim()
                if (inline.isNotBlank()) helpLines += normalizeActionLine(inline)
                return@forEach
            }

            todoHeader -> {
                section = "todo"
                val inline = line.substringAfter(":", "").substringAfter("：", "").trim()
                if (inline.isNotBlank()) todoLines += normalizeActionLine(inline)
                return@forEach
            }
        }

        when (section) {
            "summary" -> summaryLines += line
            "help" -> helpLines += normalizeActionLine(line)
            "todo" -> todoLines += normalizeActionLine(line)
        }
    }

    val summaryCandidate = summaryLines
        .asSequence()
        .map { sanitizeDiaryNarrativeLine(it) }
        .filter { it.isNotBlank() && !looksLikeCounterSummary(it) && !looksLikeTechnicalDiaryLine(it) }
        .distinct()
        .take(8)
        .joinToString(separator = " ")
        .ifBlank { fallback.summary }
    val summary = if (containsCjk(summaryCandidate)) fallback.summary else summaryCandidate
    val finalSummary = summary.take(diarySummaryCharLimit(summary))

    val proactiveHelpCandidate = helpLines
        .asSequence()
        .map { normalizeActionLine(it) }
        .map { sanitizeDiaryNarrativeLine(it) }
        .filter { it.length >= 8 && !looksLikeCounterSummary(it) && !looksLikeTechnicalDiaryLine(it) }
        .distinct()
        .take(3)
        .joinToString(separator = " | ")
        .ifBlank { fallback.proactiveHelp }
    val proactiveHelp = if (containsCjk(proactiveHelpCandidate)) fallback.proactiveHelp else proactiveHelpCandidate
    val finalProactiveHelp = proactiveHelp.take(diaryActionCharLimit(proactiveHelp))

    val selfTodoCandidate = todoLines
        .asSequence()
        .map { normalizeActionLine(it) }
        .map { sanitizeDiaryNarrativeLine(it) }
        .filter { it.length >= 8 && !looksLikeCounterSummary(it) && !looksLikeTechnicalDiaryLine(it) }
        .distinct()
        .take(3)
        .joinToString(separator = " | ")
        .ifBlank { fallback.selfTodo }
    val selfTodo = if (containsCjk(selfTodoCandidate)) fallback.selfTodo else selfTodoCandidate
    val finalSelfTodo = selfTodo.take(diaryActionCharLimit(selfTodo))

    return FourHourDiaryParsed(
        summary = finalSummary,
        proactiveHelp = finalProactiveHelp,
        selfTodo = finalSelfTodo,
    )
}

private fun containsCjk(text: String): Boolean {
    if (text.isBlank()) return false
    return text.any { ch ->
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }
}

private fun selectDiarySignalEvents(events: List<ContextEventPayload>): List<ContextEventPayload> {
    val blockedCategories = setOf("context_log", "model_io", "assistant_session", "audio_gate")
    val filtered = events
        .asSequence()
        .filterNot { it.category.lowercase(Locale.US) in blockedCategories }
        .filterNot { it.source.equals("collection_service", ignoreCase = true) && it.category.equals("device_state", ignoreCase = true) }
        .filterNot { it.summary.startsWith("1m context tick", ignoreCase = true) }
        .toList()
    if (filtered.isEmpty()) return emptyList()

    val speechPreferred = selectPreferredSpeechEvents(filtered)
    val speechIds = speechPreferred.map { it.eventId }.toSet()

    val nonSpeech = filtered
        .asSequence()
        .filterNot { it.eventId in speechIds }
        .take((24 - speechPreferred.size).coerceAtLeast(0))
        .toList()

    return (speechPreferred + nonSpeech)
        .sortedByDescending { it.occurredAt }
        .take(36)
}

private fun selectPreferredSpeechEvents(events: List<ContextEventPayload>): List<ContextEventPayload> {
    val bestByClip = linkedMapOf<String, Pair<ContextEventPayload, SpeechSignal>>()
    events.forEach { event ->
        val signal = extractSpeechSignal(event) ?: return@forEach
        val existing = bestByClip[signal.clipKey]
        if (existing == null) {
            bestByClip[signal.clipKey] = event to signal
            return@forEach
        }
        val existingSignal = existing.second
        val shouldReplace = signal.priority > existingSignal.priority ||
            (signal.priority == existingSignal.priority && signal.occurredAt > existingSignal.occurredAt)
        if (shouldReplace) {
            bestByClip[signal.clipKey] = event to signal
        }
    }

    return bestByClip.values
        .sortedWith(
            compareByDescending<Pair<ContextEventPayload, SpeechSignal>> { it.second.priority }
                .thenByDescending { it.first.occurredAt }
        )
        .map { it.first }
        .take(8)
}

private fun extractDiarySpeechEvidence(
    events: List<ContextEventPayload>,
    maxItems: Int,
): List<String> {
    if (events.isEmpty()) return emptyList()
    val bestByClip = linkedMapOf<String, SpeechSignal>()
    events.forEach { event ->
        val signal = extractSpeechSignal(event) ?: return@forEach
        val existing = bestByClip[signal.clipKey]
        if (
            existing == null ||
            signal.priority > existing.priority ||
            (signal.priority == existing.priority && signal.occurredAt > existing.occurredAt)
        ) {
            bestByClip[signal.clipKey] = signal
        }
    }

    return bestByClip.values
        .sortedWith(
            compareByDescending<SpeechSignal> { it.priority }
                .thenByDescending { it.occurredAt }
        )
        .asSequence()
        .map { normalizeSnippet(it.text) }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.US) }
        .take(maxItems)
        .toList()
}

private fun startOfLocalDayMs(timestampMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestampMs
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun endOfLocalDayExclusiveMs(startOfDayMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = startOfDayMs
    calendar.add(Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
}

private fun looksLikeCounterSummary(text: String): Boolean {
    return looksLikeTechnicalDiaryLine(text)
}

private fun buildFourHourSummary(
    events: List<ContextEventPayload>,
    snapshot: SessionContextSnapshot,
): String {
    val locationDigest = humanizeLocationForDiary(
        locationLabel = snapshot.locationLabel,
        indoorOutdoor = snapshot.indoorOutdoor,
    )
    val speechEvidence = extractDiarySpeechEvidence(events, maxItems = 48)
        .asSequence()
        .map { sanitizeDiaryNarrativeLine(it) }
        .filter { it.isNotBlank() && !looksLikeTechnicalDiaryLine(it) && !isNonSpeechText(it) }
        .distinctBy { it.lowercase(Locale.US) }
        .toList()
    val calendarDigest = sanitizeDiaryNarrativeLine(snapshot.calendarSummary)
        .takeIf {
            it.isNotBlank() &&
                !it.startsWith("No meeting", ignoreCase = true) &&
                !it.contains("no meeting signal", ignoreCase = true) &&
                !looksLikeTechnicalDiaryLine(it)
        }
    val keyMoments = events
        .asSequence()
        .map { sanitizeDiaryNarrativeLine(it.summary) }
        .filter { it.isNotBlank() && !it.startsWith("1m context tick", ignoreCase = true) && !looksLikeTechnicalDiaryLine(it) }
        .distinctBy { it.lowercase(Locale.US) }
        .take(3)
        .toList()
    val quoteMoments = speechEvidence.take(2).map { "“${it.take(90)}”" }

    val opening = when {
        !calendarDigest.isNullOrBlank() ->
            "You spent most of the day around $locationDigest, with a clear thread around \"$calendarDigest\"."
        speechEvidence.isNotEmpty() ->
            "You were mostly around $locationDigest, and your spoken notes show sustained focus on concrete personal priorities."
        else ->
            "You were mostly around $locationDigest, steadily moving through your day with light but consistent progress."
    }

    val moments = if (keyMoments.isEmpty()) {
        ""
    } else {
        "Notable moments: ${keyMoments.joinToString("; ")}."
    }

    val quotes = if (quoteMoments.isEmpty()) {
        ""
    } else {
        "Your own words captured some vivid detail: ${quoteMoments.joinToString("; ")}."
    }

    val narrative = listOf(opening, moments, quotes)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    return narrative.take(diarySummaryCharLimit(narrative))
}

private fun humanizeLocationForDiary(
    locationLabel: String,
    indoorOutdoor: String,
): String {
    val normalizedLocation = sanitizeDiaryNarrativeLine(locationLabel)
    if (
        normalizedLocation.isNotBlank() &&
        !normalizedLocation.startsWith("Unknown", ignoreCase = true) &&
        !normalizedLocation.startsWith("GPS ", ignoreCase = true) &&
        !looksLikeTechnicalDiaryLine(normalizedLocation)
    ) {
        return normalizedLocation
    }
    val indoorOutdoorClean = sanitizeDiaryNarrativeLine(indoorOutdoor)
    return when {
        indoorOutdoorClean.contains("Indoor", ignoreCase = true) -> "an indoor setting"
        indoorOutdoorClean.contains("Outdoor", ignoreCase = true) -> "an outdoor setting"
        else -> "your surrounding environment"
    }
}

private fun sanitizeDiaryNarrativeLine(raw: String): String {
    if (raw.isBlank()) return ""
    var cleaned = raw.trim()
    cleaned = cleaned
        .replace(Regex("^[\\-•*\\d\\.)\\s]+"), "")
        .replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.take(220)
}

private fun looksLikeTechnicalDiaryLine(text: String): Boolean {
    val lower = text.trim().lowercase(Locale.US)
    if (lower.isBlank()) return true
    if (
        lower.startsWith("<empty>") ||
        lower.startsWith("<none>") ||
        lower.startsWith("no speech transcript") ||
        lower.startsWith("no meeting signal")
    ) {
        return true
    }
    if (Regex("""\b[a-z_]{2,20}\s*=\s*[^,\s]+""").containsMatchIn(lower)) return true
    if (Regex("""\b(lat|lon|lng|gps|wifi|cellular|bluetooth|payload|strategy|backend|model|status|source|category)\b""")
            .containsMatchIn(lower)
    ) {
        return true
    }
    if (Regex("""\b(context tick|processed|session_|event count|events=|top=|api|sdk|json|trace)\b""")
            .containsMatchIn(lower)
    ) {
        return true
    }
    return false
}

private fun buildFourHourProactiveHelp(
    events: List<ContextEventPayload>,
    snapshot: SessionContextSnapshot,
): String {
    val summaryLower = events.joinToString(" ") { it.summary }.lowercase(Locale.US)
    val speechLower = snapshot.speechSummary.lowercase(Locale.US)
    val calendarSignals = events.count { event ->
        val lower = event.summary.lowercase(Locale.US)
        event.category.equals("calendar", ignoreCase = true) ||
            event.category.equals("task", ignoreCase = true) ||
            containsAny(lower, listOf("meeting", "calendar", "deadline", "agenda", "appointment", "会议", "日程"))
    }
    val commSignals = events.count { event ->
        val lower = event.summary.lowercase(Locale.US)
        event.category.equals("communication", ignoreCase = true) ||
            event.category.equals("notification", ignoreCase = true) ||
            containsAny(lower, listOf("email", "message", "inbox", "slack", "github", "reply", "邮件", "消息"))
    }

    val actions = mutableListOf<String>()
    if (calendarSignals > 0) {
        actions += "Prepare a concise pre-meeting brief (3 bullets + relevant links) ready to send."
    }
    if (commSignals > 0) {
        actions += "Prioritize unread communication and draft send-ready replies for the top items."
    }
    if (containsAny(summaryLower, listOf("奶茶", "milk tea", "bubble tea", "boba"))) {
        actions += "Provide nearby milk tea options with clickable order/navigation links."
    }
    if (containsAny(speechLower, listOf("buy", "order", "need", "想", "要", "买"))) {
        actions += "Convert the strongest spoken intent into one immediate, executable step."
    }
    if (actions.isEmpty()) {
        actions += "Keep low-noise monitoring and surface only high-confidence, concrete assists."
    }
    val result = actions.distinct().take(3).joinToString(" | ")
    return result.take(diaryActionCharLimit(result))
}

private fun buildFourHourSelfTodo(
    events: List<ContextEventPayload>,
    snapshot: SessionContextSnapshot,
): String {
    val summaryLower = events.joinToString(" ") { it.summary }.lowercase(Locale.US)
    val todos = mutableListOf<String>()

    if (containsAny(summaryLower, listOf("meeting", "calendar", "deadline", "会议", "日程"))) {
        todos += "Lock the objective and required materials for your next meeting."
    }
    if (containsAny(summaryLower, listOf("email", "message", "inbox", "slack", "github", "邮件", "消息"))) {
        todos += "Clear the highest-priority pending communication before context-switching."
    }
    if (!snapshot.speechSummary.startsWith("No speech", ignoreCase = true)) {
        todos += "Turn your strongest spoken idea into one concrete next action."
    }
    if (snapshot.indoorOutdoor.contains("Outdoor", ignoreCase = true) || snapshot.positionSummary.contains("motion=", ignoreCase = true)) {
        todos += "Do short, low-friction tasks while moving; reserve deep work for stable time blocks."
    }
    if (todos.isEmpty()) {
        todos += "Write down the single most important remaining task today and set a reminder."
    }
    val result = todos.distinct().take(3).joinToString(" | ")
    return result.take(diaryActionCharLimit(result))
}

private fun diarySummaryCharLimit(text: String): Int {
    if (text.isBlank()) return 1040
    val normalized = text.trim()
    val lower = normalized.lowercase(Locale.US)
    val punctuationCount = normalized.count { ch ->
        ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?'
    }
    val hasQuote = normalized.contains("“") || normalized.contains("”") || normalized.contains("\"")
    val hasPersonalDetail = containsAny(
        lower,
        listOf(
            "我",
            "今天",
            "刚刚",
            "我们",
            "朋友",
            "同事",
            "客户",
            "家人",
            "奶茶",
            "meeting",
            "calendar",
            "deadline",
            "trip",
            "restaurant",
            "flight",
            "hotel",
        ),
    )
    val baseLimit = when {
        hasQuote || hasPersonalDetail -> 920
        punctuationCount >= 6 || normalized.length > 640 -> 840
        punctuationCount >= 4 || normalized.length > 460 -> 720
        else -> 560
    }
    return baseLimit * 2
}

private fun diaryActionCharLimit(text: String): Int {
    if (text.isBlank()) return 360
    val items = text.split("|").map { it.trim() }.count { it.isNotBlank() }
    return when {
        items >= 3 || text.length > 420 -> 520
        items == 2 -> 420
        else -> 360
    }
}

private fun buildFifteenMinuteSessions(
    events: List<ContextEventPayload>,
    maxSessions: Int,
): List<FifteenMinuteSession> {
    if (events.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val minTs = now - ASSISTANT_SESSION_LOOKBACK_MS
    val maxTs = now + ASSISTANT_SESSION_FUTURE_TOLERANCE_MS
    val excludedCategories = setOf(
        "model_io",
        "assistant_session",
        "context_log",
        "audio_gate",
        "capability_status",
        "daily_focus",
    )
    val contextOnly = events.filterNot { event ->
        val categoryLower = event.category.lowercase(Locale.US)
        event.occurredAt !in minTs..maxTs ||
            event.source == "local_model" ||
            categoryLower in excludedCategories ||
            categoryLower.endsWith("_bootstrap")
    }
    if (contextOnly.isEmpty()) return emptyList()

    return contextOnly
        .groupBy { event -> (event.occurredAt / ASSISTANT_SESSION_WINDOW_MS) * ASSISTANT_SESSION_WINDOW_MS }
        .entries
        .sortedByDescending { it.key }
        .take(maxSessions)
        .map { (startMs, groupedEvents) ->
            val ordered = groupedEvents.sortedByDescending { it.occurredAt }
            val sparklingSignals = ordered.filter { isSparklingSignal(it) }
            val sparklingTriggers = sparklingSignals
                .mapNotNull { extractSparklingTrigger(it) }
                .distinct()
                .take(4)
            val sparklingSignalCount = sparklingSignals.size
            FifteenMinuteSession(
                sessionId = "session_$startMs",
                startMs = startMs,
                endMs = startMs + ASSISTANT_SESSION_WINDOW_MS,
                events = ordered,
                sparklingSignalCount = sparklingSignalCount,
                sparklingTriggers = sparklingTriggers,
                isSparkling = sparklingSignalCount > 0,
            )
        }
}

private fun buildSessionContextSnapshot(
    context: android.content.Context,
    events: List<ContextEventPayload>,
    maxSpeechSegments: Int = 6,
): SessionContextSnapshot {
    val ordered = events.sortedByDescending { it.occurredAt }
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

    ordered.forEach { event ->
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
            if (latitude == null) {
                latitude = payloadDouble(event.payload, "latitude")
            }
            if (longitude == null) {
                longitude = payloadDouble(event.payload, "longitude")
            }
            if (motionState == null) {
                motionState = payloadString(event.payload, "motionState")
            }
            if (cityLabel == null) {
                cityLabel = payloadString(event.payload, "city")
            }
            if (latestLocationSummary == null) {
                latestLocationSummary = event.summary
            }
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

    val fallbackLocation = if (latitude == null || longitude == null) {
        loadRecentLocationFallback(
            context = context,
            beforeMs = ordered.firstOrNull()?.occurredAt ?: System.currentTimeMillis(),
        )
    } else {
        null
    }
    if (latitude == null) latitude = fallbackLocation?.latitude
    if (longitude == null) longitude = fallbackLocation?.longitude
    if (cityLabel.isNullOrBlank()) cityLabel = fallbackLocation?.city
    if (motionState.isNullOrBlank()) motionState = fallbackLocation?.motionState
    if (latestLocationSummary.isNullOrBlank()) latestLocationSummary = fallbackLocation?.summary

    val speechSummary = buildSpeechSummaryFromSignals(
        signals = speechSignals,
        maxSegments = maxSpeechSegments,
    )

    val lat = latitude
    val lon = longitude
    val positionSummary = when {
        lat != null && lon != null -> {
            val motion = motionState?.takeIf { it.isNotBlank() } ?: "unknown"
            "lat=${formatCoordinate(lat)}, lon=${formatCoordinate(lon)} | motion=$motion"
        }
        !latestLocationSummary.isNullOrBlank() -> normalizeSnippet(latestLocationSummary!!)
        else -> "No location sample in this session"
    }

    val indoorOutdoor = inferIndoorOutdoor(wifiSignals, cellularSignals)
    val geocoded = reverseGeocodeLabel(context, lat, lon)
    val locationLabel = when {
        !cityLabel.isNullOrBlank() && geocoded.startsWith("GPS ", ignoreCase = true) -> "$cityLabel | $geocoded"
        !cityLabel.isNullOrBlank() && geocoded.startsWith("Unknown", ignoreCase = true) -> cityLabel.orEmpty()
        !cityLabel.isNullOrBlank() -> "$cityLabel | ${geocoded.take(80)}"
        else -> geocoded
    }
    val calendarSummary = calendarSignals
        .asSequence()
        .map { normalizeSnippet(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(2)
        .joinToString(separator = " | ")
        .ifBlank { "No meeting signal in this session" }

    return SessionContextSnapshot(
        speechSummary = speechSummary,
        positionSummary = positionSummary,
        indoorOutdoor = indoorOutdoor,
        locationLabel = locationLabel,
        calendarSummary = calendarSummary,
        sparklingSession = sparklingSignalCount > 0,
        sparklingSignalCount = sparklingSignalCount,
        sparklingTriggers = sparklingTriggers.joinToString(separator = ", ").ifBlank { "-" },
    )
}

private data class LocationFallbackSignal(
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val motionState: String?,
    val summary: String?,
)

private fun loadRecentLocationFallback(
    context: android.content.Context,
    beforeMs: Long,
): LocationFallbackSignal? {
    val minTs = beforeMs - LOCATION_SESSION_FALLBACK_LOOKBACK_MS
    val rows = ContextEventStore.getInstance(context)
        .getRecent(limit = 480)
        .asSequence()
        .filter { row ->
            row.occurredAt in minTs..beforeMs &&
                (row.category.equals("location", ignoreCase = true) ||
                    row.source.contains("location", ignoreCase = true))
        }
    for (row in rows) {
        val payloadRaw = kotlin.runCatching { JSONObject(row.payloadJson).toMap() }.getOrDefault(emptyMap())
        val payload = normalizeAnyMap(payloadRaw)
        val latitude = payloadDouble(payload, "latitude")
        val longitude = payloadDouble(payload, "longitude")
        val city = payloadString(payload, "city")
        val motionState = payloadString(payload, "motionState")
        if (latitude != null && longitude != null) {
            return LocationFallbackSignal(
                latitude = latitude,
                longitude = longitude,
                city = city,
                motionState = motionState,
                summary = row.summary,
            )
        }
    }
    return null
}

private data class SpeechSignal(
    val occurredAt: Long,
    val clipKey: String,
    val text: String,
    val priority: Int,
)

private fun extractSpeechSignal(event: ContextEventPayload): SpeechSignal? {
    val sourceLower = event.source.lowercase(Locale.US)
    val categoryLower = event.category.lowercase(Locale.US)
    val isEngagedSession = categoryLower == "engaged_session" || sourceLower.contains("engaged_session")
    if (categoryLower != "audio" && !sourceLower.contains("audio") && !isEngagedSession) return null

    val status = payloadString(event.payload, "status")?.lowercase(Locale.US).orEmpty()
    if (status == "no_speech" || status == "error") return null

    val stitched = payloadString(event.payload, "stitchedTranscript")
    val transcript = payloadString(event.payload, "transcript")
    val summaryTranscript = when {
        event.summary.startsWith("Ambient speech transcript", ignoreCase = true) ->
            event.summary.substringAfter(":", "").trim()
        event.summary.startsWith("Manual intake speech", ignoreCase = true) ->
            event.summary.substringAfter(":", "").trim()
        event.summary.startsWith("Engaged session transcript", ignoreCase = true) ->
            event.summary.substringAfter(":", "").trim()
        else -> ""
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
        ?: payloadString(event.payload, "sessionStartedAt")
            ?.ifBlank { null }
            ?.let { "engaged:$it" }
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

private fun buildAssistantPromptForSession(
    session: FifteenMinuteSession,
    snapshot: SessionContextSnapshot,
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

        Session window: ${formatSessionRange(session.startMs, session.endMs)}
        Event count: ${session.events.size}
        Sparkling marker: ${if (snapshot.sparklingSession) "YES (${snapshot.sparklingSignalCount}, triggers=${snapshot.sparklingTriggers})" else "NO"}
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

private fun parseSessionInferenceOutput(
    output: String,
    suggestedActions: List<String>,
    fallbackScenario: String,
    fallbackActionPlan: String,
    preferFallback: Boolean,
): SessionInferenceParsed {
    if (preferFallback) {
        return SessionInferenceParsed(
            guessedUserScenario = fallbackScenario,
            actionPlan = fallbackActionPlan,
        )
    }

    val cleanedOutput = output.trim()
    if (cleanedOutput.isBlank()) {
        return SessionInferenceParsed(
            guessedUserScenario = fallbackScenario,
            actionPlan = fallbackActionPlan,
        )
    }

    val lines = cleanedOutput.lines().map { it.trim() }.filter { it.isNotBlank() }
    val scenarioFromLabel = lines
        .firstOrNull {
            val lower = it.lowercase(Locale.US)
            lower.startsWith("guessed user scenario:") || lower.startsWith("suggestion:")
        }
        ?.substringAfter(":", "")
        ?.trim()
    val scenario = when {
        !scenarioFromLabel.isNullOrBlank() -> scenarioFromLabel
        suggestedActions.isNotEmpty() -> suggestedActions.first()
        else -> lines.first().trimStart('-', '*', '1', '.', ')', ' ').trim()
    }.take(220)

    val actionHeaderIndex = lines.indexOfFirst {
        val lower = it.lowercase(Locale.US)
        lower.startsWith("action plan") || lower.startsWith("plan:")
    }

    val actionLines = if (actionHeaderIndex >= 0) {
        lines.drop(actionHeaderIndex + 1)
            .take(6)
            .mapNotNull { line ->
                val normalized = normalizeActionLine(line)
                if (normalized.isBlank()) null else normalized
            }
            .take(3)
    } else {
        emptyList()
    }

    val fallbackActions = suggestedActions
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(3)

    val modelActionPlan = when {
        actionLines.isNotEmpty() -> actionLines.joinToString(separator = " | ")
        fallbackActions.isNotEmpty() -> fallbackActions.joinToString(separator = " | ")
        else -> lines.drop(1).take(2).joinToString(separator = " | ").ifBlank {
            "Continue passive monitoring and wait for stronger context."
        }
    }.take(720)

    val finalScenario = if (isWeakScenario(scenario)) fallbackScenario else scenario
    val finalActionPlan = if (isWeakActionPlan(modelActionPlan)) fallbackActionPlan else modelActionPlan

    return SessionInferenceParsed(
        guessedUserScenario = finalScenario.ifBlank { fallbackScenario },
        actionPlan = finalActionPlan.ifBlank { fallbackActionPlan },
    )
}

private fun buildHeuristicScenarioGuess(
    session: FifteenMinuteSession,
    snapshot: SessionContextSnapshot,
    allEvents: List<ContextEventPayload> = session.events,
): SessionHeuristicGuess {
    val hasSpeech = !snapshot.speechSummary.startsWith("No speech", ignoreCase = true)
    val speechLower = snapshot.speechSummary.lowercase(Locale.US)
    val calendarLower = snapshot.calendarSummary.lowercase(Locale.US)
    val calendarSignals = session.events.count { event ->
        val categoryLower = event.category.lowercase(Locale.US)
        categoryLower == "calendar" || categoryLower == "task"
    }
    val commSignals = session.events.count { event ->
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
    val sessionHour = Calendar.getInstance().apply { timeInMillis = session.endMs }.get(Calendar.HOUR_OF_DAY)
    val mealWindow = sessionHour in 11..14 || sessionHour in 18..21
    val motionSignals2h = allEvents
        .asSequence()
        .filter { it.occurredAt in (session.endMs - 2 * 60 * 60 * 1000L)..session.endMs }
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
        session.isSparkling && hasSpeech ->
            "User intentionally marked a sparkling moment and likely wants fast help on a specific personal need"
        session.isSparkling ->
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
        if (session.isSparkling) add("sparkling_marker=${session.sparklingTriggers.joinToString(",").ifBlank { "manual" }}")
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
    val sparklingBoost = if (session.isSparkling) 14 else 0
    val confidence = (44 + evidence.size * 9 + intentScore * 5 + sparklingBoost).coerceIn(35, 96)
    val scenario = buildString {
        append("$activity; mood=$mood; confidence=$confidence%. ")
        append("Evidence: ${evidence.joinToString(", ")}.")
    }.take(220)

    val actions = mutableListOf<String>()
    if (session.isSparkling) {
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

    return SessionHeuristicGuess(
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

private fun formatSessionRange(startMs: Long, endMs: Long): String {
    val dayFmt = SimpleDateFormat("MM-dd", Locale.US)
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val day = dayFmt.format(Date(startMs))
    val start = timeFmt.format(Date(startMs))
    val end = timeFmt.format(Date(endMs))
    return "$day $start-$end"
}

private fun formatDailyDiaryRange(startMs: Long): String {
    val dayFmt = SimpleDateFormat("MM-dd", Locale.US)
    val day = dayFmt.format(Date(startMs))
    return "$day 00:00-24:00"
}

private fun reverseGeocodeLabel(
    context: android.content.Context,
    latitude: Double?,
    longitude: Double?,
): String {
    if (latitude == null || longitude == null) return "Unknown location"
    val coord = "GPS ${formatCoordinate(latitude)}, ${formatCoordinate(longitude)}"
    if (!Geocoder.isPresent()) return coord

    return kotlin.runCatching {
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        if (address == null) {
            coord
        } else {
            listOfNotNull(address.featureName, address.locality, address.adminArea, address.countryName)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
                .joinToString(", ")
                .ifBlank { coord }
        }
    }.getOrElse { coord }
}

private fun inferIndoorOutdoor(
    wifiSignals: List<Boolean>,
    cellularSignals: List<Boolean>,
): String {
    val hasWifi = wifiSignals.any { it }
    val hasCell = cellularSignals.any { it }
    return when {
        hasWifi && !hasCell -> "Indoor likely (wifi)"
        hasCell && !hasWifi -> "Outdoor likely (cellular)"
        hasCell && hasWifi -> "Transition or mixed"
        else -> "Unknown"
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

private fun normalizeSnippet(input: String): String {
    return input
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(220)
}

private fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.4f", value)
}
