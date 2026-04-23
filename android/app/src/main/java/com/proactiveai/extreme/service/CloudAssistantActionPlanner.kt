package com.proactiveai.extreme.service

import android.content.Context
import com.proactiveai.extreme.BuildConfig
import com.proactiveai.extreme.app.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object CloudAssistantActionPlanner {
    private const val MODEL_ID = "gpt-4o-mini"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

    data class SessionRequest(
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
        val localScenario: String,
        val localActionPlan: String,
        val eventDigest: String,
    )

    data class Outcome(
        val status: String,
        val attempted: Boolean,
        val modelLabel: String,
        val guessedUserScenario: String,
        val actionPlan: String,
        val comparison: String,
        val prompt: String,
        val rawResponse: String,
        val detail: String,
        val latencyMs: Long,
    )

    private data class ParsedOutput(
        val scenario: String,
        val actionPlan: String,
        val comparison: String,
    )

    suspend fun generate(
        context: Context,
        request: SessionRequest,
    ): Outcome {
        if (AppPrefs.isGlobalLockEnabled(context)) {
            return Outcome(
                status = "blocked",
                attempted = false,
                modelLabel = MODEL_ID,
                guessedUserScenario = "",
                actionPlan = "",
                comparison = "",
                prompt = "",
                rawResponse = "",
                detail = "Global lock enabled: cloud action planning is blocked.",
                latencyMs = 0L,
            )
        }

        val apiKey = resolveApiKey(context)
        if (apiKey.isBlank()) {
            return Outcome(
                status = "missing_key",
                attempted = false,
                modelLabel = MODEL_ID,
                guessedUserScenario = "",
                actionPlan = "",
                comparison = "",
                prompt = "",
                rawResponse = "",
                detail = "OpenAI API key missing. Set it in Models -> Model Config.",
                latencyMs = 0L,
            )
        }

        val prompt = buildPrompt(request)
        val startedAt = System.currentTimeMillis()
        return kotlin.runCatching {
            requestCompletion(apiKey = apiKey, prompt = prompt)
        }.fold(
            onSuccess = { raw ->
                val latencyMs = System.currentTimeMillis() - startedAt
                val parsed = parseOutput(
                    output = raw,
                    localScenario = request.localScenario,
                    localActionPlan = request.localActionPlan,
                )
                val candidatePlan = parsed.actionPlan.takeIf { !isWeakActionPlan(it) }
                    ?: buildCloudFallbackActionPlan(request)
                val normalizedActionPlan = candidatePlan
                    .ifBlank { request.localActionPlan }
                    .take(520)
                val normalizedScenario = parsed.scenario
                    .takeIf { !isWeakScenario(it) }
                    .orEmpty()
                    .ifBlank { request.localScenario }
                    .take(260)
                val comparison = parsed.comparison
                    .ifBlank {
                        buildComparisonFallback(
                            localActionPlan = request.localActionPlan,
                            cloudActionPlan = normalizedActionPlan,
                        )
                    }
                    .take(360)

                Outcome(
                    status = "success",
                    attempted = true,
                    modelLabel = MODEL_ID,
                    guessedUserScenario = normalizedScenario,
                    actionPlan = normalizedActionPlan,
                    comparison = comparison,
                    prompt = prompt,
                    rawResponse = raw.take(2_000),
                    detail = "Cloud action plan generated.",
                    latencyMs = latencyMs,
                )
            },
            onFailure = { throwable ->
                Outcome(
                    status = "error",
                    attempted = true,
                    modelLabel = MODEL_ID,
                    guessedUserScenario = "",
                    actionPlan = "",
                    comparison = "",
                    prompt = prompt,
                    rawResponse = "",
                    detail = throwable.message.orEmpty().ifBlank { "Unknown cloud action plan error." },
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
            },
        )
    }

    private suspend fun requestCompletion(
        apiKey: String,
        prompt: String,
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject().apply {
            put("model", MODEL_ID)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            """
                            You are a high-precision proactive assistant planner.
                            Focus on personal daily-life assistance first, not generic office productivity.
                            Use only evidence provided in context.
                            Produce short, concrete next actions that can be executed within 5-30 minutes.
                            """.trimIndent(),
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            })
            put("max_tokens", 550)
        }

        try {
            connection.outputStream.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                    writer.flush()
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                InputStreamReader(input, Charsets.UTF_8).readText()
            }.orEmpty()

            if (code !in 200..299) {
                error("Cloud plan request failed: http=$code body=${responseBody.take(320)}")
            }

            val json = JSONObject(responseBody)
            val text = extractMessageContent(json)
            if (text.isBlank()) {
                error("Cloud plan returned empty content.")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun extractMessageContent(json: JSONObject): String {
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() <= 0) return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""

        when (val content = message.opt("content")) {
            is String -> return content.trim()
            is JSONArray -> {
                val text = buildString {
                    for (i in 0 until content.length()) {
                        val part = content.opt(i) ?: continue
                        when (part) {
                            is JSONObject -> {
                                val chunk = part.optString("text")
                                    .ifBlank { part.optString("content") }
                                    .trim()
                                if (chunk.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(chunk)
                                }
                            }
                            is String -> {
                                val chunk = part.trim()
                                if (chunk.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(chunk)
                                }
                            }
                        }
                    }
                }
                if (text.isNotBlank()) return text
            }
        }
        return message.optString("content").trim()
    }

    private fun buildPrompt(request: SessionRequest): String {
        return """
            Build a stronger cloud action plan for this 15-minute assistant session.
            Keep suggestions highly specific to this user context.
            Reject generic actions unless there is explicit evidence.
            Prioritize the following classes:
            1) Deep research topic (finance/investment/company/person/project).
            2) Daily-life decision support with direct links/directions (restaurant, milk tea, haircut, errands).
            3) Emotional support (angry/sad/anxious/proud cues).
            4) Health nudges from behavior/context (sedentary, hydration, food/rest timing).
            5) Explicit command execution (investigate/schedule/book/remind).

            Session: ${request.sessionLabel}
            Event count: ${request.eventCount}
            Sparkling marker: ${if (request.sparklingSession) "YES (${request.sparklingSignalCount}, triggers=${request.sparklingTriggers})" else "NO"}
            Speech: ${request.speechSummary}
            Position: ${request.positionSummary}
            Indoor/Outdoor: ${request.indoorOutdoor}
            Location: ${request.locationLabel}
            Calendar: ${request.calendarSummary}
            Event digest:
            ${request.eventDigest}

            Local model scenario:
            ${request.localScenario}

            Local model action plan:
            ${request.localActionPlan}

            Requirements:
            1) Infer the most likely real user scenario in one sentence with mood/state.
            2) Provide exactly 3 action steps, each concrete and immediately useful.
            3) Each step should include a concrete target and direct entry point (URL, map/search query, or exact app/module action).
            4) Prefer life-assistance tasks (food, travel, errands, social follow-up, health, booking) when evidence supports it.
            5) Avoid repetitive office defaults (open calendar/open github/check email) unless directly supported by evidence.
            6) Add a short comparison note explaining why cloud plan is better or when it is not better.

            Output in this exact plain-text format:
            Cloud Guessed User Scenario: <one sentence>
            Cloud Action Plan:
            - <step 1>
            - <step 2>
            - <step 3>
            Comparison vs Local:
            - <short note>
        """.trimIndent()
    }

    private fun buildCloudFallbackActionPlan(request: SessionRequest): String {
        val speechLower = request.speechSummary.lowercase(Locale.US)
        val locationHint = request.locationLabel
            .takeIf { it.isNotBlank() && !it.startsWith("Unknown", ignoreCase = true) }
            ?.take(48)
            ?: "near me"
        val actions = mutableListOf<String>()

        if (containsAny(speechLower, listOf("stock", "investment", "portfolio", "crypto", "股票", "投资", "项目", "research", "investigate"))) {
            val link = "https://www.google.com/search?udm=50&q=${encodeQuery("${request.speechSummary.take(80)} deep research latest analysis")}"
            actions += "Run deep research now and produce a 1-page brief (thesis, risks, catalysts, key people). Link: $link."
        }
        if (containsAny(speechLower, listOf("milk tea", "boba", "奶茶", "restaurant", "餐厅", "haircut", "barber", "salon", "理发", "buy", "shop", "买"))) {
            val maps = "https://www.google.com/maps/search/?api=1&query=${encodeQuery("$locationHint restaurant milk tea barber")}"
            actions += "Solve this daily-life task end-to-end by shortlisting options with ratings and opening directions: $maps."
        }
        if (containsAny(speechLower, listOf("angry", "frustrated", "sad", "lonely", "proud", "生气", "难过", "得意"))) {
            actions += "Emotional support mode: acknowledge the current emotion, offer a grounded next step, and open a short check-in conversation now."
        }
        if (containsAny(speechLower, listOf("tired", "sleep", "rest", "water", "hungry", "累", "困", "饿")) || request.positionSummary.contains("motion=still", ignoreCase = true)) {
            actions += "Health nudge: stand up, drink water, and do a 3-5 minute walk now; then set a reminder for the next break."
        }
        if (containsAny(speechLower, listOf("schedule", "meeting", "set", "book", "reserve", "remind", "安排", "会议", "预订", "提醒"))) {
            actions += "Execute the explicit command by generating the exact checklist (who/when/where/output) and starting step 1 immediately."
        }

        if (actions.isEmpty()) {
            val link = "https://www.google.com/search?udm=50&q=${encodeQuery("${request.speechSummary.take(80)} best next step now")}"
            actions += "Convert the strongest context signal into one immediate action and open this AI search entry: $link."
        }
        return actions.distinct().take(3).joinToString(" | ")
    }

    private fun parseOutput(
        output: String,
        localScenario: String,
        localActionPlan: String,
    ): ParsedOutput {
        val lines = output
            .trim()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
        if (lines.isEmpty()) {
            return ParsedOutput(
                scenario = localScenario,
                actionPlan = localActionPlan,
                comparison = "",
            )
        }

        var section = "scenario"
        var scenario = ""
        val actionLines = mutableListOf<String>()
        val comparisonLines = mutableListOf<String>()

        lines.forEach { line ->
            val lower = line.lowercase(Locale.US)
            when {
                lower.startsWith("cloud guessed user scenario:") ||
                    lower.startsWith("guessed user scenario:") ||
                    lower.startsWith("scenario:") -> {
                        section = "scenario"
                        scenario = line.substringAfter(":").trim().ifBlank { scenario }
                        return@forEach
                    }

                lower.startsWith("cloud action plan:") ||
                    lower.startsWith("action plan:") ||
                    lower.startsWith("plan:") -> {
                        section = "action"
                        val inline = line.substringAfter(":").trim()
                        if (inline.isNotBlank()) actionLines += normalizeLine(inline)
                        return@forEach
                    }

                lower.startsWith("comparison vs local:") ||
                    lower.startsWith("comparison:") ||
                    lower.startsWith("why better than local:") -> {
                        section = "compare"
                        val inline = line.substringAfter(":").trim()
                        if (inline.isNotBlank()) comparisonLines += normalizeLine(inline)
                        return@forEach
                    }
            }

            when (section) {
                "scenario" -> if (scenario.isBlank()) scenario = normalizeLine(line)
                "action" -> actionLines += normalizeLine(line)
                "compare" -> comparisonLines += normalizeLine(line)
            }
        }

        val normalizedActions = actionLines
            .asSequence()
            .map { normalizeLine(it) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .take(3)
            .toList()
        val actionPlan = when {
            normalizedActions.isNotEmpty() -> normalizedActions.joinToString(" | ")
            else -> localActionPlan
        }

        val comparison = comparisonLines
            .asSequence()
            .map { normalizeLine(it) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .take(2)
            .joinToString(" | ")

        return ParsedOutput(
            scenario = scenario.ifBlank { localScenario },
            actionPlan = actionPlan,
            comparison = comparison,
        )
    }

    private fun buildComparisonFallback(
        localActionPlan: String,
        cloudActionPlan: String,
    ): String {
        val localScore = actionSpecificityScore(localActionPlan)
        val cloudScore = actionSpecificityScore(cloudActionPlan)
        return when {
            cloudScore > localScore + 8 ->
                "Cloud plan is more concrete and executable for this context."
            localScore > cloudScore + 8 ->
                "Local plan is already as specific as cloud for this context."
            else ->
                "Local and cloud plans are similar; keep whichever feels more actionable."
        }
    }

    private fun isWeakScenario(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (text.length < 24) return true
        return containsAny(
            lower,
            listOf(
                "insufficient context",
                "unknown",
                "no clear",
                "keep collecting",
            ),
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
                "continue monitoring",
                "wait for more context",
                "open calendar",
                "check calendar",
                "open github",
                "review notes",
                "generic",
            ),
        )
        return (generic && !hasDirectEntry)
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        return needles.any { text.contains(it, ignoreCase = true) }
    }

    private fun encodeQuery(input: String): String {
        return java.net.URLEncoder.encode(input, Charsets.UTF_8.name())
    }

    private fun actionSpecificityScore(plan: String): Int {
        val normalized = plan.lowercase(Locale.US)
        if (normalized.isBlank()) return 0
        var score = normalized.length.coerceAtMost(260) / 8
        if (normalized.contains("http://") || normalized.contains("https://")) score += 12
        if (normalized.contains("search") || normalized.contains("搜索")) score += 8
        if (normalized.contains("book") || normalized.contains("reserve") || normalized.contains("预订")) score += 8
        if (normalized.contains("nearby") || normalized.contains("附近") || normalized.contains("location")) score += 6
        if (Regex("""\d""").containsMatchIn(normalized)) score += 4
        if (normalized.contains("|")) score += 4
        return score
    }

    private fun normalizeLine(line: String): String {
        return line
            .replace(Regex("^\\s*[-*•]+\\s*"), "")
            .replace(Regex("^\\s*\\d+[\\).]\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resolveApiKey(context: Context): String {
        return AppPrefs.getOpenAiApiKey(context)
            .ifBlank { BuildConfig.OPENAI_API_KEY.orEmpty() }
            .trim()
    }
}
