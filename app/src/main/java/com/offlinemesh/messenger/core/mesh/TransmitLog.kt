package com.offlinemesh.messenger.core.mesh

/**
 * Remembers a value per packet id, for a bounded number of packets (least recently used first
 * out). Used to space out retransmissions and repeated receipts; losing an entry only means one
 * packet is sent a little earlier than necessary.
 */
class BoundedMemo<V : Any>(private val maxEntries: Int = 4096) {

    private val entries = object : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): V? = entries[key]

    @Synchronized
    fun put(key: String, value: V) {
        entries[key] = value
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun size(): Int = entries.size
}

/** When each packet was last put on the air. */
class TransmitLog(maxEntries: Int = 4096) {

    private val sentAt = BoundedMemo<Long>(maxEntries)

    fun mark(packetId: String, now: Long) = sentAt.put(packetId, now)

    /** True when [packetId] was never sent, or not within the last [intervalMs]. */
    fun due(packetId: String, now: Long, intervalMs: Long): Boolean {
        val last = sentAt.get(packetId) ?: return true
        // A clock that jumped backwards must not silence a packet for good.
        return last > now || now - last >= intervalMs
    }

    fun forget(packetId: String) = sentAt.remove(packetId)
}
