package com.proactiveai.extreme.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryActionLayoutTest {

    @Test
    fun `chunkActionLabelsForCompactGrid splits actions into two-column rows`() {
        val labels = listOf(
            "Ping Backend",
            "Sync Now",
            "Sync Config",
            "Refresh Plan",
        )

        val rows = chunkActionLabelsForCompactGrid(labels)

        assertEquals(
            listOf(
                listOf("Ping Backend", "Sync Now"),
                listOf("Sync Config", "Refresh Plan"),
            ),
            rows,
        )
    }

    @Test
    fun `chunkForCompactActionGrid keeps final row when action count is odd`() {
        val labels = listOf(
            "Refresh",
            "Re-run STT",
            "Clear",
        )

        val rows = chunkForCompactActionGrid(labels, columns = 2)

        assertEquals(
            listOf(
                listOf("Refresh", "Re-run STT"),
                listOf("Clear"),
            ),
            rows,
        )
    }
}
