package com.proactiveai.extreme.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSyncHealthGatewayTest {
    @Test
    fun `health checks base url health endpoint and accepts ok true`() {
        val gateway = HttpMobileSyncHealthGateway(
            baseUrl = "https://example.ngrok-free.dev",
            transport = { method, url ->
                assertEquals("GET", method)
                assertEquals("https://example.ngrok-free.dev/health", url)
                200 to """{"ok":true}"""
            },
        )

        assertTrue(gateway.health().getOrThrow())
    }

    @Test
    fun `health returns false when ok flag is missing`() {
        val gateway = HttpMobileSyncHealthGateway(
            baseUrl = "https://example.ngrok-free.dev",
            transport = { _, _ ->
                200 to """{"status":"ok"}"""
            },
        )

        assertFalse(gateway.health().getOrThrow())
    }
}
