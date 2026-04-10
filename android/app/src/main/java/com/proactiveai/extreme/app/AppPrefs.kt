package com.proactiveai.extreme.app

import android.content.Context
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.model.EvaluationMetrics
import com.proactiveai.extreme.data.ExtremeDefaults

object AppPrefs {
    private const val PREFS_NAME = "proactive_extreme_prefs"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_ENABLED_PLUGINS = "enabled_plugins"
    private const val KEY_COLLECTION_ENABLED = "collection_enabled"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EDGE_MODEL = "edge_model"
    private const val KEY_AUTO_EXECUTE_LOW_RISK = "auto_execute_low_risk"
    private const val KEY_LOCAL_MODEL_ENABLED = "local_model_enabled"
    private const val KEY_LOCAL_MODEL_BACKEND = "local_model_backend"
    private const val KEY_LOCAL_MODEL_PATH_2B = "local_model_path_2b"
    private const val KEY_LOCAL_MODEL_PATH_4B = "local_model_path_4b"
    private const val KEY_LOCAL_GGUF_PATH_2B = "local_gguf_path_2b"
    private const val KEY_LOCAL_GGUF_PATH_4B = "local_gguf_path_4b"
    private const val KEY_LOCAL_LLAMA_CONTEXT_SIZE = "local_llama_context_size"
    private const val KEY_LOCAL_LLAMA_THREADS = "local_llama_threads"
    private const val KEY_HF_TOKEN = "huggingface_token"
    private const val KEY_ASSISTANT_LAST_SESSION_BUCKET = "assistant_last_session_bucket"

    private const val KEY_METRIC_SUGGESTIONS_TOTAL = "metric_suggestions_total"
    private const val KEY_METRIC_SUGGESTIONS_ACCEPTED = "metric_suggestions_accepted"
    private const val KEY_METRIC_EXECUTIONS_TOTAL = "metric_executions_total"
    private const val KEY_METRIC_EXECUTIONS_SUCCESS = "metric_executions_success"
    private const val KEY_METRIC_INTERRUPTION_TOTAL = "metric_interruption_total"
    private const val KEY_METRIC_INTERRUPTION_POSITIVE = "metric_interruption_positive"

    fun isMasterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getEnabledPlugins(context: Context): Set<String> {
        val defaults = ExtremeDefaults.plugins().map { it.id }.toSet()
        val stored = prefs(context).getStringSet(KEY_ENABLED_PLUGINS, null)?.toSet()
        return if (stored == null) {
            defaults
        } else {
            // Auto-adopt newly introduced plugins into Extreme mode while preserving existing enabled set.
            stored + defaults
        }
    }

    fun setEnabledPlugins(context: Context, pluginIds: Set<String>) {
        prefs(context).edit().putStringSet(KEY_ENABLED_PLUGINS, pluginIds).apply()
    }

