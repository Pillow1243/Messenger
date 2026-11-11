package com.offlinemesh.messenger.domain

/**
 * Retry pacing for the online outbox. Kept pure and separate so the schedule can be unit-tested
 * without a network, a database or a device.
 */
object Backoff {

    const val BASE_MS = 5_000L
    const val MAX_MS = 10 * 60_000L
    const val MAX_ATTEMPTS = 8

    /** Exponential 5 s → 5 min, capped, and never negative for a corrupt attempt counter. */
    fun delayMillis(attempts: Int): Long {
        // 0..10 keeps the shift inside Long range while still reaching the cap.
        val safe = attempts.coerceIn(0, 10)
        return (BASE_MS shl safe).coerceAtMost(MAX_MS)
    }

    /** True once the row has been retried often enough that it should surface as failed. */
    fun exhausted(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
