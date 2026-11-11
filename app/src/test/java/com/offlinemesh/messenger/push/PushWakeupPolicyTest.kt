package com.offlinemesh.messenger.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PushWakeupPolicyTest {

    @Test
    fun `accepts valid zero-plaintext message and call wakeups`() {
        val policy = PushWakeupPolicy()
        val msg = policy.acceptOrNull(
            mapOf(
                "type" to "mm_wakeup",
                "event_kind" to "message",
                "conversation_id" to "conv-123",
                "target_id" to "msg-456",
                "expires_at_ms" to "200000"
            ),
            nowMs = 100_000L
        )
        assertNotNull(msg)
        assertEquals("message", msg?.eventKind)
        assertEquals("conv-123", msg?.conversationId)
        assertEquals("msg-456", msg?.targetId)

        val call = policy.acceptOrNull(
            mapOf(
                "type" to "mm_wakeup",
                "event_kind" to "call",
                "conversation_id" to "conv-123",
                "target_id" to "call-789"
            ),
            nowMs = 100_000L
        )
        assertNotNull(call)
        assertEquals("call", call?.eventKind)
    }

    @Test
    fun `rejects any wakeup carrying forbidden plaintext or key fields`() {
        val policy = PushWakeupPolicy()
        val forbiddenKeys = listOf(
            "body", "plaintext", "text", "message_body", "sender_name", "file_name", "media_key", "sdp"
        )
        for (forbidden in forbiddenKeys) {
            val result = policy.acceptOrNull(
                mapOf(
                    "type" to "mm_wakeup",
                    "event_kind" to "message",
                    "conversation_id" to "conv-1",
                    "target_id" to "msg-$forbidden",
                    forbidden to "leaked"
                ),
                nowMs = 1_000L
            )
            assertNull("Wakeup with forbidden field '$forbidden' must be rejected", result)
        }
    }

    @Test
    fun `drops expired wakeups and suppresses duplicates within window`() {
        val policy = PushWakeupPolicy(dedupWindowMs = 60_000L)
        val expired = policy.acceptOrNull(
            mapOf(
                "type" to "mm_wakeup",
                "event_kind" to "call",
                "conversation_id" to "conv-1",
                "target_id" to "call-expired",
                "expires_at_ms" to "5000"
            ),
            nowMs = 10_000L
        )
        assertNull(expired)

        val payload = mapOf(
            "type" to "mm_wakeup",
            "event_kind" to "message",
            "conversation_id" to "conv-1",
            "target_id" to "msg-1"
        )
        assertNotNull(policy.acceptOrNull(payload, nowMs = 10_000L))
        assertNull("Duplicate wakeup inside window must be suppressed", policy.acceptOrNull(payload, nowMs = 30_000L))
        assertNotNull("Wakeup after dedup window should be accepted", policy.acceptOrNull(payload, nowMs = 80_000L))
    }

    @Test
    fun `evicts eldest dedup keys when maxEntries is exceeded`() {
        val policy = PushWakeupPolicy(maxEntries = 3, dedupWindowMs = 600_000L)
        for (i in 1..4) {
            assertNotNull(
                policy.acceptOrNull(
                    mapOf(
                        "type" to "mm_wakeup",
                        "event_kind" to "message",
                        "conversation_id" to "c",
                        "target_id" to "m-$i"
                    ),
                    nowMs = i * 100L
                )
            )
        }
        // m-1 was evicted when m-4 arrived, while m-4 is still cached
        assertNull(
            policy.acceptOrNull(
                mapOf("type" to "mm_wakeup", "event_kind" to "message", "conversation_id" to "c", "target_id" to "m-4"),
                nowMs = 500L
            )
        )
        assertNotNull(
            policy.acceptOrNull(
                mapOf("type" to "mm_wakeup", "event_kind" to "message", "conversation_id" to "c", "target_id" to "m-1"),
                nowMs = 500L
            )
        )
    }
}
