package com.proactiveai.extreme.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapCursorCodecTest {
    @Test
    fun `encode produces stable sorted json`() {
        val encoded = BootstrapCursorCodec.encode(
            linkedMapOf(
                "scanId" to "contacts-1",
                "lastUpdatedTs" to 1234L,
            ),
        )

        assertEquals("""{"lastUpdatedTs":1234,"scanId":"contacts-1"}""", encoded)
    }

    @Test
    fun `decode round trips nested cursor payload`() {
        val decoded = BootstrapCursorCodec.decode(
            """{"windowEndMs":200,"windowStartMs":100,"signature":"abc","items":[1,true,"x"]}""",
        )

        assertEquals(200L, (decoded["windowEndMs"] as Number).toLong())
        assertEquals(100L, (decoded["windowStartMs"] as Number).toLong())
        assertEquals("abc", decoded["signature"])
        assertTrue(decoded["items"] is List<*>)
    }
}
