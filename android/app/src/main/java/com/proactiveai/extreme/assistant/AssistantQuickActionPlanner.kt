package com.proactiveai.extreme.assistant

import java.net.URLEncoder
import java.util.Locale

data class AssistantQuickAction(
    val label: String,
    val url: String,
    val confidence: Int = 0,
)

object AssistantQuickActionPlanner {
    private const val MIN_CONFIDENCE = 70
    private const val MAX_ACTIONS = 4
    private const val MAX_LABEL_CHARS = 24

    private val urlRegex = Regex("""https?://[^\s)\]>,]+""", RegexOption.IGNORE_CASE)
    private val splitRegex = Regex("""[\n|]+""")
    private val spaceRegex = Regex("""\s+""")
    private val trailingPunctuationRegex = Regex("""[.,;:!?)]+$""")

    private data class QuickActionCandidate(
        val label: String,
        val url: String,
        val confidence: Int,
        val priority: Int,
    )

    fun inferQuickActions(
        speechSummary: String,
        guessedUserScenario: String,
        actionPlan: String,
        locationLabel: String,
        calendarSummary: String,
        extraText: String = "",
    ): List<AssistantQuickAction> {
        val combined = listOf(
            speechSummary,
            guessedUserScenario,
            actionPlan,
            locationLabel,
            calendarSummary,
            extraText,
        ).joinToString(" ").trim()
        if (combined.isBlank()) return emptyList()

        val strictEvidenceLower = listOf(
            speechSummary,
            calendarSummary,
            locationLabel,
        ).joinToString(" ")
            .lowercase(Locale.US)

        val preferChinese = containsChinese(combined)
        val locationHint = locationLabel
            .takeIf { it.isNotBlank() && !it.startsWith("Unknown", ignoreCase = true) }
            ?.let(::normalizeText)
            ?.take(64)
            ?.ifBlank { null }
            ?: if (preferChinese) "附近" else "near me"

        val allowCalendarDomain = containsAny(
            strictEvidenceLower,
            listOf("meeting", "calendar", "agenda", "appointment", "会议", "日程", "行程", "约"),
        )
        val allowGithubDomain = containsAny(
            strictEvidenceLower,
            listOf("github", "repo", "repository", "pull request", "pr", "issue", "代码", "仓库"),
        )
        val allowGmailDomain = containsAny(
            strictEvidenceLower,
            listOf("gmail", "email", "inbox", "mail", "邮件", "回信"),
        )
        val allowSlackDomain = containsAny(
            strictEvidenceLower,
            listOf("slack", "channel", "dm", "message", "消息", "频道"),
        )

        val steps = extractPlanSteps(actionPlan)
        val candidates = mutableListOf<QuickActionCandidate>()

        fun add(label: String, url: String, confidence: Int, priority: Int) {
            val normalized = normalizeUrl(url) ?: return
            val finalLabel = compactPlannerLabel(label, preferChinese).ifBlank {
                if (preferChinese) "打开链接" else "Open Link"
            }.take(MAX_LABEL_CHARS)
            if (candidates.none { it.url == normalized && it.label == finalLabel }) {
                candidates += QuickActionCandidate(
                    label = finalLabel,
                    url = normalized,
                    confidence = normalizeConfidence(confidence),
                    priority = priority,
                )
            }
        }

        fun addLocalized(
            labelEn: String,
            labelZh: String,
            url: String,
            confidence: Int,
            priority: Int,
        ) {
            add(
                label = if (preferChinese) labelZh else labelEn,
                url = url,
                confidence = confidence,
                priority = priority,
            )
        }

        fun addGoogleAiForStep(step: String, confidence: Int, priority: Int) {
            val query = buildStepQuery(step, locationHint, preferChinese)
            if (query.isBlank()) return
            val label = buildGoogleAiLabel(step, preferChinese)
            add(
                label = label,
                url = googleAiSearchUrl(query),
                confidence = confidence,
                priority = priority,
            )
        }

        fun addGoogleMaps(
            labelEn: String,
            labelZh: String,
            query: String,
            confidence: Int,
            priority: Int,
        ) {
            if (query.isBlank()) return
            addLocalized(
                labelEn = labelEn,
                labelZh = labelZh,
                url = "https://www.google.com/maps/search/?api=1&query=${encode(query)}",
                confidence = confidence,
                priority = priority,
            )
        }

        fun addGoogleAi(
            labelEn: String,
            labelZh: String,
            query: String,
            confidence: Int,
            priority: Int,
        ) {
            if (query.isBlank()) return
            addLocalized(
                labelEn = labelEn,
                labelZh = labelZh,
                url = googleAiSearchUrl(query),
                confidence = confidence,
                priority = priority,
            )
        }

        if (steps.isEmpty()) {
            val fallbackQuery = buildFallbackQuery(
                speechSummary = speechSummary,
                guessedUserScenario = guessedUserScenario,
                actionPlan = actionPlan,
            )
            addGoogleAi(
                labelEn = "Current Need Plan",
                labelZh = "当前需求方案",
                query = fallbackQuery.ifBlank {
                    if (preferChinese) "基于当前上下文给出可执行方案" else "solve current need with concrete next steps"
                },
                confidence = 82,
                priority = 200,
            )
        } else {
            steps.forEachIndexed { index, step ->
                val stepLower = step.lowercase(Locale.US)
                val basePriority = 200 - (index * 10)
                var matched = false
                val mentionsCalendar = containsAny(stepLower, listOf("meeting", "calendar", "会议", "日程", "appointment", "schedule", "安排", "agenda"))
                val mentionsGithub = containsAny(stepLower, listOf("github", "repo", "repository", "pr", "issue", "代码", "仓库"))
                val mentionsGmail = containsAny(stepLower, listOf("gmail", "email", "inbox", "mail", "邮件", "回信"))
                val mentionsSlack = containsAny(stepLower, listOf("slack", "channel", "dm", "message", "消息", "频道"))
                val blockedDomainStep =
                    (mentionsCalendar && !allowCalendarDomain) ||
                        (mentionsGithub && !allowGithubDomain) ||
                        (mentionsGmail && !allowGmailDomain) ||
                        (mentionsSlack && !allowSlackDomain)
                if (blockedDomainStep) {
                    return@forEachIndexed
                }

                extractUrls(step).forEachIndexed { urlIndex, url ->
                    val label = if (preferChinese) {
                        "打开步骤链接 ${urlIndex + 1}"
                    } else {
                        "Open Step Link ${urlIndex + 1}"
                    }
                    add(label = label, url = url, confidence = 96, priority = basePriority - urlIndex)
                    matched = true
                }

                if (containsAny(stepLower, listOf("奶茶", "milk tea", "bubble tea", "boba", "茶饮"))) {
                    matched = true
                    if (containsAny(stepLower, listOf("优惠", "折扣", "券", "deal", "coupon", "discount"))) {
                        addGoogleAi(
                            labelEn = "Find Milk Tea Deals",
                            labelZh = "找奶茶优惠",
                            query = if (preferChinese) "$locationHint 奶茶 优惠 券 团购" else "milk tea deals coupons $locationHint",
                            confidence = 94,
                            priority = basePriority,
                        )
                    }
                    if (containsAny(stepLower, listOf("下单", "点单", "order", "delivery", "外卖", "buy"))) {
                        addGoogleAi(
                            labelEn = "Order Milk Tea",
                            labelZh = "奶茶下单",
                            query = if (preferChinese) "$locationHint 奶茶 外卖 下单" else "order milk tea delivery $locationHint",
                            confidence = 92,
                            priority = basePriority - 1,
                        )
                    }
                    addGoogleMaps(
                        labelEn = "Open Nearby Milk Tea",
                        labelZh = "附近奶茶店",
                        query = if (preferChinese) "$locationHint 奶茶店" else "milk tea $locationHint",
                        confidence = 90,
                        priority = basePriority - 2,
                    )
                }

                if (containsAny(stepLower, listOf("restaurant", "餐厅", "订位", "reservation", "opentable", "resy"))) {
                    matched = true
                    addGoogleMaps(
                        labelEn = "Find Reservable Restaurant",
                        labelZh = "找可订位餐厅",
                        query = if (preferChinese) "$locationHint 可预订 餐厅" else "restaurant reservation $locationHint",
                        confidence = 92,
                        priority = basePriority,
                    )
                    addGoogleAi(
                        labelEn = "Compare Restaurant Options",
                        labelZh = "比对餐厅选项",
                        query = buildStepQuery(step, locationHint, preferChinese),
                        confidence = 88,
                        priority = basePriority - 1,
                    )
                }

                if (containsAny(stepLower, listOf("meeting", "calendar", "会议", "日程", "appointment", "schedule", "安排"))) {
                    matched = true
                    if (containsAny(stepLower, listOf("create", "new", "安排", "新建", "schedule"))) {
                        addLocalized(
                            labelEn = "Create Calendar Event",
                            labelZh = "新建日程",
                            url = "https://calendar.google.com/calendar/u/0/r/eventedit",
                            confidence = 92,
                            priority = basePriority,
                        )
                    }
                    if (containsAny(stepLower, listOf("agenda", "brief", "prep", "材料", "准备", "notes"))) {
                        addGoogleAi(
                            labelEn = "Generate Meeting Prep",
                            labelZh = "生成会议准备",
                            query = buildStepQuery(step, locationHint, preferChinese),
                            confidence = 90,
                            priority = basePriority - 1,
                        )
                    }
                    if (containsAny(stepLower, listOf("open calendar", "查看日历", "agenda", "日程"))) {
                        addLocalized(
                            labelEn = "Open Calendar",
                            labelZh = "打开日历",
                            url = "https://calendar.google.com/calendar/u/0/r",
                            confidence = 84,
                            priority = basePriority - 2,
                        )
                    }
                }

                if (containsAny(stepLower, listOf("flight", "airfare", "机票", "hotel", "酒店", "travel", "trip", "旅行"))) {
                    matched = true
                    if (containsAny(stepLower, listOf("flight", "airfare", "机票"))) {
                        addLocalized(
                            labelEn = "Open Flights",
                            labelZh = "打开机票",
                            url = "https://www.google.com/travel/flights",
                            confidence = 90,
                            priority = basePriority,
                        )
                    }
                    if (containsAny(stepLower, listOf("hotel", "住宿", "酒店"))) {
                        addLocalized(
                            labelEn = "Open Hotels",
                            labelZh = "打开酒店",
                            url = "https://www.google.com/travel/hotels",
                            confidence = 90,
                            priority = basePriority - 1,
                        )
                    }
                    addGoogleAi(
                        labelEn = "Find Best Travel Plan",
                        labelZh = "找最优机酒方案",
                        query = buildStepQuery(step, locationHint, preferChinese),
                        confidence = 88,
                        priority = basePriority - 2,
                    )
                }

                if (containsAny(stepLower, listOf("email", "gmail", "邮件", "inbox", "reply", "回信"))) {
                    if (!allowGmailDomain) return@forEachIndexed
                    matched = true
                    addLocalized(
                        labelEn = "Open Gmail",
                        labelZh = "打开 Gmail",
                        url = "https://mail.google.com/mail/u/0/#inbox",
                        confidence = 88,
                        priority = basePriority,
                    )
                    addGoogleAiForStep(step, confidence = 82, priority = basePriority - 1)
                }

                if (containsAny(stepLower, listOf("slack", "message", "消息", "dm"))) {
                    if (!allowSlackDomain) return@forEachIndexed
                    matched = true
                    addLocalized(
                        labelEn = "Open Slack",
                        labelZh = "打开 Slack",
                        url = "https://app.slack.com/client",
                        confidence = 86,
                        priority = basePriority,
                    )
                    addGoogleAiForStep(step, confidence = 80, priority = basePriority - 1)
                }

                if (containsAny(stepLower, listOf("github", "pr", "issue", "repo", "代码", "debug", "bug"))) {
                    if (!allowGithubDomain) return@forEachIndexed
                    matched = true
                    addLocalized(
                        labelEn = "Open GitHub",
                        labelZh = "打开 GitHub",
                        url = "https://github.com/",
                        confidence = 84,
                        priority = basePriority,
                    )
                    addGoogleAiForStep(step, confidence = 82, priority = basePriority - 1)
                }

                if (containsAny(stepLower, listOf("research", "deep research", "investigate", "调研", "背景", "资料", "分析", "搜索"))) {
                    matched = true
                    addGoogleAiForStep(step, confidence = 90, priority = basePriority)
                }

                if (containsAny(stepLower, listOf("ride", "taxi", "uber", "lyft", "打车", "叫车"))) {
                    matched = true
                    addGoogleMaps(
                        labelEn = "Book a Ride",
                        labelZh = "立刻叫车",
                        query = if (preferChinese) "$locationHint 叫车" else "book ride now $locationHint",
                        confidence = 86,
                        priority = basePriority,
                    )
                }

                if (!matched) {
                    addGoogleAiForStep(step, confidence = 80, priority = basePriority)
                }
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<QuickActionCandidate> { it.confidence }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.label.length }
            )
            .filter { it.confidence >= MIN_CONFIDENCE }
            .filterNot { isGenericAction(it.label, it.url) }
            .distinctBy { it.url }
            .take(MAX_ACTIONS)
            .map { candidate ->
                AssistantQuickAction(
                    label = candidate.label,
                    url = candidate.url,
                    confidence = candidate.confidence,
                )
            }
    }

