package com.proactiveai.extreme.orchestrator

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        val value = this.opt(key)
        map[key] = when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}

fun JSONArray.toList(): List<Any?> {
    return (0 until length()).map { idx ->
        when (val value = opt(idx)) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            JSONObject.NULL -> null
            else -> value
        }
    }
}

fun Map<String, Any>.toJsonObject(): JSONObject {
    val output = JSONObject()
    forEach { (key, value) ->
        output.put(key, value.toJsonCompatible())
    }
    return output
}

private fun Any?.toJsonCompatible(): Any? {
    return when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val mapped = JSONObject()
            this.forEach { (k, v) ->
                if (k != null) {
                    mapped.put(k.toString(), v.toJsonCompatible())
                }
            }
            mapped
        }
        is List<*> -> {
            val array = JSONArray()
            this.forEach { item ->
                array.put(item.toJsonCompatible())
            }
            array
        }
        else -> this
    }
}
