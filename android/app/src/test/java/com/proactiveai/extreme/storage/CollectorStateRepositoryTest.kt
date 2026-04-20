package com.proactiveai.extreme.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectorStateRepositoryTest {
    private class MutableInMemoryCollectorStateStore : MutableCollectorStateStore {
        private val data = linkedMapOf<String, String>()

        override fun get(key: String): String? = data[key]

        override fun put(key: String, value: String) {
            data[key] = value
        }

        override fun remove(key: String) {
            data.remove(key)
        }
    }

    @Test
    fun `bootstrap cursor round trips through typed helpers`() {
        val repository = CollectorStateRepository(MutableInMemoryCollectorStateStore())

        repository.putBootstrapCursor(
            source = "contacts",
            cursor = mapOf(
                "lastUpdatedTs" to 1234L,
                "scanId" to "contacts-1",
            ),
        )

        val cursor = repository.getBootstrapCursor("contacts")

        assertEquals(1234L, (cursor["lastUpdatedTs"] as Number).toLong())
        assertEquals("contacts-1", cursor["scanId"])
    }

    @Test
    fun `summary refresh flag is consumed once`() {
        val repository = CollectorStateRepository(MutableInMemoryCollectorStateStore())

        repository.requestSummaryRefresh("contacts", atMs = 4567L)

        assertEquals(4567L, repository.consumeSummaryRefresh("contacts"))
        assertNull(repository.consumeSummaryRefresh("contacts"))
    }
}
