package com.proactiveai.extreme.ui.permission

import com.proactiveai.extreme.core.edge.EdgeModelProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadPresentationTest {
    @Test
    fun `download button label reflects selected 2B target`() {
        assertEquals(
            "Download 2B",
            modelDownloadActionLabel(
                selectedProfile = EdgeModelProfile.GEMMA_EFFECTIVE_2B,
                downloadInFlight = false,
            ),
        )
    }

    @Test
    fun `download button label reflects selected 4B target`() {
        assertEquals(
            "Download 4B",
            modelDownloadActionLabel(
                selectedProfile = EdgeModelProfile.GEMMA_EFFECTIVE_4B,
                downloadInFlight = false,
            ),
        )
    }

    @Test
    fun `download button label shows downloading when request is active`() {
        assertEquals(
            "Downloading...",
            modelDownloadActionLabel(
                selectedProfile = EdgeModelProfile.GEMMA_EFFECTIVE_4B,
                downloadInFlight = true,
            ),
        )
    }

    @Test
    fun `download draft switches by selected profile`() {
        assertEquals(
            "https://example.com/e2b",
            modelDownloadDraftForProfile(
                selectedProfile = EdgeModelProfile.GEMMA_EFFECTIVE_2B,
                url2B = "https://example.com/e2b",
                url4B = "https://example.com/e4b",
            ),
        )
        assertEquals(
            "https://example.com/e4b",
            modelDownloadDraftForProfile(
                selectedProfile = EdgeModelProfile.GEMMA_EFFECTIVE_4B,
                url2B = "https://example.com/e2b",
                url4B = "https://example.com/e4b",
            ),
        )
    }
}