    fun toPayload(actions: List<AssistantQuickAction>): List<Map<String, String>> {
        return actions
            .mapNotNull { action ->
                val label = action.label.trim()
                val url = normalizeUrl(action.url) ?: return@mapNotNull null
                mapOf(
                    "label" to label.ifBlank { "Open Link" },
                    "url" to url,
                    "confidence" to normalizeConfidence(action.confidence).toString(),
                )
            }
            .distinctBy { it["url"] }
            .take(MAX_ACTIONS)
    }

    fun parseQuickActions(raw: Any?): List<AssistantQuickAction> {
        val parsed = when (raw) {
            is List<*> -> raw.mapNotNull(::parseActionItem)
            is Map<*, *> -> listOfNotNull(parseActionItem(raw))
            else -> emptyList()
        }
        return parsed
            .map { action ->
                val normalized = if (action.confidence <= 0) {
                    action.copy(confidence = inferLegacyConfidence(action))
                } else {
                    action.copy(confidence = normalizeConfidence(action.confidence))
                }
                normalized
            }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .filterNot { isGenericAction(it.label, it.url) }
            .distinctBy { it.url }
            .take(MAX_ACTIONS)
    }

    private fun parseActionItem(item: Any?): AssistantQuickAction? {
        val map = item as? Map<*, *> ?: return null
        val label = map["label"]?.toString().orEmpty().trim()
        val url = normalizeUrl(map["url"]?.toString().orEmpty()) ?: return null
        val confidence = map["confidence"]?.toString()?.toIntOrNull() ?: 0
        return AssistantQuickAction(
            label = label.ifBlank { "Open Link" }.take(MAX_LABEL_CHARS),
            url = url,
            confidence = normalizeConfidence(confidence),
        )
    }