    fun isCollectionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_COLLECTION_ENABLED, false)
    }

    fun setCollectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COLLECTION_ENABLED, enabled).apply()
    }

    fun getUserId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val generated = "local-user-" + System.currentTimeMillis().toString(16)
        prefs.edit().putString(KEY_USER_ID, generated).apply()
        return generated
    }

    fun getEdgeModel(context: Context): String {
        return prefs(context).getString(KEY_EDGE_MODEL, EdgeModelProfile.GEMMA_EFFECTIVE_2B.id)
            ?: EdgeModelProfile.GEMMA_EFFECTIVE_2B.id
    }

    fun setEdgeModel(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_EDGE_MODEL, modelId).apply()
    }

    fun isAutoExecuteLowRisk(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_EXECUTE_LOW_RISK, true)
    }

    fun setAutoExecuteLowRisk(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_EXECUTE_LOW_RISK, enabled).apply()
    }

    fun isLocalModelEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LOCAL_MODEL_ENABLED, true)
    }

    fun setLocalModelEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_MODEL_ENABLED, enabled).apply()
    }

    fun getLocalModelBackend(context: Context): String {
        return prefs(context).getString(KEY_LOCAL_MODEL_BACKEND, LocalModelBackend.LITERT_LM.id)
            ?: LocalModelBackend.LITERT_LM.id
    }

    fun setLocalModelBackend(context: Context, backendId: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_BACKEND, backendId).apply()
    }

    fun getLocalModelPath2B(context: Context): String {
        return prefs(context).getString(
            KEY_LOCAL_MODEL_PATH_2B,
            "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E2B-it.litertlm",
        ) ?: "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E2B-it.litertlm"
    }

    fun setLocalModelPath2B(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH_2B, path).apply()
    }

    fun getLocalModelPath4B(context: Context): String {
        return prefs(context).getString(
            KEY_LOCAL_MODEL_PATH_4B,
            "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E4B-it.litertlm",
        ) ?: "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E4B-it.litertlm"
    }

    fun setLocalModelPath4B(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH_4B, path).apply()
    }

    fun getLocalGgufPath2B(context: Context): String {
        return prefs(context).getString(
            KEY_LOCAL_GGUF_PATH_2B,
            "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E2B-it-Q4_K_M.gguf",
        ) ?: "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E2B-it-Q4_K_M.gguf"
    }

    fun setLocalGgufPath2B(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_GGUF_PATH_2B, path).apply()
    }

    fun getLocalGgufPath4B(context: Context): String {
        return prefs(context).getString(
            KEY_LOCAL_GGUF_PATH_4B,
            "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E4B-it-Q4_K_M.gguf",
        ) ?: "/data/user/0/com.proactiveai.extreme/files/models/gemma-4-E4B-it-Q4_K_M.gguf"
    }

    fun setLocalGgufPath4B(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_GGUF_PATH_4B, path).apply()
    }

    fun getLocalLlamaContextSize(context: Context): Int {
        return prefs(context).getInt(KEY_LOCAL_LLAMA_CONTEXT_SIZE, 4096)
    }

    fun setLocalLlamaContextSize(context: Context, contextSize: Int) {
        prefs(context).edit().putInt(KEY_LOCAL_LLAMA_CONTEXT_SIZE, contextSize).apply()
    }

    fun getLocalLlamaThreads(context: Context): Int {
        return prefs(context).getInt(KEY_LOCAL_LLAMA_THREADS, 6)
    }

    fun setLocalLlamaThreads(context: Context, threads: Int) {
        prefs(context).edit().putInt(KEY_LOCAL_LLAMA_THREADS, threads).apply()
    }

    fun getHuggingFaceToken(context: Context): String {
        return prefs(context).getString(KEY_HF_TOKEN, "").orEmpty()
    }

    fun setHuggingFaceToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getAssistantLastSessionBucket(context: Context): Long {
        return prefs(context).getLong(KEY_ASSISTANT_LAST_SESSION_BUCKET, -1L)
    }

    fun setAssistantLastSessionBucket(context: Context, bucket: Long) {
        prefs(context).edit().putLong(KEY_ASSISTANT_LAST_SESSION_BUCKET, bucket).apply()
    }

    fun getEvaluationMetrics(context: Context): EvaluationMetrics {
        val p = prefs(context)
        return EvaluationMetrics(
            suggestionsTotal = p.getInt(KEY_METRIC_SUGGESTIONS_TOTAL, 0),
            suggestionsAccepted = p.getInt(KEY_METRIC_SUGGESTIONS_ACCEPTED, 0),
            executionsTotal = p.getInt(KEY_METRIC_EXECUTIONS_TOTAL, 0),
            executionsSuccessful = p.getInt(KEY_METRIC_EXECUTIONS_SUCCESS, 0),
            interruptionsTotal = p.getInt(KEY_METRIC_INTERRUPTION_TOTAL, 0),
            interruptionsPositive = p.getInt(KEY_METRIC_INTERRUPTION_POSITIVE, 0),
        )
    }

    fun recordSuggestionGenerated(context: Context) {
        bumpCounter(context, KEY_METRIC_SUGGESTIONS_TOTAL)
    }

    fun recordSuggestionAccepted(context: Context) {
        bumpCounter(context, KEY_METRIC_SUGGESTIONS_ACCEPTED)
    }

    fun recordExecution(context: Context, successful: Boolean) {
        bumpCounter(context, KEY_METRIC_EXECUTIONS_TOTAL)
        if (successful) {
            bumpCounter(context, KEY_METRIC_EXECUTIONS_SUCCESS)
        }
    }

    fun recordInterruptionDecision(context: Context, positive: Boolean) {
        bumpCounter(context, KEY_METRIC_INTERRUPTION_TOTAL)
        if (positive) {
            bumpCounter(context, KEY_METRIC_INTERRUPTION_POSITIVE)
        }
    }

    fun resetEvaluationMetrics(context: Context) {
        prefs(context).edit()
            .remove(KEY_METRIC_SUGGESTIONS_TOTAL)
            .remove(KEY_METRIC_SUGGESTIONS_ACCEPTED)
            .remove(KEY_METRIC_EXECUTIONS_TOTAL)
            .remove(KEY_METRIC_EXECUTIONS_SUCCESS)
            .remove(KEY_METRIC_INTERRUPTION_TOTAL)
            .remove(KEY_METRIC_INTERRUPTION_POSITIVE)
            .apply()
    }

    private fun bumpCounter(context: Context, key: String) {
        val p = prefs(context)
        val current = p.getInt(key, 0)
        p.edit().putInt(key, current + 1).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
