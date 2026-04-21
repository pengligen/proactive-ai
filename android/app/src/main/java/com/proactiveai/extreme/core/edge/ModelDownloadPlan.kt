package com.proactiveai.extreme.core.edge

object ModelDownloadPlan {
    private data class ModelDownloadSpec(
        val defaultUrl: String,
        val defaultFileName: String,
        val shortLabel: String,
    )

    private val specs: Map<EdgeModelProfile, ModelDownloadSpec> = mapOf(
        EdgeModelProfile.GEMMA_EFFECTIVE_2B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            defaultFileName = "gemma-4-E2B-it.litertlm",
            shortLabel = "2B",
        ),
        EdgeModelProfile.GEMMA_EFFECTIVE_4B to ModelDownloadSpec(
            defaultUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            defaultFileName = "gemma-4-E4B-it.litertlm",
            shortLabel = "4B",
        ),
    )

    fun defaultUrlFor(profile: EdgeModelProfile): String = specFor(profile).defaultUrl

    fun defaultFileNameFor(profile: EdgeModelProfile): String = specFor(profile).defaultFileName

    fun shortLabelFor(profile: EdgeModelProfile): String = specFor(profile).shortLabel

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
