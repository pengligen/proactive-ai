package com.proactiveai.extreme.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModelForegroundGateTest {
    @Test
    fun `foreground entry extends suppression window`() {
        val nowMs = 10_000L

        val suppressUntilMs = AutoModelForegroundGate.extendSuppressionWindow(
            nowMs = nowMs,
            cooldownMs = 120_000L,
        )

        assertTrue(
            AutoModelForegroundGate.shouldSuppressAutoModelWork(
                nowMs = nowMs + 30_000L,
                suppressUntilMs = suppressUntilMs,
                isUiForegroundVisible = false,
            )
        )
        assertFalse(
            AutoModelForegroundGate.shouldSuppressAutoModelWork(
                nowMs = suppressUntilMs,
                suppressUntilMs = suppressUntilMs,
                isUiForegroundVisible = false,
            )
        )
    }

    @Test
    fun `expired suppression does not block model work`() {
        assertFalse(
            AutoModelForegroundGate.shouldSuppressAutoModelWork(
                nowMs = 300_000L,
                suppressUntilMs = 299_999L,
                isUiForegroundVisible = false,
            )
        )
    }

    @Test
    fun `foreground visibility always suppresses model work`() {
        assertTrue(
            AutoModelForegroundGate.shouldSuppressAutoModelWork(
                nowMs = 300_000L,
                suppressUntilMs = 0L,
                isUiForegroundVisible = true,
            )
        )
    }
}
