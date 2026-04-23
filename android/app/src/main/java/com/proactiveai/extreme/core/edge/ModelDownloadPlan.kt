package com.proactiveai.extreme.core.edge

object ModelDownloadPlan {
    private data class ModelDownloadSpec(
        val defaultUrl: String,
        val defaultFileName: String,
        val shortLabel: String,
        val notes: String,
    )

    private val specs: Map<EdgeModelProfile, ModelDownloadSpec> = mapOf(
        EdgeModelProfile.GEMMA_EFFECTIVE_2B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            defaultFileName = "gemma-4-E2B-it.litertlm",
            shortLabel = "2B",
            notes = "Fast default model for Android phones.",
        ),
        EdgeModelProfile.GEMMA_EFFECTIVE_4B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            defaultFileName = "gemma-4-E4B-it.litertlm",
            shortLabel = "4B",
            notes = "Higher-quality Gemma reasoning at larger size.",
        ),
        EdgeModelProfile.QWEN3_0_6B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            defaultFileName = "Qwen3-0.6B.litertlm",
            shortLabel = "Qwen 0.6B",
            notes = "Small multilingual model with fast startup.",
        ),
        EdgeModelProfile.QWEN2_5_1_5B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            defaultFileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            shortLabel = "Qwen 1.5B",
            notes = "Balanced quality/speed; good daily assistant default.",
        ),
        EdgeModelProfile.QWEN3_4B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_channelwise_int8_float32kv.litertlm",
            defaultFileName = "qwen3_4b_channelwise_int8_float32kv.litertlm",
            shortLabel = "Qwen 4B",
            notes = "Best Qwen quality in this catalog, needs more RAM.",
        ),
    )

    fun defaultUrlFor(profile: EdgeModelProfile): String = specFor(profile).defaultUrl

    fun defaultFileNameFor(profile: EdgeModelProfile): String = specFor(profile).defaultFileName

    fun shortLabelFor(profile: EdgeModelProfile): String = specFor(profile).shortLabel

    fun notesFor(profile: EdgeModelProfile): String = specFor(profile).notes

    fun normalizeUrl(profile: EdgeModelProfile, raw: String): String {
        return raw.trim().ifBlank { defaultUrlFor(profile) }
    }

    fun preferredFileName(profile: EdgeModelProfile, url: String): String {
        val lastSegment = url
            .substringBefore("#")
            .substringBefore("?")
            .substringAfterLast("/")
            .trim()

        return if (lastSegment.endsWith(".litertlm", ignoreCase = true)) {
            lastSegment
        } else {
            defaultFileNameFor(profile)
        }
    }

    private fun specFor(profile: EdgeModelProfile): ModelDownloadSpec {
        return specs.getValue(profile)
    }
}
