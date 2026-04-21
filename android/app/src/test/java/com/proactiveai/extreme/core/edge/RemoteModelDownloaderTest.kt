package com.proactiveai.extreme.core.edge

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteModelDownloaderTest {
    @Test
    fun `describeFailure falls back to exception class when message is blank`() {
        val detail = RemoteModelDownloader.describeFailure(IllegalStateException())

        assertEquals("IllegalStateException", detail)
    }

    @Test
    fun `describeFailure prefers exception message when present`() {
        val detail = RemoteModelDownloader.describeFailure(IllegalStateException("timeout while downloading"))

        assertEquals("timeout while downloading", detail)
    }

    @Test
    fun `resolveFileName sanitizes preferred name`() {
        val fileName = RemoteModelDownloader.resolveFileName(
            url = "https://example.com/models/raw.bin",
            contentDisposition = null,
            preferred = "gemma 4/E2B?.litertlm",
        )

        assertEquals("gemma_4_E2B_.litertlm", fileName)
    }

    @Test
    fun `resolveFileName falls back to sanitized url segment`() {
        val fileName = RemoteModelDownloader.resolveFileName(
            url = "https://example.com/models/gemma-4-E4B-it.litertlm?download=1",
            contentDisposition = null,
            preferred = null,
        )

        assertEquals("gemma-4-E4B-it.litertlm", fileName)
    }
}
