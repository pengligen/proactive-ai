package com.proactiveai.extreme.core.edge

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadPlanTest {
    @Test
    fun `normalizeUrl returns default 2B url when blank`() {
        assertEquals(
            ModelDownloadPlan.defaultUrlFor(EdgeModelProfile.GEMMA_EFFECTIVE_2B),
            ModelDownloadPlan.normalizeUrl(EdgeModelProfile.GEMMA_EFFECTIVE_2B, "   "),
        )
    }

    @Test
    fun `normalizeUrl returns default 4B url when blank`() {
        assertEquals(
            ModelDownloadPlan.defaultUrlFor(EdgeModelProfile.GEMMA_EFFECTIVE_4B),
            ModelDownloadPlan.normalizeUrl(EdgeModelProfile.GEMMA_EFFECTIVE_4B, "   "),
        )
    }

    @Test
    fun `preferredFileName extracts direct 2B model file name`() {
        assertEquals(
            "gemma-4-E2B-it.litertlm",
            ModelDownloadPlan.preferredFileName(
                profile = EdgeModelProfile.GEMMA_EFFECTIVE_2B,
                url = "https://example.com/models/gemma-4-E2B-it.litertlm?download=1",
            ),
        )
    }

    @Test
    fun `preferredFileName extracts direct 4B model file name`() {
        assertEquals(
            "gemma-4-E4B-it.litertlm",
            ModelDownloadPlan.preferredFileName(
                profile = EdgeModelProfile.GEMMA_EFFECTIVE_4B,
                url = "https://example.com/models/gemma-4-E4B-it.litertlm?download=1",
            ),
        )
    }

    @Test
    fun `preferredFileName falls back to profile default when url is not a model file`() {
        assertEquals(
            ModelDownloadPlan.defaultFileNameFor(EdgeModelProfile.GEMMA_EFFECTIVE_2B),
            ModelDownloadPlan.preferredFileName(
                profile = EdgeModelProfile.GEMMA_EFFECTIVE_2B,
                url = "https://huggingface.co/some/model",
            ),
        )
    }
}
