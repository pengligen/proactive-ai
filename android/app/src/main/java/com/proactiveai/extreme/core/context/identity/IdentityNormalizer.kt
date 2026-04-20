package com.proactiveai.extreme.core.context.identity

import android.telephony.PhoneNumberUtils
import com.proactiveai.extreme.storage.CanonicalPayloadHasher
import java.util.Locale

class IdentityNormalizer(
    private val defaultCountryIsoProvider: () -> String? = {
        Locale.getDefault().country.takeIf { it.isNotBlank() }
    },
    private val phoneFormatter: (String, String?) -> String? = { raw, countryIso ->
        val normalizedCountry = countryIso
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
        if (normalizedCountry == null) {
            null
        } else {
            runCatching { PhoneNumberUtils.formatNumberToE164(raw, normalizedCountry) }.getOrNull()
        }
    },
) {
    fun normalizePhone(raw: String, countryIso: String? = defaultCountryIsoProvider()): NormalizedIdentity? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val formatted = phoneFormatter(trimmed, countryIso)?.trim().takeUnless { it.isNullOrBlank() }
        val fallback = digitsFallback(trimmed)
        val normalizedValue = formatted ?: fallback ?: return null

        return NormalizedIdentity(
            kind = "phone",
            normalizedValue = normalizedValue,
            normalizedHash = CanonicalPayloadHasher.sha256Hex("phone:$normalizedValue"),
            displayMasked = maskForDisplay(normalizedValue),
        )
    }

    fun normalizeEmail(raw: String): NormalizedIdentity? {
        val normalizedValue = raw.trim().lowercase(Locale.US)
        if (normalizedValue.isBlank()) return null

        return NormalizedIdentity(
            kind = "email",
            normalizedValue = normalizedValue,
            normalizedHash = CanonicalPayloadHasher.sha256Hex("email:$normalizedValue"),
            displayMasked = maskForDisplay(normalizedValue),
        )
    }

    private fun digitsFallback(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isBlank()) return null
        return if (raw.trim().startsWith("+")) "+$digits" else digits
    }

    private fun maskForDisplay(raw: String): String {
        if (raw.length <= 4) return raw
        return "${raw.take(2)}***${raw.takeLast(2)}"
    }
}