    private fun extractPlanSteps(actionPlan: String): List<String> {
        if (actionPlan.isBlank()) return emptyList()
        return actionPlan
            .split(splitRegex)
            .map(::normalizeActionLine)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .take(6)
    }

    private fun normalizeActionLine(raw: String): String {
        var line = raw.trim()
        if (line.isBlank()) return ""
        val lower = line.lowercase(Locale.US)
        if (lower.startsWith("action plan")) return ""
        line = line.replace(Regex("""^[-*•]+\s*"""), "")
        line = line.replace(Regex("""^\d+[.)]\s*"""), "")
        return line.replace(spaceRegex, " ").trim()
    }

    private fun buildGoogleAiLabel(step: String, preferChinese: Boolean): String {
        val core = normalizeText(step)
            .take(28)
            .trim()
            .ifBlank {
                if (preferChinese) "当前问题" else "current task"
            }
        return core.take(MAX_LABEL_CHARS)
    }

    private fun buildStepQuery(step: String, locationHint: String, preferChinese: Boolean): String {
        val base = normalizeText(step)
        if (base.isBlank()) {
            return if (preferChinese) "基于当前上下文给出可执行方案" else "solve this action plan item with concrete next steps"
        }
        val lower = base.lowercase(Locale.US)
        val needsLocation = containsAny(
            lower,
            listOf(
                "nearby", "near me", "local", "附近", "本地", "restaurant", "奶茶",
                "milk tea", "delivery", "外卖", "reservation", "订位", "ride", "taxi", "叫车"
            ),
        )
        if (needsLocation && !base.contains(locationHint, ignoreCase = true)) {
            return "$base $locationHint"
        }
        return base
    }

