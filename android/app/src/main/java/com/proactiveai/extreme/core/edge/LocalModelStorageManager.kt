package com.proactiveai.extreme.core.edge

import android.content.Context
import org.json.JSONObject
import java.io.File

data class InstalledLocalModelRecord(
    val modelId: String,
    val modelLabel: String,
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val downloadedAtMs: Long,
    val sourceUrl: String,
)

data class ModelInferenceLogEntry(
    val occurredAtMs: Long,
    val trigger: String,
    val mode: String,
    val modelId: String,
    val modelLabel: String,
    val prompt: String,
    val response: String,
    val status: String,
    val nativeUsed: Boolean,
)

object LocalModelStorageManager {
    private const val REGISTRY_FILE_NAME = "model_registry.json"
    private const val INFERENCE_LOG_FILE_NAME = "inference_log.jsonl"

    fun modelsDir(context: Context): File {
        return File(context.filesDir, "models").apply { mkdirs() }
    }

    fun registryFile(context: Context): File {
        return File(modelsDir(context), REGISTRY_FILE_NAME)
    }

    fun modelLogsDir(context: Context): File {
        return File(context.filesDir, "model_logs").apply { mkdirs() }
    }

    fun inferenceLogFile(context: Context): File {
        return File(modelLogsDir(context), INFERENCE_LOG_FILE_NAME)
    }

    @Synchronized
    fun upsertDownloadedModel(
        context: Context,
        profile: EdgeModelProfile,
        path: String,
        sourceUrl: String,
    ) {
        val registry = loadRegistry(context)
        val file = File(path)
        val entry = JSONObject().apply {
            put("id", profile.id)
            put("label", profile.label)
            put("path", path)
            put("sourceUrl", sourceUrl)
            put("downloadedAtMs", System.currentTimeMillis())
            put("fileSizeBytes", file.length())
            put("exists", file.exists())
        }
        registry.put(profile.id, entry)
        persistRegistry(context, registry)
    }

    @Synchronized
    fun listInstalledModels(context: Context): List<InstalledLocalModelRecord> {
        val registry = loadRegistry(context)
        return EdgeModelProfile.entries.map { profile ->
            val item = registry.optJSONObject(profile.id)
            val path = item?.optString("path")
                ?.trim()
                .orEmpty()
            val file = File(path)
            InstalledLocalModelRecord(
                modelId = profile.id,
                modelLabel = profile.label,
                path = path,
                exists = path.isNotBlank() && file.exists(),
                sizeBytes = if (file.exists()) file.length() else 0L,
                downloadedAtMs = item?.optLong("downloadedAtMs", 0L) ?: 0L,
                sourceUrl = item?.optString("sourceUrl", "").orEmpty(),
            )
        }
    }

    @Synchronized
    fun appendInferenceLog(
        context: Context,
        entry: ModelInferenceLogEntry,
    ) {
        val file = inferenceLogFile(context)
        val line = JSONObject().apply {
            put("occurredAtMs", entry.occurredAtMs)
            put("trigger", entry.trigger)
            put("mode", entry.mode)
            put("modelId", entry.modelId)
            put("modelLabel", entry.modelLabel)
            put("prompt", entry.prompt.take(8_000))
            put("response", entry.response.take(8_000))
            put("status", entry.status.take(1_000))
            put("nativeUsed", entry.nativeUsed)
        }.toString()
        file.appendText(line + "\n")
    }

    private fun loadRegistry(context: Context): JSONObject {
        val file = registryFile(context)
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
    }

    private fun persistRegistry(context: Context, json: JSONObject) {
        val file = registryFile(context)
        file.writeText(json.toString())
    }
}
