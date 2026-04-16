package com.proactiveai.extreme.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileSyncConfigTest {
    @Test
    fun `builds ingest path under api mobile prefix`() {
        val url = MobileSyncConfig.buildIngestItemsUrl("https://example.ngrok-free.app/")
        assertEquals("https://example.ngrok-free.app/api/mobile/items/ingest", url)
    }
}
