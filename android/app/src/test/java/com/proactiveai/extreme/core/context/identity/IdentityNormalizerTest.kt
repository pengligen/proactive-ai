package com.proactiveai.extreme.core.context.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IdentityNormalizerTest {
    private val formatter: (String, String?) -> String? = { raw, countryIso ->
        val digits = raw.filter { it.isDigit() }
        when {
            raw.trim().startsWith("+") && digits.isNotBlank() -> "+$digits"
            countryIso == "CN" && digits.length == 11 -> "+86$digits"
            else -> null
        }
    }

    @Test
    fun `same phone in different formats normalizes to same hash`() {
        val normalizer = IdentityNormalizer(
            defaultCountryIsoProvider = { "CN" },
            phoneFormatter = formatter,
        )

        val first = normalizer.normalizePhone("138 0013 8000")
        val second = normalizer.normalizePhone("+86 138-0013-8000")

        assertNotNull(first)
        assertNotNull(second)
        assertEquals("+8613800138000", first!!.normalizedValue)
        assertEquals(first.normalizedHash, second!!.normalizedHash)
    }

    @Test
    fun `phone fallback keeps digits when formatter cannot parse`() {
        val normalizer = IdentityNormalizer(
            defaultCountryIsoProvider = { null },
            phoneFormatter = { _, _ -> null },
        )

        val normalized = normalizer.normalizePhone("010-8888-9999")

        assertNotNull(normalized)
        assertEquals("01088889999", normalized!!.normalizedValue)
        assertEquals("01***99", normalized.displayMasked)
    }

    @Test
    fun `email normalization lowercases before hashing`() {
        val normalizer = IdentityNormalizer()

        val normalized = normalizer.normalizeEmail("Alice.Example@Example.COM ")

        assertNotNull(normalized)
        assertEquals("alice.example@example.com", normalized!!.normalizedValue)
        assertEquals(normalized.normalizedHash, normalizer.normalizeEmail("alice.example@example.com")!!.normalizedHash)
    }
}
