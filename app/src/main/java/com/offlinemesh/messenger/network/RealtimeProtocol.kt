package com.offlinemesh.messenger.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Supabase Realtime wire protocol (Phoenix channels over WebSocket), isolated from the socket so
 * every frame shape is unit-testable on the JVM.
 *
 * Frames are plain JSON text:
 *   join  -> {"topic":"realtime:mm_messages","event":"phx_join","payload":{config,access_token},"ref":n}
 *   beat  -> {"topic":"phoenix","event":"heartbeat","payload":{},"ref":n}
 *   event -> {"event":"postgres_changes","payload":{"data":{type,table,record,old_record}}}
 */
object RealtimeProtocol {

    const val HEARTBEAT_INTERVAL_MS = 25_000L

    private val json = Json { ignoreUnknownKeys = true }

    data class Subscription(val table: String, val filter: String? = null)

    data class Change(
        val type: String,
        val table: String,
        val record: JsonObject,
        val oldRecord: JsonObject?
    )

    sealed interface Frame {
        data class Changes(val changes: List<Change>) : Frame
        data class Joined(val topic: String) : Frame
        data class JoinFailed(val topic: String, val reason: String?) : Frame
        data class Other(val event: String) : Frame
    }

    fun topic(table: String): String = "realtime:$table"

    fun joinFrame(subscription: Subscription, accessToken: String, ref: Long): String =
        buildJsonObject {
            put("topic", topic(subscription.table))
            put("event", "phx_join")
            put("ref", ref.toString())
            put(
                "payload",
                buildJsonObject {
                    put(
                        "config",
                        buildJsonObject {
                            put(
                                "postgres_changes",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("event", "*")
                                            put("schema", "public")
                                            put("table", subscription.table)
                                            subscription.filter?.let { put("filter", it) }
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("access_token", accessToken)
                }
            )
        }.toString()

    fun heartbeatFrame(ref: Long): String = buildJsonObject {
        put("topic", "phoenix")
        put("event", "heartbeat")
        put("ref", ref.toString())
        put("payload", buildJsonObject { })
    }.toString()

    fun leaveFrame(table: String, ref: Long): String = buildJsonObject {
        put("topic", topic(table))
        put("event", "phx_leave")
        put("ref", ref.toString())
        put("payload", buildJsonObject { })
    }.toString()

    fun parse(text: String): Frame? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return null
        val topic = obj["topic"]?.jsonPrimitive?.contentOrNull
        val payload = obj["payload"] as? JsonObject
        when (event) {
            "postgres_changes" -> {
                val data = payload?.get("data") as? JsonObject ?: return null
                val type = data["type"]?.jsonPrimitive?.contentOrNull ?: "INSERT"
                val table = data["table"]?.jsonPrimitive?.contentOrNull
                    ?: topic?.removePrefix("realtime:")
                    ?: return null
                val record = data["record"] as? JsonObject ?: JsonObject(emptyMap())
                val old = data["old_record"] as? JsonObject
                return Frame.Changes(listOf(Change(type, table, record, old)))
            }

            "phx_reply" -> {
                val status = payload?.get("status")?.jsonPrimitive?.contentOrNull
                val response = payload?.get("response") as? JsonObject
                val reason = response?.get("reason")?.jsonPrimitive?.contentOrNull
                    ?: response?.toString()
                val target = topic.orEmpty()
                return if (status == "ok") Frame.Joined(target) else Frame.JoinFailed(target, reason)
            }

            else -> return Frame.Other(event)
        }
    }
}
