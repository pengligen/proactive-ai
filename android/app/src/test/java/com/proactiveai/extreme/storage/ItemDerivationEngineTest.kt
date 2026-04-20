package com.proactiveai.extreme.storage

import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDerivationEngineTest {
    @Test
    fun `assistant session becomes one session summary and one compressed moment`() {
        val event = ContextEvent(
            eventId = "assistant-session-1",
            occurredAt = 10_000L,
            source = "assistant_engine",
            category = "assistant_session",
            summary = "Assistant session 08:00-08:15 | Travel planning",
            payload = mapOf(
                "sessionId" to "session-42",
                "sessionLabel" to "08:00-08:15",
                "guessedUserScenario" to "Planning a trip and packing list",
                "actionPlan" to "Book hotel, confirm train, finish packing",
                "locationLabel" to "Home",
                "calendarSummary" to "Train at 09:00",
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 3_600,
        )

        val items = ItemDerivationEngine.derive(event)

        assertEquals(2, items.size)
        assertEquals("session_summary", items[0].itemType)
        assertEquals("assistant_session:session-42", items[0].dedupeKey)
        assertTrue(items[0].summary.contains("Planning a trip"))
        assertEquals("moment", items[1].itemType)
        assertEquals("assistant_moment:session-42", items[1].dedupeKey)
        assertTrue(items[1].summary.contains("Home"))
    }

    @Test
    fun `daily focus expands into ranked attention items`() {
        val event = ContextEvent(
            eventId = "daily-focus-1",
            occurredAt = 20_000L,
            source = "assistant_engine",
            category = "daily_focus",
            summary = "Daily focus top3 generated",
            payload = mapOf(
                "dateToken" to "2026-04-16",
                "items" to listOf(
                    mapOf(
                        "rank" to 1,
                        "title" to "Book hotel",
                        "reason" to "The trip is imminent.",
                        "evidence" to "travel planning",
                        "importance" to 90,
                        "urgency" to 80,
                        "missRisk" to 70,
                        "action" to "Book the hotel before noon",
                    ),
                    mapOf(
                        "rank" to 2,
                        "title" to "Finish packing",
                        "reason" to "Need to leave early tomorrow.",
                        "evidence" to "packing list",
                        "importance" to 80,
                        "urgency" to 70,
                        "missRisk" to 60,
                        "action" to "Pack the essentials tonight",
                    ),
                ),
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 3_600,
        )

        val items = ItemDerivationEngine.derive(event)

        assertEquals(2, items.size)
        assertEquals("attention_item", items[0].itemType)
        assertEquals("daily_focus:2026-04-16:1", items[0].dedupeKey)
        assertEquals("daily_focus:2026-04-16:2", items[1].dedupeKey)
    }

    @Test
    fun `raw state snapshot no longer becomes standalone moment`() {
        val event = ContextEvent(
            eventId = "location-1",
            occurredAt = 30_000L,
            source = "location_motion",
            category = "location",
            summary = "Location 40.0278, 116.4348 | motion=unknown | city=北京市",
            payload = mapOf(
                "latitude" to 40.0278,
                "longitude" to 116.4348,
                "motionState" to "unknown",
                "city" to "北京市",
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 3_600,
        )

        val items = ItemDerivationEngine.derive(event)

        assertTrue(items.isEmpty())
    }

    @Test
    fun `calendar item uses stable payload dedupe key`() {
        val event = ContextEvent(
            eventId = "calendar-event-random",
            occurredAt = 40_000L,
            source = "calendar_provider",
            category = "calendar",
            summary = "Calendar ongoing: Home @ 08:00",
            payload = mapOf(
                "eventId" to 65,
                "title" to "Home",
                "state" to "ongoing",
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 3_600,
        )

        val items = ItemDerivationEngine.derive(event)

        assertEquals(1, items.size)
        assertEquals("calendar:65:ongoing", items.first().dedupeKey)
    }

    @Test
    fun `communication uses stable sms and call dedupe keys and skips unchanged contacts`() {
        val smsEvent = ContextEvent(
            eventId = "comm-random-1",
            occurredAt = 50_000L,
            source = "comm_plugin",
            category = "communication",
            summary = "Latest SMS from 10***91",
            payload = mapOf(
                "smsId" to 46,
                "address" to "10***91",
                "bodySnippet" to "验证码",
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 3_600,
        )
        val callEvent = ContextEvent(
            eventId = "comm-random-2",
            occurredAt = 51_000L,
            source = "comm_plugin",
            category = "communication",
            summary = "Latest call: missed 91***65 (59s)",
            payload = mapOf(
                "callId" to 14,
                "type" to "missed",
                "number" to "91***65",
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 3_600,
        )
        val contactsEvent = ContextEvent(
            eventId = "comm-random-3",
            occurredAt = 52_000L,
            source = "comm_plugin",
            category = "communication",
            summary = "Contacts accessible: total=404",
            payload = mapOf(
                "contactsCount" to 404,
                "deltaFromLast" to 0,
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 3_600,
        )

        val smsItems = ItemDerivationEngine.derive(smsEvent)
        val callItems = ItemDerivationEngine.derive(callEvent)
        val contactsItems = ItemDerivationEngine.derive(contactsEvent)

        assertEquals("communication:sms:46", smsItems.single().dedupeKey)
        assertEquals("communication:call:14:missed", callItems.single().dedupeKey)
        assertTrue(contactsItems.isEmpty())
    }

    @Test
    fun `bootstrap snapshots become syncable items and bootstrap calendar uses bootstrap state`() {
        val contactsEvent = ContextEvent(
            eventId = "bootstrap-contacts-1",
            occurredAt = 60_000L,
            source = "bootstrap_runner",
            category = "contact_bootstrap",
            summary = "Bootstrap contacts snapshot: 404 contacts, 350 identities",
            payload = mapOf(
                "contactsCount" to 404,
                "identityCount" to 350,
                "maxLastUpdatedTs" to 1_775_082_493_391L,
                "contactsIncluded" to 1,
                "contactsTruncated" to false,
                "contacts" to listOf(
                    mapOf(
                        "contactId" to "1",
                        "displayName" to "Alice",
                        "phonesMasked" to listOf("+8***78"),
                        "emailsMasked" to listOf("al***om"),
                        "identityHashes" to listOf("hash-1"),
                    ),
                ),
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 86_400,
        )
        val appsEvent = ContextEvent(
            eventId = "bootstrap-apps-1",
            occurredAt = 61_000L,
            source = "bootstrap_runner",
            category = "app_inventory_bootstrap",
            summary = "Bootstrap app inventory: 311 installed packages",
            payload = mapOf(
                "appCount" to 311,
                "lastUpdateTimeMax" to 1_776_484_683_674L,
                "appsIncluded" to 1,
                "appsTruncated" to false,
                "apps" to listOf(
                    mapOf(
                        "packageName" to "com.pin.app",
                        "label" to "Pin AI",
                    ),
                ),
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 86_400,
        )
        val smsEvent = ContextEvent(
            eventId = "bootstrap-sms-1",
            occurredAt = 62_000L,
            source = "bootstrap_runner",
            category = "sms_bootstrap",
            summary = "Bootstrap SMS from 10***91",
            payload = mapOf(
                "smsId" to 46,
                "address" to "10***91",
                "bodySnippet" to "验证码",
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 86_400,
        )
        val calendarEvent = ContextEvent(
            eventId = "bootstrap-calendar-1",
            occurredAt = 63_000L,
            source = "bootstrap_runner",
            category = "calendar_bootstrap",
            summary = "Bootstrap calendar: Pin AI Team Meeting",
            payload = mapOf(
                "eventId" to 88,
                "title" to "Pin AI Team Meeting",
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 86_400,
        )

        val contactItems = ItemDerivationEngine.derive(contactsEvent)
        val appItems = ItemDerivationEngine.derive(appsEvent)
        val smsItems = ItemDerivationEngine.derive(smsEvent)
        val calendarItems = ItemDerivationEngine.derive(calendarEvent)

        assertEquals("contact_directory_snapshot", contactItems.single().itemType)
        assertEquals(
            "contact_bootstrap:404:350:1775082493391",
            contactItems.single().dedupeKey,
        )
        @Suppress("UNCHECKED_CAST")
        val contactPayload = contactItems.single().payload["contacts"] as List<Map<String, Any>>
        assertEquals("Alice", contactPayload.single()["displayName"])
        assertEquals(listOf("+8***78"), contactPayload.single()["phonesMasked"])
        assertEquals("app_inventory_snapshot", appItems.single().itemType)
        assertEquals(
            "app_inventory_bootstrap:311:1776484683674",
            appItems.single().dedupeKey,
        )
        @Suppress("UNCHECKED_CAST")
        val appPayload = appItems.single().payload["apps"] as List<Map<String, Any>>
        assertEquals("Pin AI", appPayload.single()["label"])
        assertEquals("communication:sms:46", smsItems.single().dedupeKey)
        assertEquals("calendar:88:bootstrap", calendarItems.single().dedupeKey)
    }
}
