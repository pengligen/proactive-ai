package com.proactiveai.extreme.core.context.identity

data class NormalizedIdentity(
    val kind: String,
    val normalizedValue: String,
    val normalizedHash: String,
    val displayMasked: String,
)
