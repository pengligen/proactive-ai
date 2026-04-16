package com.proactiveai.extreme.storage

import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EventFilterEngineTest {
    @Test
    fun `drops ongoing clash notification before local storage`() {
        val state = InMemoryCollectorStateStore()
        val event = ContextEvent(
            eventId = "notification-1",
            occurredAt = 1_000L,
            source = "notification_listener",
            category = "notification",
            summary = "Notification from com.github.kr328.clash: westdata",
            payload = mapOf(
                "packageName" to "com.github.kr328.clash",
                "key" to "0|com.github.kr328.clash|123",
                "title" to "westdata",
                "text" to "0 Bytes/s↑ 0 Bytes/s↓",
                "ongoing" to true,
                "clearable" to false,
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 60,
        )

        val filtered = EventFilterEngine.filter(event, state)
        assertNull(filtered)
    }

    @Test
    fun `dedupes repeated useful notifications within cooldown window`() {
        val state = InMemoryCollectorStateStore()
        val event = ContextEvent(
            eventId = "notification-2",
            occurredAt = 2_000L,
            source = "notification_listener",
            category = "notification",
            summary = "Notification from com.chat.app: Alice",
            payload = mapOf(
                "packageName" to "com.chat.app",
                "key" to "chat-1",
                "title" to "Alice",
                "text" to "Can you reply today?",
                "ongoing" to false,
                "clearable" to true,
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 60,
        )

        val first = EventFilterEngine.filter(event, state)
        val second = EventFilterEngine.filter(
            event.copy(eventId = "notification-3", occurredAt = 2_500L),
            state,
        )

        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun `drops app store update notifications before local storage`() {
        val state = InMemoryCollectorStateStore()
        val event = ContextEvent(
            eventId = "notification-market-1",
            occurredAt = 2_000L,
            source = "notification_listener",
            category = "notification",
            summary = "应用商店更新提醒",
            payload = mapOf(
                "packageName" to "com.xiaomi.market",
                "key" to "market-1",
                "title" to "78%的用户已更新这101款应用",
                "text" to "微信、多看、搜索...",
                "ongoing" to false,
                "clearable" to true,
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 60,
        )

        val filtered = EventFilterEngine.filter(event, state)

        assertNull(filtered)
    }

    @Test
    fun `drops non clearable running service notifications`() {
        val state = InMemoryCollectorStateStore()
        val event = ContextEvent(
            eventId = "notification-running-1",
            occurredAt = 2_100L,
            source = "notification_listener",
            category = "notification",
            summary = "短信正在运行",
            payload = mapOf(
                "packageName" to "com.android.mms",
                "key" to "mms-running-1",
                "title" to "“短信”正在运行",
                "text" to "点按即可了解详情或停止应用。",
                "ongoing" to false,
                "clearable" to false,
            ),
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 60,
        )

        val filtered = EventFilterEngine.filter(event, state)

        assertNull(filtered)
    }

    @Test
    fun `strips audio file paths but keeps transcript`() {
        val state = InMemoryCollectorStateStore()
        val event = ContextEvent(
            eventId = "audio-1",
            occurredAt = 3_000L,
            source = "audio_ambient",
            category = "audio",
            summary = "Audio captured",
            payload = mapOf(
                "transcript" to "Book hotel and finish packing tonight.",
                "wavPath" to "/tmp/test.wav",
                "metaPath" to "/tmp/test.json",
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 60,
        )

        val filtered = EventFilterEngine.filter(event, state)

        assertNotNull(filtered)
        assertEquals("Book hotel and finish packing tonight.", filtered!!.payload["transcript"])
        assertFalse(filtered.payload.containsKey("wavPath"))
        assertFalse(filtered.payload.containsKey("metaPath"))
    }
}