    private fun buildFallbackQuery(
        speechSummary: String,
        guessedUserScenario: String,
        actionPlan: String,
    ): String {
        val ordered = listOf(actionPlan, speechSummary, guessedUserScenario)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("No speech", ignoreCase = true) }
        if (ordered.isEmpty()) return ""
        val raw = ordered.first()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace('|', ' ')
            .replace(spaceRegex, " ")
            .trim()
        if (raw.isBlank()) return ""
        return if (containsChinese(raw)) raw.take(30) else raw.split(' ').take(12).joinToString(" ")
    }

    private fun googleAiSearchUrl(query: String): String {
        return "https://www.google.com/search?udm=50&q=${encode(query)}"
    }

    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(spaceRegex, " ")
            .trim()
    }

    private fun compactPlannerLabel(raw: String, preferChinese: Boolean): String {
        var text = normalizeText(raw)
        if (text.isBlank()) return ""
        if (preferChinese) {
            text = text
                .replace("立刻", "")
                .replace("立即", "")
                .replace("当前", "")
                .replace("请", "")
                .replace("AI", "", ignoreCase = true)
                .replace("  ", " ")
                .trim()
            return text
                .replace(Regex("[，。！？、；：]+"), "")
                .take(12)
                .trim()
        }

        text = text
            .replace(Regex("(?i)\\b(ai|immediately|current|context|device|please|kindly|the|for|with)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.length <= MAX_LABEL_CHARS) return text
        val tokens = text.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return text.take(MAX_LABEL_CHARS)
        val compressed = tokens.take(4).joinToString(" ")
        return compressed.take(MAX_LABEL_CHARS).trim()
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        return needles.any { text.contains(it, ignoreCase = true) }
    }

    private fun containsChinese(text: String): Boolean {
        return text.any { ch -> ch.code in 0x4E00..0x9FFF }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun normalizeConfidence(value: Int): Int {
        return value.coerceIn(0, 100)
    }

    private fun inferLegacyConfidence(action: AssistantQuickAction): Int {
        val lower = action.label.lowercase(Locale.US)
        if (isGenericAction(action.label, action.url)) return 55
        return when {
            containsAny(lower, listOf("奶茶", "milk tea", "restaurant", "reservation", "flight", "hotel", "meeting", "calendar")) -> 84
            containsAny(lower, listOf("gmail", "slack", "github")) -> 76
            else -> 72
        }
    }

    private fun isGenericAction(label: String, url: String): Boolean {
        val lower = label.lowercase(Locale.US)
        if (
            containsAny(
                lower,
                listOf(
                    "search intent",
                    "nearby options",
                    "general search",
                    "open link",
                    "搜索当前意图",
                    "查找附近可执行选项",
                    "通用搜索",
                    "打开链接",
                ),
            )
        ) {
            return true
        }
        val urlLower = url.lowercase(Locale.US)
        if (urlLower.contains("best+next+action+near+me+right+now")) return true
        return false
    }

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().replace(trailingPunctuationRegex, "")
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        return null
    }

    private fun extractUrls(text: String): List<String> {
        return urlRegex.findAll(text)
            .map { it.value.trim() }
            .mapNotNull(::normalizeUrl)
            .distinct()
            .toList()
    }
}
