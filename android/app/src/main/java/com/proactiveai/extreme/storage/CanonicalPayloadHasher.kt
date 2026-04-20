package com.proactiveai.extreme.storage

import java.security.MessageDigest

object CanonicalPayloadHasher {
    fun canonicalJson(payload: Map<String, Any?>): String {
        return renderMap(payload)
    }

    fun sha256(payload: Map<String, Any?>): String {
        return sha256Hex(canonicalJson(payload))
    }

    fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun renderMap(payload: Map<String, Any?>): String {
        val rendered = payload.entries
            .mapNotNull { (key, value) ->
                val renderedValue = renderValue(value) ?: return@mapNotNull null
                key to renderedValue
            }
            .sortedBy { it.first }
            .joinToString(separator = ",") { (key, value) ->
                "${quoteString(key)}:$value"
            }
        return "{$rendered}"
    }

    private fun renderList(items: List<*>): String {
        val rendered = items.mapNotNull { renderValue(it) }
        return rendered.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderValue(value: Any?): String? {
        return when (value) {
            null -> null
            is Map<*, *> -> {
                val normalized = linkedMapOf<String, Any?>()
                value.forEach { (key, item) ->
                    val normalizedKey = key?.toString() ?: return@forEach
                    normalized[normalizedKey] = item
                }
                renderMap(normalized)
            }
            is List<*> -> renderList(value)
            is String -> quoteString(value)
            is CharSequence -> quoteString(value.toString())
            is Boolean -> value.toString()
            is Number -> renderNumber(value)
            else -> quoteString(value.toString())
        }
    }

    private fun quoteString(value: String): String {
        val escaped = buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
        return "\"$escaped\""
    }

    private fun renderNumber(value: Number): String {
        return when (value) {
            is Byte, is Short, is Int, is Long -> value.toLong().toString()
            is Float, is Double -> {
                val doubleValue = value.toDouble()
                if (!doubleValue.isFinite()) {
                    quoteString(value.toString())
                } else {
                    val raw = value.toString()
                    if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
                        java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()
                    } else {
                        raw
                    }
                }
            }
            else -> value.toString()
        }
    }
}
