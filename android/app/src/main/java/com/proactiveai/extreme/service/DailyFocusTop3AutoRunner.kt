package com.proactiveai.extreme.service

import android.content.Context
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.core.edge.OnDeviceInferenceEngine
import com.proactiveai.extreme.orchestrator.ActionStepPayload
import com.proactiveai.extreme.orchestrator.ContextEventPayload
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.storage.ActionQueueStore
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.storage.toPayload
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

object DailyFocusTop3AutoRunner {
    private const val TARGET_HOUR_OF_DAY = 9
    private const val LOOKBACK_MS = 2 * 24 * 60 * 60 * 1000L
    private const val MAX_EVENTS = 4_000
    private const val MAX_EVIDENCE_LINES = 80

    private data class FocusItem(
        val rank: Int,
        val title: String,
        val reason: String,
        val evidence: String,
        val importance: Int,
        val urgency: Int,
        val missRisk: Int,
        val action: String,
    )

    suspend fun runIfDue(context: Context) {
        runInternal(
            context = context,
            requireDailyScheduleGate = true,
            allowRepeatToday = false,
            replaceExistingForDay = false,
        )
    }

    suspend fun runNow(
        context: Context,
        replaceExistingForDay: Boolean = true,
    ): Boolean {
        return runInternal(
            context = context,
            requireDailyScheduleGate = false,
            allowRepeatToday = true,
            replaceExistingForDay = replaceExistingForDay,
        )
    }

    private suspend fun runInternal(
        context: Context,
        requireDailyScheduleGate: Boolean,
        allowRepeatToday: Boolean,
        replaceExistingForDay: Boolean,
    ): Boolean {
        if (AppPrefs.isGlobalLockEnabled(context)) return false

        val now = System.currentTimeMillis()
        if (requireDailyScheduleGate) {
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            if (calendar.get(Calendar.HOUR_OF_DAY) < TARGET_HOUR_OF_DAY) return false
        }

        val todayToken = dateToken(now)
        if (!allowRepeatToday && AppPrefs.getDailyFocusLastDate(context) == todayToken) return false

        val events = loadRecentEvents(context, now)
        if (events.isEmpty()) return false

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

        val prompt = buildPrompt(now = now, todayToken = todayToken, events = events)
        val trace = OnDeviceInferenceEngine.inferFromPromptWithTrace(
            context = context,
            model = model,
            prompt = prompt,
            runtimeConfig = runtimeConfig,
        )
        val result = trace.result
        val raw = result.nativeModelOutput?.trim().takeIf { !it.isNullOrBlank() } ?: result.summary

        val parsed = parseFocusItems(raw)
        val fallback = buildFallbackFocusItems(events)
        val finalItems = personalizeFocusItems(
            items = (parsed.ifEmpty { fallback }),
            events = events,
        )
            .sortedBy { it.rank }
            .take(3)
            .ifEmpty { fallback.take(3) }

        if (finalItems.isEmpty()) return false

        enqueueFocusItems(
            context = context,
            todayToken = todayToken,
            now = now,
            items = finalItems,
            replaceExistingForDay = replaceExistingForDay,
        )
        persistFocusEvents(
            context = context,
            todayToken = todayToken,
            now = now,
            prompt = prompt,
            traceOutput = raw,
            modelLabel = "${result.model.label} | ${result.strategyLabel}",
            nativeUsed = result.nativeModelUsed,
            nativeStatus = result.nativeModelMessage,
            items = finalItems,
            eventCount = events.size,
        )
        AppPrefs.setDailyFocusLastDate(context, todayToken)
        return true
    }

    private fun loadRecentEvents(context: Context, now: Long): List<ContextEventPayload> {
        val cutoff = now - LOOKBACK_MS
        val store = ContextEventStore.getInstance(context)
        return store.getRecent(limit = MAX_EVENTS)
            .mapNotNull { item ->
                val payload = safePayloadMap(item.payloadJson)
                kotlin.runCatching { item.toPayload(payload) }.getOrNull()
            }
            .filter { it.occurredAt >= cutoff }
            .filterNot { event ->
                event.category == "model_io" ||
                    event.category == "daily_focus"
            }
    }

