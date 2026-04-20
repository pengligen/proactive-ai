package com.proactiveai.extreme.app

object AutoModelForegroundGate {
    const val DEFAULT_COOLDOWN_MS = 3 * 60_000L

    fun extendSuppressionWindow(
        nowMs: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Long {
        return nowMs + cooldownMs.coerceAtLeast(0L)
    }

    fun shouldSuppressAutoModelWork(
        nowMs: Long,
        suppressUntilMs: Long,
        isUiForegroundVisible: Boolean,
    ): Boolean {
        return isUiForegroundVisible || suppressUntilMs > nowMs
    }
}
