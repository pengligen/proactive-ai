package com.proactiveai.extreme.storage

import com.proactiveai.extreme.core.context.ContextEvent
import java.util.Locale

interface CollectorStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class InMemoryCollectorStateStore : CollectorStateStore {
    private val data = linkedMapOf<String, String>()

    override fun get(key: String): String? = data[key]

    override fun put(key: String, value: String) {
        data[key] = value
    }
}

object EventFilterEngine {
    private const val NOTIFICATION_DEDUPE_WINDOW_MS = 5 * 60 * 1000L
    private const val MIN_AUDIO_TRANSCRIPT_CHARS = 8

    private val droppedCategories = setOf(
        "context_log",
        "audio_gate",
        "model_io",
        "capability_status",
    )

    private val notificationBlacklistPackages = setOf(
        "com.github.kr328.clash",
        "com.xiaomi.market",
    )

    private val statefulCategories = setOf(
        "location",
        "connectivity",
        "device_state",
        "app_usage",
        "sensor",
    )

    fun filter(event: ContextEvent, state: CollectorStateStore): ContextEvent? {
        val category = event.category.lowercase(Locale.US)
        if (category in droppedCategories) return null

        val sanitized = event.copy(payload = sanitizePayload(event.payload))

        return when (category) {
            "notification" -> filterNotification(sanitized, state)
            "audio" -> filterAudio(sanitized)
            in statefulCategories -> filterStateful(sanitized, state)
            else -> sanitized
        }
    }

    private fun filterNotification(
        event: ContextEvent,
        state: CollectorStateStore,
    ): ContextEvent? {
        val packageName = stringValue(event.payload["packageName"]).lowercase(Locale.US)
        if (packageName in notificationBlacklistPackages) return null

        val ongoing = booleanValue(event.payload["ongoing"])
        val clearable = booleanValue(event.payload["clearable"])
        if (ongoing && !clearable) return null
        if (!clearable && looksLikeRunningServiceNotification(event)) return null

        val signature = listOf(
            packageName,
            stringValue(event.payload["key"]),
            stringValue(event.payload["title"]),
            stringValue(event.payload["text"]),
        )
            .joinToString("|")
            .trim()
            .lowercase(Locale.US)

        if (signature.isBlank()) return event

        val stateKey = "notification:$signature"
        val previous = state.get(stateKey)?.toLongOrNull()
        if (previous != null && event.occurredAt - previous < NOTIFICATION_DEDUPE_WINDOW_MS) {
            return null
        }
        state.put(stateKey, event.occurredAt.toString())
        return event
    }

    private fun looksLikeRunningServiceNotification(event: ContextEvent): Boolean {
        val haystack = listOf(
            event.summary,
            stringValue(event.payload["title"]),
            stringValue(event.payload["text"]),
        ).joinToString(" ").lowercase(Locale.US)

        return haystack.contains("正在运行")
            || haystack.contains("running")
            || haystack.contains("停止应用")
            || haystack.contains("stop app")
    }

    private fun filterAudio(event: ContextEvent): ContextEvent? {
        val transcript = bestTranscript(event.payload)
        if (transcript.length < MIN_AUDIO_TRANSCRIPT_CHARS) return null
        return event.copy(
            summary = transcript.take(140),
            payload = event.payload.toMutableMap().apply {
                put("transcript", transcript)
            },
        )
    }

    private fun filterStateful(
        event: ContextEvent,
        state: CollectorStateStore,
    ): ContextEvent? {
        val signature = buildStateSignature(event)
        if (signature.isBlank()) return event
        val stateKey = "state:${event.category.lowercase(Locale.US)}:${event.source.lowercase(Locale.US)}"
        val previous = state.get(stateKey)
        if (previous == signature) {
            return null
        }
        state.put(stateKey, signature)
        return event
    }

    private fun buildStateSignature(event: ContextEvent): String {
        val payload = event.payload
        return when (event.category.lowercase(Locale.US)) {
            "location" -> listOf(
                stringValue(payload["city"]),
                stringValue(payload["geofence"]),
                stringValue(payload["motionState"]),
                roundedNumber(payload["latitude"]),
                roundedNumber(payload["longitude"]),
            ).joinToString("|")
            "connectivity" -> listOf(
                stringValue(payload["wifiSsid"]),
                booleanValue(payload["wifi"]).toString(),
                booleanValue(payload["cellular"]).toString(),
                booleanValue(payload["internet"]).toString(),
                booleanValue(payload["vpn"]).toString(),
                booleanValue(payload["bluetoothEnabled"]).toString(),
            ).joinToString("|")
            "device_state" -> listOf(
                booleanValue(payload["charging"]).toString(),
                stringValue(payload["batteryBucket"]),
                booleanValue(payload["interactive"]).toString(),
                stringValue(payload["ringerMode"]),
            ).joinToString("|")
            "app_usage" -> listOf(
                stringValue(payload["topPackage"]),
                stringValue(payload["appLabel"]),
            ).joinToString("|")
            "sensor" -> listOf(
                stringValue(payload["activityState"]),
                stringValue(payload["ambientState"]),
                stringValue(payload["stillness"]),
            ).joinToString("|")
            else -> event.summary.trim()
        }
    }

    private fun sanitizePayload(input: Map<String, Any>): Map<String, Any> {
        val output = linkedMapOf<String, Any>()
        input.forEach { (key, value) ->
            if (key == "wavPath" || key == "metaPath") return@forEach
            val normalized = sanitizeValue(value) ?: return@forEach
            output[key] = normalized
        }
        return output
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is Map<*, *> -> {
                val nested = linkedMapOf<String, Any>()
                value.forEach { (key, item) ->
                    val normalizedKey = key?.toString() ?: return@forEach
                    if (normalizedKey == "wavPath" || normalizedKey == "metaPath") return@forEach
                    val normalizedValue = sanitizeValue(item) ?: return@forEach
                    nested[normalizedKey] = normalizedValue
                }
                nested
            }
            is List<*> -> value.mapNotNull { sanitizeValue(it) }
            else -> value
        }
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

    private fun booleanValue(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun roundedNumber(value: Any?): String {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return ""
        return String.format(Locale.US, "%.3f", number)
    }
}