    private fun buildPrompt(
        now: Long,
        todayToken: String,
        events: List<ContextEventPayload>,
    ): String {
        val ordered = events.sortedByDescending { it.occurredAt }
        val categoryDigest = ordered
            .groupingBy { it.category.lowercase(Locale.US) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString(separator = ", ") { "${it.key}:${it.value}" }
            .ifBlank { "none" }

        val calendarEvidence = ordered
            .asSequence()
            .map { normalizeLine(it.summary) }
            .filter { containsAny(it.lowercase(Locale.US), listOf("meeting", "calendar", "deadline", "appointment", "会议", "日程", "截止")) }
            .distinctBy { it.lowercase(Locale.US) }
            .take(12)
            .joinToString(separator = "\n") { "- $it" }
            .ifBlank { "- 无明显会议/截止线索" }

        val speechEvidence = extractSpeechEvidence(ordered, maxItems = 18)
            .joinToString(separator = "\n") { "- $it" }
            .ifBlank { "- 无明显语音线索" }

        val highSignalEvents = ordered
            .asSequence()
            .map { normalizeLine(it.summary) }
            .filter { it.isNotBlank() && !looksTooTechnical(it) }
            .distinctBy { it.lowercase(Locale.US) }
            .take(MAX_EVIDENCE_LINES)
            .joinToString(separator = "\n") { "- $it" }
            .ifBlank { "- 无额外线索" }

        return """
            你是用户的执行型贴身秘书。请基于“最近2天”的手机上下文，生成“今天最需要focus的三件事”。
            目标：宁可少而准，也不要泛泛建议。必须可执行、可落地。

            打分规则（0-100）：
            - Importance：做成后价值有多大
            - Urgency：今天不做会不会马上影响结果
            - MissRisk：漏掉后造成损失/翻车的概率

            选择原则：
            1) 必须结合证据，不要空泛
            2) 优先选择“今天就该处理”的事项
            3) 三条之间尽量不重复
            4) 不要输出技术日志词，不要输出“打开GitHub/开会准备”这类无证据的套话
            5) 每一项都要给出一个“Evidence”字段，引用本次输入里的具体语句或线索（可加引号）
            6) 禁止使用泛化标题：例如“明确今天最重要产出”“处理关键阻塞点”“清理待办”这类模板句

            今天日期: $todayToken
            当前时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))}
            最近2天事件总数: ${events.size}
            类别统计: $categoryDigest

            会议/日程证据:
            $calendarEvidence

            语音证据:
            $speechEvidence

            其他高信号线索:
            $highSignalEvents

            严格按以下格式输出（纯文本，必须正好3项）：
            [ITEM_1]
            Title: <一句话标题>
            Why: <为什么它最该做，必须具体>
            Evidence: <来自输入的具体证据，尽量保留原词/短句>
            Importance: <0-100>
            Urgency: <0-100>
            MissRisk: <0-100>
            Action: <下一步动作，尽量一句可执行命令>

            [ITEM_2]
            Title: ...
            Why: ...
            Evidence: ...
            Importance: ...
            Urgency: ...
            MissRisk: ...
            Action: ...

            [ITEM_3]
            Title: ...
            Why: ...
            Evidence: ...
            Importance: ...
            Urgency: ...
            MissRisk: ...
            Action: ...
        """.trimIndent()
    }

    private fun parseFocusItems(output: String): List<FocusItem> {
        val cleaned = output.trim()
        if (cleaned.isBlank()) return emptyList()

        val blocks = cleaned
            .split(Regex("(?=\\[ITEM_[1-3]\\])"))
            .map { it.trim() }
            .filter { it.startsWith("[ITEM_") }

        if (blocks.isEmpty()) return emptyList()

        return blocks.mapNotNull { block ->
            val rank = Regex("""\[ITEM_(\d+)]""")
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: return@mapNotNull null

            val title = fieldValue(
                block = block,
                keys = listOf("title", "标题"),
            )
            if (title.isBlank()) return@mapNotNull null

            val reason = fieldValue(
                block = block,
                keys = listOf("why", "reason", "原因", "依据"),
            ).ifBlank { "Based on recent context evidence." }
            val evidence = fieldValue(
                block = block,
                keys = listOf("evidence", "证据", "线索"),
            ).ifBlank { reason.take(120) }

            val importance = parseScore(fieldValue(block, keys = listOf("importance", "重要")))
            val urgency = parseScore(fieldValue(block, keys = listOf("urgency", "紧急")))
            val missRisk = parseScore(fieldValue(block, keys = listOf("missrisk", "miss risk", "漏球", "遗漏风险")))
            val action = fieldValue(
                block = block,
                keys = listOf("action", "next action", "下一步", "动作"),
            ).ifBlank { "Start a focused 25-minute block on this item." }

            FocusItem(
                rank = rank.coerceIn(1, 3),
                title = normalizeLine(title).take(88).ifBlank { "Today focus item #$rank" },
                reason = normalizeLine(reason).take(260),
                evidence = normalizeLine(evidence).take(220),
                importance = importance,
                urgency = urgency,
                missRisk = missRisk,
                action = normalizeLine(action).take(160),
            )
        }.distinctBy { it.rank }
    }

    private fun enqueueFocusItems(
        context: Context,
        todayToken: String,
        now: Long,
        items: List<FocusItem>,
        replaceExistingForDay: Boolean,
    ) {
        val queueStore = ActionQueueStore.getInstance(context)
        val dayToken = todayToken.replace("-", "")
        val planId = "daily_focus_$dayToken"
        if (replaceExistingForDay) {
            queueStore.clearByPlanId(planId)
        }

        items.sortedBy { it.rank }.forEach { item ->
            val step = ActionStepPayload(
                stepId = "focus_${dayToken}_${item.rank}_${now.toString(16)}",
                connector = "focus",
                operation = "today_top3",
                args = mapOf(
                    "rank" to item.rank,
                    "title" to item.title,
                    "reason" to item.reason,
                    "evidence" to item.evidence,
                    "importance" to item.importance,
                    "urgency" to item.urgency,
                    "missRisk" to item.missRisk,
                    "action" to item.action,
                    "dateToken" to todayToken,
                    "lookbackHours" to 48,
                    "generatedAt" to now,
                ),
            )
            // maxAttempts=0 means this item is kept as a planning/focus card, not auto-executed.
            queueStore.enqueue(planId = planId, step = step, maxAttempts = 0)
        }
    }

    private fun persistFocusEvents(
        context: Context,
        todayToken: String,
        now: Long,
        prompt: String,
        traceOutput: String,
        modelLabel: String,
        nativeUsed: Boolean,
        nativeStatus: String,
        items: List<FocusItem>,
        eventCount: Int,
    ) {
        val store = ContextEventStore.getInstance(context)
        store.insert(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = "assistant_engine",
                category = "daily_focus",
                summary = "Daily focus top3 generated for $todayToken",
                payload = mapOf(
                    "dateToken" to todayToken,
                    "itemCount" to items.size,
                    "contextEventCount" to eventCount,
                    "items" to items.map { item ->
                        mapOf(
                            "rank" to item.rank,
                            "title" to item.title,
                            "reason" to item.reason,
                            "evidence" to item.evidence,
                            "importance" to item.importance,
                            "urgency" to item.urgency,
                            "missRisk" to item.missRisk,
                            "action" to item.action,
                        )
                    },
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        store.insert(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = "local_model",
                category = "model_io",
                summary = "Model IO [daily_focus_9am_auto] $modelLabel",
                payload = mapOf(
                    "trigger" to "daily_focus_9am_auto",
                    "prompt" to prompt,
                    "response" to traceOutput.take(8_000),
                    "nativeUsed" to nativeUsed,
                    "nativeStatus" to nativeStatus,
                    "contextEventCount" to eventCount,
                    "dateToken" to todayToken,
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )
    }

    private fun buildFallbackFocusItems(events: List<ContextEventPayload>): List<FocusItem> {
        val summaryLower = events.joinToString(" ") { it.summary }.lowercase(Locale.US)
        val speech = extractSpeechEvidence(events.sortedByDescending { it.occurredAt }, maxItems = 10)
        val speechJoined = speech.joinToString(" | ")

        val calendarSignals = events.count { event ->
            containsAny(
                event.summary.lowercase(Locale.US),
                listOf("meeting", "calendar", "deadline", "appointment", "会议", "日程", "截止"),
            )
        }
        val commSignals = events.count { event ->
            containsAny(
                event.summary.lowercase(Locale.US),
                listOf("email", "message", "slack", "github", "reply", "inbox", "邮件", "消息"),
            )
        }
        val intentSignals = containsAny(
            summaryLower,
            listOf("need", "must", "want", "plan", "book", "buy", "schedule", "要", "需要", "想", "安排", "买"),
        )

        val items = mutableListOf<FocusItem>()
        if (calendarSignals > 0) {
            items += FocusItem(
                rank = 1,
                title = "锁定今天最关键会议与截止任务",
                reason = "最近2天检测到较多会议/截止线索，今天先把高影响节点准备到位，避免连锁延误。",
                evidence = "会议/截止相关信号=$calendarSignals",
                importance = 92,
                urgency = 90,
                missRisk = 88,
                action = "先整理今天最重要会议/截止项，补齐材料并确认下一步负责人。",
            )
        }
        if (commSignals > 0) {
            items += FocusItem(
                rank = 2,
                title = "清理高风险沟通与待回复事项",
                reason = "最近上下文出现持续沟通信号，未及时回复会影响推进效率和协作节奏。",
                evidence = "沟通信号数=$commSignals",
                importance = 86,
                urgency = 82,
                missRisk = 84,
                action = "先处理最关键的3条待回复，给出明确承诺时间和下一动作。",
            )
        }
        if (intentSignals || speechJoined.isNotBlank()) {
            items += FocusItem(
                rank = 3,
                title = "推进你反复提到的核心个人意图",
                reason = if (speechJoined.isNotBlank()) {
                    "语音里有明确个人意图线索：${speechJoined.take(120)}。"
                } else {
                    "最近2天出现多次“要/需要/计划”相关表达，说明该事项已进入执行窗口。"
                },
                evidence = if (speechJoined.isNotBlank()) speechJoined.take(180) else "intent_signals_detected",
                importance = 84,
                urgency = 78,
                missRisk = 80,
                action = "把该意图拆成今天可完成的第一步，并立即执行25分钟。",
            )
        }

        val defaults = listOf(
            FocusItem(
                rank = 1,
                title = "明确今天唯一最重要产出",
                reason = "先聚焦高杠杆产出，能显著降低全天任务分散。",
                evidence = "context_fallback",
                importance = 88,
                urgency = 80,
                missRisk = 82,
                action = "写下一句今天最关键结果，并为它锁定第一段深度时间。",
            ),
            FocusItem(
                rank = 2,
                title = "处理最可能延误的关键阻塞点",
                reason = "提前解除阻塞，比后续救火成本更低。",
                evidence = "context_fallback",
                importance = 83,
                urgency = 79,
                missRisk = 85,
                action = "列出当前最大阻塞点并马上发起一次推进动作。",
            ),
            FocusItem(
                rank = 3,
                title = "把零散事项收敛为可执行清单",
                reason = "减少认知切换，提升执行连续性。",
                evidence = "context_fallback",
                importance = 78,
                urgency = 72,
                missRisk = 76,
                action = "把分散待办压缩成3条可执行动作，按顺序开始第一条。",
            ),
        )

        val merged = (items + defaults)
            .sortedBy { it.rank }
            .distinctBy { it.rank }
            .take(3)
        return merged.ifEmpty { defaults }
    }

    private fun personalizeFocusItems(
        items: List<FocusItem>,
        events: List<ContextEventPayload>,
    ): List<FocusItem> {
        if (items.isEmpty()) return emptyList()
        val evidencePool = buildPersonalEvidencePool(events)

        return items.mapIndexed { index, item ->
            val fallbackEvidence = evidencePool.getOrNull(index)
                ?: evidencePool.firstOrNull()
                ?: "最近2天上下文显示该事项反复出现"
            val evidence = normalizeLine(item.evidence)
                .takeIf { it.isNotBlank() && !isTooGenericEvidence(it) }
                ?: fallbackEvidence

            val title = normalizeLine(item.title).let { raw ->
                if (!isGenericFocusTitle(raw)) raw.take(88) else buildPersonalTitleFromEvidence(evidence, index + 1)
            }

            val reason = normalizeLine(item.reason).let { raw ->
                if (!isGenericReason(raw)) {
                    raw.take(260)
                } else {
                    "从最近2天线索看，你多次提到或遇到“${evidence.take(96)}”，这件事今天不推进最容易导致后续节奏被打断。".take(260)
                }
            }

            val action = normalizeLine(item.action).let { raw ->
                if (!isGenericAction(raw)) {
                    raw.take(160)
                } else {
                    "先围绕「${title.take(36)}」做第一步：把该事项拆成一个可在30分钟内完成的动作并立即开始。".take(160)
                }
            }

            item.copy(
                title = title.ifBlank { item.title }.take(88),
                reason = reason.ifBlank { item.reason }.take(260),
                evidence = evidence.take(220),
                action = action.ifBlank { item.action }.take(160),
            )
        }
    }

    private fun buildPersonalEvidencePool(events: List<ContextEventPayload>): List<String> {
        val ordered = events.sortedByDescending { it.occurredAt }
        val speech = extractSpeechEvidence(ordered, maxItems = 18)
            .map { "语音: ${it.take(120)}" }

        val nonSpeech = ordered
            .asSequence()
            .map { normalizeLine(it.summary) }
            .filter { it.length >= 10 && !looksTooTechnical(it) }
            .filterNot { isNonSpeechText(it) }
            .filterNot { isTooGenericEvidence(it) }
            .distinctBy { it.lowercase(Locale.US) }
            .take(24)
            .map { "事件: ${it.take(120)}" }
            .toList()

        return (speech + nonSpeech)
            .distinctBy { it.lowercase(Locale.US) }
            .take(24)
    }

    private fun buildPersonalTitleFromEvidence(evidence: String, rank: Int): String {
        val cleaned = evidence
            .removePrefix("语音:")
            .removePrefix("事件:")
            .trim()
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
        if (cleaned.isBlank()) return "今日重点事项 #$rank"

        val tokens = cleaned.split(Regex("[,，。；; ]+"))
            .filter { it.isNotBlank() }
        val short = if (containsChinese(cleaned)) {
            cleaned.take(22)
        } else {
            tokens.take(6).joinToString(" ").take(52)
        }
        return "优先推进：$short".take(88)
    }

    private fun isGenericFocusTitle(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (lower.isBlank()) return true
        return containsAny(
            lower,
            listOf(
                "most important output",
                "key blocker",
                "clear pending",
                "focus item",
                "today focus",
                "明确今天唯一最重要产出",
                "处理最可能延误的关键阻塞点",
                "把零散事项收敛为可执行清单",
                "推进核心个人意图",
                "今日重点事项",
            ),
        )
    }

    private fun isGenericReason(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (lower.length < 18) return true
        return containsAny(
            lower,
            listOf(
                "improve efficiency",
                "reduce context switching",
                "high leverage",
                "based on recent context evidence",
                "减少认知切换",
                "提升执行连续性",
                "提高效率",
                "最近上下文显示",
                "context_fallback",
            ),
        )
    }

    private fun isGenericAction(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (lower.isBlank()) return true
        return containsAny(
            lower,
            listOf(
                "start a focused 25-minute block",
                "写下一句今天最关键结果",
                "按顺序开始第一条",
                "马上发起一次推进动作",
            ),
        )
    }

    private fun isTooGenericEvidence(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (lower.length < 8) return true
        return containsAny(
            lower,
            listOf(
                "context_fallback",
                "insufficient",
                "limited context",
                "最近2天上下文",
                "signal detected",
                "signals=",
                "event count",
            ),
        )
    }

    private fun containsChinese(text: String): Boolean {
        return text.any { it.code in 0x4E00..0x9FFF }
    }

    private fun extractSpeechEvidence(events: List<ContextEventPayload>, maxItems: Int): List<String> {
        return events.asSequence()
            .mapNotNull { event ->
                val sourceLower = event.source.lowercase(Locale.US)
                val categoryLower = event.category.lowercase(Locale.US)
                if (categoryLower != "audio" && !sourceLower.contains("audio")) return@mapNotNull null
                val stitched = payloadString(event.payload, "stitchedTranscript")
                val transcript = payloadString(event.payload, "transcript")
                val summaryText = if (event.summary.startsWith("Ambient speech transcript", ignoreCase = true)) {
                    event.summary.substringAfter(":", "").trim()
                } else {
                    null
                }
                val picked = listOf(stitched, transcript, summaryText)
                    .firstOrNull { !it.isNullOrBlank() }
                    .orEmpty()
                    .trim()
                val normalized = normalizeLine(picked)
                if (normalized.isBlank() || isNonSpeechText(normalized)) null else normalized
            }
            .distinctBy { it.lowercase(Locale.US) }
            .take(maxItems)
            .toList()
    }

    private fun isNonSpeechText(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return lower.isBlank() ||
            lower == "<no-speech>" ||
            lower == "no speech" ||
            lower == "no_speech" ||
            lower == "[silence]" ||
            lower == "silence" ||
            lower.contains("speech_error_") ||
            lower.contains("speech recognizer failed") ||
            lower.contains("no clear speech") ||
            lower.contains("未识别") ||
            lower.contains("无法识别")
    }

    private fun fieldValue(block: String, keys: List<String>): String {
        val rows = block.lines().map { it.trim() }.filter { it.isNotBlank() }
        val value = rows.firstOrNull { row ->
            val lower = row.lowercase(Locale.US)
            keys.any { key ->
                val token = key.lowercase(Locale.US)
                lower.startsWith("$token:") || lower.startsWith("$token：")
            }
        }?.substringAfter(":")
            ?.substringAfter("：")
            ?.trim()
            .orEmpty()
        return normalizeLine(value)
    }

    private fun parseScore(raw: String): Int {
        val number = Regex("""-?\d+""").find(raw)?.value?.toIntOrNull() ?: 75
        return number.coerceIn(0, 100)
    }

    private fun payloadString(payload: Map<String, Any>, key: String): String? {
        return payload[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
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

    private fun normalizeLine(raw: String): String {
        return raw
            .trim()
            .replace("\u0000", "")
            .replace(Regex("\\s+"), " ")
    }

    private fun looksTooTechnical(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (lower.startsWith("1m context tick")) return true
        return Regex("""\b(events=|category=|source=|payload|lat=|lon=|wifi=|cellular=|backend|strategy|model)\b""")
            .containsMatchIn(lower)
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        return needles.any { text.contains(it, ignoreCase = true) }
    }

    private fun dateToken(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
    }
}
