package com.proactiveai.extreme.storage

import com.proactiveai.extreme.core.context.ContextEvent
import java.util.Locale
import java.util.UUID

object ItemDerivationEngine {
    private const val DEFAULT_ITEM_TTL_MS = 24 * 60 * 60 * 1000L

    fun derive(event: ContextEvent): List<MobileSyncItem> {
        return when (event.category.lowercase(Locale.US)) {
            "assistant_session" -> listOfNotNull(
                assistantSessionItem(event),
                assistantMomentItem(event),
            )
            "daily_focus" -> dailyFocusItems(event)
            "notification" -> listOfNotNull(notificationItem(event))
            "calendar" -> listOfNotNull(calendarItem(event))
            "communication" -> listOfNotNull(communicationItem(event))
            "audio" -> listOfNotNull(audioSessionItem(event))
            "location", "connectivity", "device_state", "app_usage", "sensor" -> emptyList()
            else -> emptyList()
        }
    }

    private fun assistantSessionItem(event: ContextEvent): MobileSyncItem? {
        val sessionId = stringValue(event.payload["sessionId"]).ifBlank { return null }
        val sessionLabel = stringValue(event.payload["sessionLabel"]).ifBlank { "Assistant session" }
        val scenario = stringValue(event.payload["guessedUserScenario"])
        val actionPlan = stringValue(event.payload["actionPlan"])
        val summary = listOf(scenario, actionPlan)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .ifBlank { event.summary }
        return MobileSyncItem(
            itemId = UUID.randomUUID().toString(),
            itemType = "session_summary",
            title = sessionLabel,
            summary = summary.take(220),
            payload = event.payload,
            salience = 0.85,
            confidence = 0.8,
            dedupeKey = "assistant_session:$sessionId",
            occurredAt = event.occurredAt,
            expiresAt = event.occurredAt + minOf(event.ttlSeconds * 1000L, DEFAULT_ITEM_TTL_MS),
        )
    }

    private fun assistantMomentItem(event: ContextEvent): MobileSyncItem? {
        val sessionId = stringValue(event.payload["sessionId"]).ifBlank { return null }
        val scenario = stringValue(event.payload["guessedUserScenario"])
            .ifBlank { stringValue(event.payload["suggestion"]) }
            .ifBlank { event.summary }
            .takeIf { it.isNotBlank() }
            ?: return null
        val location = stringValue(event.payload["locationLabel"])
            .takeUnless { it.equals("Unknown location", ignoreCase = true) }
        val calendar = stringValue(event.payload["calendarSummary"])
            .takeUnless { it.equals("No meeting signal in this session", ignoreCase = true) }
        val summary = listOfNotNull(
            scenario.takeIf { it.isNotBlank() },
            location?.let { "Location: $it" },
            calendar?.let { "Calendar: $it" },
        ).joinToString(" | ")

        return MobileSyncItem(
            itemId = UUID.randomUUID().toString(),
            itemType = "moment",
            title = scenario.take(120),
            summary = summary.take(220),
            payload = linkedMapOf<String, Any>().apply {
                put("sessionId", sessionId)
                put("sessionLabel", stringValue(event.payload["sessionLabel"]))
                put("guessedUserScenario", scenario)
                location?.let { put("locationLabel", it) }
                calendar?.let { put("calendarSummary", it) }
            },
            salience = 0.78,
            confidence = 0.74,
            dedupeKey = "assistant_moment:$sessionId",
            occurredAt = event.occurredAt,
            expiresAt = event.occurredAt + minOf(event.ttlSeconds * 1000L, DEFAULT_ITEM_TTL_MS),
        )
    }

    private fun dailyFocusItems(event: ContextEvent): List<MobileSyncItem> {
        val dateToken = stringValue(event.payload["dateToken"]).ifBlank { "unknown" }
        val rawItems = event.payload["items"] as? List<*> ?: return emptyList()
        return rawItems.mapIndexedNotNull { index, item ->
            val row = item as? Map<*, *> ?: return@mapIndexedNotNull null
            val rank = intValue(row["rank"]).takeIf { it > 0 } ?: (index + 1)
            val title = stringValue(row["title"]).ifBlank { return@mapIndexedNotNull null }
            val reason = stringValue(row["reason"])
            val action = stringValue(row["action"])
            val evidence = stringValue(row["evidence"])
            val summary = listOf(reason, action, evidence.takeIf { it.isNotBlank() }?.let { "Evidence: $it" })
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .ifBlank { event.summary }
            val salience = (intValue(row["importance"]) + intValue(row["urgency"]) + intValue(row["missRisk"])) / 300.0
            MobileSyncItem(
                itemId = UUID.randomUUID().toString(),
                itemType = "attention_item",
                title = title.take(120),
                summary = summary.take(220),
                payload = linkedMapOf<String, Any>().apply {
                    putAll(row.entries.associate { it.key.toString() to (it.value ?: "") })
                    put("dateToken", dateToken)
                },
                salience = salience.coerceIn(0.0, 1.0),
                confidence = 0.9,
                dedupeKey = "daily_focus:$dateToken:$rank",
                occurredAt = event.occurredAt,
                expiresAt = event.occurredAt + minOf(event.ttlSeconds * 1000L, DEFAULT_ITEM_TTL_MS),
            )
        }
    }

