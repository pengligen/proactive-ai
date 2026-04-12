package com.proactiveai.extreme.orchestrator

import android.content.Context
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.model.RiskLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

interface OrchestratorGateway {
    fun health(): Result<Boolean>
    fun ingestEvents(payload: EventBatchPayload): Result<Int>
    fun requestPlan(request: PlanRequestPayload): Result<ActionPlanPayload>
    fun submitHitlDecision(payload: HitlDecisionPayload): Result<Boolean>
    fun executeStep(payload: ActionExecutionRequestPayload): Result<ActionExecutionResultPayload>
    fun connectorStatus(userId: String): Result<List<ConnectorStatusPayload>>
    fun authorizeConnector(connector: String, userId: String): Result<ConnectorAuthorizeResultPayload>
    fun disconnectConnector(connector: String, userId: String): Result<ConnectorAuthorizeResultPayload>
}

class HttpOrchestratorGateway(
    private val baseUrl: String = OrchestratorConfig.baseUrl(),
    private val appContext: Context? = null,
) : OrchestratorGateway {

    private val lockBlockMessage = "Global lock enabled: outbound cloud/orchestrator calls are blocked."

    override fun health(): Result<Boolean> {
        if (isGlobalLockEnabled()) return lockFailure()
        return runCatching {
            val (code, body) = call("GET", "$baseUrl/health", null)
            code in 200..299 && JSONObject(body).optString("status") == "ok"
        }
    }

    override fun ingestEvents(payload: EventBatchPayload): Result<Int> {
        if (isGlobalLockEnabled()) return lockFailure()
        return runCatching {
            val body = JSONObject().apply {
                put("userId", payload.userId)
                put("sessionId", payload.sessionId)
                put("events", JSONArray().apply {
                    payload.events.forEach { event ->
                        put(
                            JSONObject().apply {
                                put("eventId", event.eventId)
                                put("occurredAt", event.occurredAt)
                                put("source", event.source)
                                put("category", event.category)
                                put("summary", event.summary)
                                put("payload", event.payload.toJsonObject())
                                put("sensitivity", event.sensitivity)
                                put("ttlSeconds", event.ttlSeconds)
                            }
                        )
                    }
                })
            }.toString()

            val (code, responseBody) = call("POST", "$baseUrl/v1/context/events", body)
            if (code !in 200..299) {
                error("Ingest failed with status $code")
            }
            JSONObject(responseBody).optInt("accepted", 0)
        }
    }

    override fun requestPlan(request: PlanRequestPayload): Result<ActionPlanPayload> {
        if (isGlobalLockEnabled()) return lockFailure()
        return runCatching {
            val body = JSONObject().apply {
                put("userId", request.userId)
                put("now", request.now)
                put("contextWindow", JSONArray().apply {
                    request.contextWindow.forEach { event ->
                        put(
                            JSONObject().apply {
                                put("eventId", event.eventId)
                                put("occurredAt", event.occurredAt)
                                put("source", event.source)
                                put("category", event.category)
                                put("summary", event.summary)
                                put("payload", event.payload.toJsonObject())
                                put("sensitivity", event.sensitivity)
                                put("ttlSeconds", event.ttlSeconds)
                            }
                        )
                    }
                })
            }.toString()

            val (code, responseBody) = call("POST", "$baseUrl/v1/proactive/plan", body)
            if (code !in 200..299) {
                error("Plan request failed with status $code")
            }

            val raw = JSONObject(responseBody)
            val steps = raw.optJSONArray("steps").toSteps()
            ActionPlanPayload(
                planId = raw.optString("planId"),
                goal = raw.optString("goal"),
                riskLevel = parseRiskLevel(raw.optString("riskLevel")),
                requiresUserConfirmation = raw.optBoolean("requiresUserConfirmation", false),
                explainWhy = raw.optString("explainWhy"),
                steps = steps,
                raw = raw.toMap(),
            )
        }
    }

    override fun submitHitlDecision(payload: HitlDecisionPayload): Result<Boolean> {
        if (isGlobalLockEnabled()) return lockFailure()
        return runCatching {
            val body = JSONObject().apply {
                put("planId", payload.planId)
                put("approved", payload.approved)
                payload.note?.let { put("note", it) }
            }.toString()

            val (code, responseBody) = call("POST", "$baseUrl/v1/hitl/decision", body)
            if (code !in 200..299) {
                error("HITL decision failed with status $code")
            }
            val raw = JSONObject(responseBody)
            raw.optBoolean("stored", false)
        }
    }

    override fun executeStep(payload: ActionExecutionRequestPayload): Result<ActionExecutionResultPayload> {
        if (isGlobalLockEnabled()) return lockFailure()
        return runCatching {
            val body = JSONObject().apply {
                put("planId", payload.planId)
                put("stepId", payload.stepId)
            }.toString()

            val (code, responseBody) = call("POST", "$baseUrl/v1/actions/execute", body)
            if (code !in 200..299) {
                error("Action execute failed with status $code")
            }
            val raw = JSONObject(responseBody)
            ActionExecutionResultPayload(
                status = raw.optString("status", "FAILED"),
                details = raw.optString("details").takeIf { it.isNotBlank() },
            )
        }
    }

    override fun connectorStatus(userId: String): Result<List<ConnectorStatusPayload>> {
        if (isGlobalLockEnabled()) return lockFailure()
        return runCatching {
            val (code, responseBody) = call("GET", "$baseUrl/v1/connectors/status?userId=$userId", null)
            if (code !in 200..299) {
                error("Connector status failed with status $code")
            }
            val raw = JSONObject(responseBody)
            val connectors = raw.optJSONArray("connectors")
            if (connectors == null) {
                emptyList()
            } else {
                (0 until connectors.length()).mapNotNull { index ->
                    val item = connectors.optJSONObject(index) ?: return@mapNotNull null
                    ConnectorStatusPayload(
                        connector = item.optString("connector"),
                        connected = item.optBoolean("connected", false),
                        accountLabel = item.optString("accountLabel").takeIf { it.isNotBlank() },
                        lastConnectedAt = item.optLong("lastConnectedAt").takeIf { it > 0L },
                    )
                }
            }
        }
    }

    override fun authorizeConnector(connector: String, userId: String): Result<ConnectorAuthorizeResultPayload> {
        if (isGlobalLockEnabled()) return lockFailure()
        return connectorMutation(endpoint = "authorize", connector = connector, userId = userId)
    }

    override fun disconnectConnector(connector: String, userId: String): Result<ConnectorAuthorizeResultPayload> {
        if (isGlobalLockEnabled()) return lockFailure()
        return connectorMutation(endpoint = "disconnect", connector = connector, userId = userId)
    }

    private fun call(method: String, url: String, requestBody: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/json")

        if (requestBody != null) {
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }.orEmpty()

        connection.disconnect()
        return code to body
    }

    private fun connectorMutation(
        endpoint: String,
        connector: String,
        userId: String,
    ): Result<ConnectorAuthorizeResultPayload> {
        return runCatching {
            val body = JSONObject().apply {
                put("userId", userId)
            }.toString()
            val normalized = connector.lowercase()
            val (code, responseBody) = call(
                "POST",
                "$baseUrl/v1/connectors/$normalized/$endpoint",
                body,
            )
            if (code !in 200..299) {
                error("Connector $endpoint failed with status $code")
            }
            val raw = JSONObject(responseBody)
            ConnectorAuthorizeResultPayload(
                userId = raw.optString("userId"),
                connector = raw.optString("connector"),
                connected = raw.optBoolean("connected", false),
                authUrl = raw.optString("authUrl").takeIf { it.isNotBlank() },
                message = raw.optString("message"),
            )
        }
    }

    private fun isGlobalLockEnabled(): Boolean {
        val context = appContext ?: return false
        return AppPrefs.isGlobalLockEnabled(context)
    }

    private fun <T> lockFailure(): Result<T> {
        return Result.failure(IllegalStateException(lockBlockMessage))
    }

    private fun parseRiskLevel(value: String): RiskLevel {
        return runCatching { RiskLevel.valueOf(value.uppercase()) }.getOrDefault(RiskLevel.MEDIUM)
    }

    private fun JSONArray?.toSteps(): List<ActionStepPayload> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val step = optJSONObject(index) ?: return@mapNotNull null
            ActionStepPayload(
                stepId = step.optString("stepId"),
                connector = step.optString("connector"),
                operation = step.optString("operation"),
                args = (step.optJSONObject("args")?.toMap().orEmpty())
                    .mapNotNull { (key, value) -> if (value != null) key to value else null }
                    .toMap(),
            )
        }
    }
}

fun OrchestratorGateway.requestPlan(userId: String): Result<ActionPlanPayload> {
    return requestPlan(
        PlanRequestPayload(
            userId = userId,
            now = System.currentTimeMillis(),
            contextWindow = emptyList(),
        )
    )
}
