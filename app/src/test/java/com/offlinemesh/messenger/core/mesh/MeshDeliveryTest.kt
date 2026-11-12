package com.offlinemesh.messenger.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenPacketCacheTest {

    @Test
    fun `first sighting is new, second is a duplicate`() {
        val cache = SeenPacketCache()
        assertTrue(cache.markSeen("p1", 1_000))
        assertFalse(cache.markSeen("p1", 1_001))
        assertTrue(cache.contains("p1", 1_002))
    }

    @Test
    fun `entries are forgotten after the ttl so the cache cannot grow forever`() {
        val cache = SeenPacketCache(ttlMs = 1_000)
        cache.markSeen("p1", 0)
        assertFalse(cache.contains("p1", 5_000))
        // A packet id seen again after the window is treated as new: the ratchet, not this
        // cache, is what rejects replays at the protocol level.
        assertTrue(cache.markSeen("p1", 5_000))
    }

    @Test
    fun `cache evicts the oldest entries`() {
        val cache = SeenPacketCache(maxEntries = 3)
        for (index in 0 until 10) {
            cache.markSeen("p$index", index.toLong())
        }
        assertEquals(3, cache.size())
        assertFalse(cache.contains("p0", 10))
        assertTrue(cache.contains("p9", 10))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = SeenPacketCache()
        cache.markSeen("p1", 1)
        cache.clear()
        assertEquals(0, cache.size())
    }
}

class RetryPolicyTest {

    @Test
    fun `first attempt has no delay`() {
        assertEquals(0L, RetryPolicy.delayFor(0))
    }

    @Test
    fun `delays grow exponentially`() {
        assertEquals(RetryPolicy.BASE_DELAY_MS, RetryPolicy.delayFor(1))
        assertEquals(RetryPolicy.BASE_DELAY_MS * 2, RetryPolicy.delayFor(2))
        assertEquals(RetryPolicy.BASE_DELAY_MS * 4, RetryPolicy.delayFor(3))
    }

    @Test
    fun `delay is capped`() {
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayFor(30))
    }

    @Test
    fun `retries stop after the attempt budget`() {
        assertTrue(RetryPolicy.shouldRetry(0))
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.MAX_ATTEMPTS))
    }
}

class TransmitLogTest {

    @Test
    fun `a packet that was never sent is due`() {
        assertTrue(TransmitLog().due("p1", now = 1_000, intervalMs = 10_000))
    }

    @Test
    fun `a packet is not repeated inside the interval and is repeated after it`() {
        val log = TransmitLog()
        log.mark("p1", 1_000)
        assertFalse(log.due("p1", now = 5_000, intervalMs = 10_000))
        assertTrue(log.due("p1", now = 11_000, intervalMs = 10_000))
        assertTrue(log.due("p2", now = 5_000, intervalMs = 10_000))
    }

    @Test
    fun `a clock that jumps backwards does not silence a packet`() {
        val log = TransmitLog()
        log.mark("p1", 1_000_000)
        assertTrue(log.due("p1", now = 5, intervalMs = 10_000))
    }

    @Test
    fun `the log is bounded and forgets the least recently used packets first`() {
        val memo = BoundedMemo<Long>(maxEntries = 3)
        for (index in 0 until 10) memo.put("p$index", index.toLong())
        assertEquals(3, memo.size())
        assertEquals(null, memo.get("p0"))
        assertEquals(9L, memo.get("p9"))
        // Reading p7 makes it the most recently used, so p8 goes first.
        memo.get("p7")
        memo.put("p10", 10)
        memo.put("p11", 11)
        assertEquals(7L, memo.get("p7"))
        assertEquals(null, memo.get("p8"))
    }
}
