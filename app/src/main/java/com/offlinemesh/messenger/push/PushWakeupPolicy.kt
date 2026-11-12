package com.offlinemesh.messenger.push

/**
 * Pure validation, zero-plaintext enforcement, expiry filtering, and bounded deduplication for
 * remote push wakeup payloads.
 *
 * Kept free of Android framework classes so JVM unit tests verify every branch deterministically.
 */
data class ValidatedPushWakeup(
    val eventKind: String,
    val conversationId: String,
    val targetId: String,
    val dedupKey: String
)

class PushWakeupPolicy(
    private val maxEntries: Int = 512,
    private val dedupWindowMs: Long = 10 * 60_000L
) {
    private val seen = LinkedHashMap<String, Long>(64, 0.75f, true)

    companion object {
        private val FORBIDDEN_PLAINTEXT_KEYS = setOf(
            "body",
            "plaintext",
            "text",
            "message_body",
            "sender_name",
            "file_name",
            "media_key",
            "sdp"
        )
        private val ALLOWED_EVENT_KINDS = setOf("message", "call")
    }

    /**
     * Validates an incoming FCM `data` payload:
     *  1. Rejects non-`mm_wakeup` types or unknown `event_kind`.
     *  2. Rejects any payload containing forbidden plaintext keys (defense-in-depth).
     *  3. Drops expired wakeups (`expires_at_ms < nowMs`).
     *  4. Suppresses duplicate deliveries within [dedupWindowMs].
     */
    @Synchronized
    fun acceptOrNull(data: Map<String, String>, nowMs: Long = System.currentTimeMillis()): ValidatedPushWakeup? {
        if (data["type"] != "mm_wakeup") return null
        if (data.keys.any { it.lowercase() in FORBIDDEN_PLAINTEXT_KEYS }) return null

        val eventKind = data["event_kind"]?.lowercase() ?: return null
        if (eventKind !in ALLOWED_EVENT_KINDS) return null

        val conversationId = data["conversation_id"]?.takeIf { it.isNotBlank() } ?: return null
        val targetId = data["target_id"]?.takeIf { it.isNotBlank() } ?: conversationId

        val expiresAtMs = data["expires_at_ms"]?.toLongOrNull()
        if (expiresAtMs != null && expiresAtMs < nowMs) return null

        val dedupKey = "$eventKind:$conversationId:$targetId"
        val previous = seen[dedupKey]
        if (previous != null && nowMs - previous < dedupWindowMs) {
            return null
        }
        seen[dedupKey] = nowMs
        while (seen.size > maxEntries) {
            val eldest = seen.entries.iterator().next().key
            seen.remove(eldest)
        }
        return ValidatedPushWakeup(
            eventKind = eventKind,
            conversationId = conversationId,
            targetId = targetId,
            dedupKey = dedupKey
        )
    }

    /**
     * Forgets a wakeup that was accepted but could not be acted on (no session yet, no network at
     * that moment). Without this the server's next push for the same message would be discarded
     * as a duplicate for [dedupWindowMs], although nothing had been fetched.
     */
    @Synchronized
    fun forget(dedupKey: String) {
        seen.remove(dedupKey)
    }
}
