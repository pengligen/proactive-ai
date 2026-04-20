package com.proactiveai.extreme.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CanonicalPayloadHasherTest {
    @Test
    fun `canonical json sorts keys and strips nulls`() {
        val payload = linkedMapOf<String, Any?>(
            "b" to 2,
            "a" to 1,
            "nullable" to null,
            "nested" to mapOf(
                "z" to true,
                "skip" to null,
                "items" to listOf(3, null, "ok"),
            ),
        )

        val canonical = CanonicalPayloadHasher.canonicalJson(payload)

        assertEquals("""{"a":1,"b":2,"nested":{"items":[3,"ok"],"z":true}}""", canonical)
    }

    @Test
    fun `same content with different key order hashes identically`() {
        val left = mapOf(
            "summary" to "same",
            "payload" to mapOf(
                "b" to 2,
                "a" to 1,
            ),
        )
        val right = linkedMapOf<String, Any?>(
            "payload" to linkedMapOf<String, Any?>(
                "a" to 1,
                "b" to 2,
            ),
            "summary" to "same",
        )

        assertEquals(CanonicalPayloadHasher.sha256(left), CanonicalPayloadHasher.sha256(right))
        assertNotEquals(
            CanonicalPayloadHasher.sha256(left),
            CanonicalPayloadHasher.sha256(mapOf("summary" to "different")),
        )
    }
}
