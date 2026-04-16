package com.proactiveai.extreme.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCardPresentationTest {

    @Test
    fun `collection service ui exposes active state feedback`() {
        val state = collectionServiceStatusUi(collectionEnabled = true)

        assertEquals("Running", state.statusLabel)
        assertEquals(SignalTone.Success, state.statusTone)
        assertEquals("Service Running", state.startActionLabel)
        assertFalse(state.startActionEnabled)
        assertEquals("Stop Service", state.stopActionLabel)
        assertTrue(state.stopActionEnabled)
    }

    @Test
    fun `collection service ui exposes idle state feedback`() {
        val state = collectionServiceStatusUi(collectionEnabled = false)

        assertEquals("Idle", state.statusLabel)
        assertEquals(SignalTone.Warning, state.statusTone)
        assertEquals("Start Service", state.startActionLabel)
        assertTrue(state.startActionEnabled)
        assertEquals("Service Idle", state.stopActionLabel)
        assertFalse(state.stopActionEnabled)
    }

    @Test
    fun `manual sync ui disables button while sync is running`() {
        val state = manualSyncStatusUi(syncInFlight = true)

        assertEquals("Syncing...", state.actionLabel)
        assertFalse(state.actionEnabled)
    }

    @Test
    fun `manual sync result message reports drained queue`() {
        val message = manualSyncResultMessage(queuedBefore = 44, queuedAfter = 0)

        assertEquals("Synced 44 uploads. Queue is now empty.", message)
    }

    @Test
    fun `manual sync result message reports unchanged queue`() {
        val message = manualSyncResultMessage(queuedBefore = 44, queuedAfter = 44)

        assertEquals("Sync request sent, but queue is still 44. Backend may still be processing.", message)
    }
}