    private fun notificationItem(event: ContextEvent): MobileSyncItem? {
        if (!looksActionableNotification(event)) return null
        val title = stringValue(event.payload["title"]).ifBlank {
            stringValue(event.payload["packageName"]).ifBlank { "Notification" }
        }
        val text = stringValue(event.payload["text"]).ifBlank { event.summary }
        val packageName = stringValue(event.payload["packageName"])
        val key = stringValue(event.payload["key"]).ifBlank { event.eventId }
        return MobileSyncItem(
            itemId = UUID.randomUUID().toString(),
            itemType = "attention_item",
            title = title.take(120),
            summary = text.take(220),
            payload = event.payload,
            salience = 0.62,
            confidence = 0.6,
            dedupeKey = "notification:$packageName:$key",
            occurredAt = event.occurredAt,
            expiresAt = event.occurredAt + minOf(event.ttlSeconds * 1000L, DEFAULT_ITEM_TTL_MS),
        )
    }

    private fun calendarItem(event: ContextEvent): MobileSyncItem? {
        val eventId = stringValue(event.payload["eventId"]).ifBlank { return null }
        val state = stringValue(event.payload["state"]).ifBlank { "unknown" }
        return attentionItem(
            event = event,
            dedupeKey = "calendar:$eventId:$state",
        )
    }

    private fun communicationItem(event: ContextEvent): MobileSyncItem? {
        val smsId = stringValue(event.payload["smsId"])
        if (smsId.isNotBlank()) {
            return attentionItem(event, dedupeKey = "communication:sms:$smsId")
        }

        val callId = stringValue(event.payload["callId"])
        if (callId.isNotBlank()) {
            val callType = stringValue(event.payload["type"]).ifBlank { "unknown" }
            return attentionItem(event, dedupeKey = "communication:call:$callId:$callType")
        }

        val contactsCount = stringValue(event.payload["contactsCount"])
        if (contactsCount.isNotBlank()) {
            val delta = intValue(event.payload["deltaFromLast"])
            if (delta <= 0) return null
            return attentionItem(event, dedupeKey = "communication:contacts:$contactsCount")
        }

        val address = stringValue(event.payload["address"])
        if (address.isNotBlank()) {
            return attentionItem(event, dedupeKey = "communication:address:$address")
        }

        val number = stringValue(event.payload["number"])
        if (number.isNotBlank()) {
            return attentionItem(event, dedupeKey = "communication:number:$number")
        }

        return null
    }

    private fun audioSessionItem(event: ContextEvent): MobileSyncItem? {
        val transcript = bestTranscript(event.payload)
        if (transcript.length < 24) return null
        return MobileSyncItem(
            itemId = UUID.randomUUID().toString(),
            itemType = "session_summary",
            title = "Voice summary",
            summary = transcript.take(220),
            payload = event.payload,
            salience = 0.7,
            confidence = 0.75,
            dedupeKey = "audio:${event.eventId}",
            occurredAt = event.occurredAt,
            expiresAt = event.occurredAt + minOf(event.ttlSeconds * 1000L, DEFAULT_ITEM_TTL_MS),
        )
    }

    private fun attentionItem(event: ContextEvent, dedupeKey: String): MobileSyncItem {
        return MobileSyncItem(
            itemId = UUID.randomUUID().toString(),
            itemType = "attention_item",
            title = event.summary.take(120),
            summary = event.summary.take(220),
            payload = event.payload,
            salience = 0.65,
            confidence = 0.65,
            dedupeKey = dedupeKey,
            occurredAt = event.occurredAt,
            expiresAt = event.occurredAt + minOf(event.ttlSeconds * 1000L, DEFAULT_ITEM_TTL_MS),
        )
    }

    private fun looksActionableNotification(event: ContextEvent): Boolean {
        val haystack = listOf(
            stringValue(event.payload["title"]),
            stringValue(event.payload["text"]),
            event.summary,
        ).joinToString(" ").lowercase(Locale.US)

        val keywords = listOf(
            "message", "reply", "mail", "email", "meeting", "calendar", "delivery", "order", "payment",
            "security", "verify", "code", "短信", "消息", "回复", "订单", "支付", "会议", "日程", "验证码",
        )
        return keywords.any { haystack.contains(it) }
    }

    private fun bestTranscript(payload: Map<String, Any>): String {
        return listOf("refinedTranscript", "stitchedTranscript", "transcript")
            .map { stringValue(payload[it]) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun stringValue(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is CharSequence -> value.toString().trim()
            else -> value?.toString()?.trim().orEmpty()
        }
    }

    private fun intValue(value: Any?): Int {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }
}
