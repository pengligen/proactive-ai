package com.proactiveai.extreme.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BootstrapCursorCodec {
    fun encode(cursor: Map<String, Any?>): String {
        return CanonicalPayloadHasher.canonicalJson(cursor)
    }

    fun decode(encoded: String?): Map<String, Any> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val parsed = Json.parseToJsonElement(encoded)
        require(parsed is JsonObject) { "Bootstrap cursor must be a JSON object." }
        return parsed.toMap()
    }

    private fun JsonObject.toMap(): Map<String, Any> {
        val output = linkedMapOf<String, Any>()
        val keys = keys.toList().sorted()
        keys.forEach { key ->
            val value = get(key).toPlainValue() ?: return@forEach
            output[key] = value
        }
        return output
    }

    private fun JsonArray.toListValue(): List<Any> {
        return mapNotNull { it.toPlainValue() }
    }

    private fun JsonElement?.toPlainValue(): Any? {
        return when (this) {
            null, JsonNull -> null
            is JsonObject -> toMap()
            is JsonArray -> toListValue()
            is JsonPrimitive -> toScalar()
            else -> this.toString()
        }
    }

    private fun JsonPrimitive.toScalar(): Any {
        if (isString) return content
        parseBooleanOrNull()?.let { return it }
        parseLongOrNull()?.let { return it }
        parseDoubleOrNull()?.let { return it }
        return content
    }

    private fun JsonPrimitive.parseLongOrNull(): Long? = content.toLongOrNull()

    private fun JsonPrimitive.parseDoubleOrNull(): Double? = content.toDoubleOrNull()

    private fun JsonPrimitive.parseBooleanOrNull(): Boolean? {
        return when (content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
