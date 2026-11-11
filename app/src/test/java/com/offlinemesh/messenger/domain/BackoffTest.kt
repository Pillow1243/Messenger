package com.offlinemesh.messenger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun `grows exponentially from five seconds`() {
        assertEquals(5_000L, Backoff.delayMillis(0))
        assertEquals(10_000L, Backoff.delayMillis(1))
        assertEquals(40_000L, Backoff.delayMillis(3))
    }

    @Test
    fun `is capped at ten minutes`() {
        assertEquals(Backoff.MAX_MS, Backoff.delayMillis(20))
        assertEquals(Backoff.MAX_MS, Backoff.delayMillis(Int.MAX_VALUE))
    }

    @Test
    fun `negative counters are treated as a first attempt`() {
        assertEquals(5_000L, Backoff.delayMillis(-3))
    }

    @Test
    fun `exhaustion happens after the eighth attempt`() {
        assertFalse(Backoff.exhausted(7))
        assertTrue(Backoff.exhausted(8))
        assertTrue(Backoff.exhausted(64))
    }
}
