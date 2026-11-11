package com.offlinemesh.messenger.domain

/**
 * Pure, deterministic reconciliation rules for messages that traverse both the offline Nearby
 * mesh and the online Supabase transport.
 */
object HybridReconciler {

    private val STATUS_RANK = mapOf(
        "FAILED" to 0,
        "QUEUED" to 1,
        "SENDING" to 2,
        "SENT" to 3,
        "RECEIVED" to 3,
        "DELIVERED" to 4
    )

    /**
     * Merges transport provenance (`MESH`, `ONLINE`, `BOTH`) idempotently regardless of which
     * transport delivered the message first.
     */
    fun mergeTransport(existing: String, incoming: String): String {
        val a = existing.uppercase()
        val b = incoming.uppercase()
        if (a == "BOTH" || b == "BOTH") return "BOTH"
        if ((a == "MESH" && b == "ONLINE") || (a == "ONLINE" && b == "MESH")) return "BOTH"
        return b.ifBlank { a.ifBlank { "MESH" } }
    }

    /**
     * Monotonically reconciles outgoing delivery state so a late `SENT` confirmation on one
     * transport never regresses a message already `DELIVERED` on the other transport.
     */
    fun reconcileStatus(currentStatus: String, candidateStatus: String): String {
        val currentRank = STATUS_RANK[currentStatus] ?: 0
        val candidateRank = STATUS_RANK[candidateStatus] ?: 0
        return if (candidateRank >= currentRank) candidateStatus else currentStatus
    }

    /** Picks the earliest confirmed delivery timestamp across mesh ACK and online receipt. */
    fun reconcileTimestamp(existingAt: Long?, incomingAt: Long?): Long? = when {
        existingAt == null -> incomingAt
        incomingAt == null -> existingAt
        else -> minOf(existingAt, incomingAt)
    }

    /**
     * Deterministic comparator for conversation timelines when packets arrive out of order across
     * offline mesh and online sync.
     */
    fun compareMessages(
        tsA: Long,
        clientIdA: String,
        idA: String,
        tsB: Long,
        clientIdB: String,
        idB: String
    ): Int {
        val byTs = tsA.compareTo(tsB)
        if (byTs != 0) return byTs
        val keyA = clientIdA.ifBlank { idA }
        val keyB = clientIdB.ifBlank { idB }
        val byClientKey = keyA.compareTo(keyB)
        if (byClientKey != 0) return byClientKey
        return idA.compareTo(idB)
    }
}
