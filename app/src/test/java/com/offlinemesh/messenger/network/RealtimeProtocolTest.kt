package com.offlinemesh.messenger.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class RealtimeProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `join frame carries the table, filter and access token`() {
        val frame = decode(
            RealtimeProtocol.joinFrame(
                RealtimeProtocol.Subscription("mm_message_envelopes", "recipient_user_id=eq.u1"),
                accessToken = "jwt",
                ref = 1
            )
        )
        assertEquals("realtime:mm_message_envelopes", frame["topic"]!!.jsonPrimitive.content)
        assertEquals("phx_join", frame["event"]!!.jsonPrimitive.content)
        val payload = frame["payload"]!!.jsonObject
        assertEquals("jwt", payload["access_token"]!!.jsonPrimitive.content)
        val change = payload["config"]!!.jsonObject["postgres_changes"]!!
            .let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("mm_message_envelopes", change["table"]!!.jsonPrimitive.content)
        assertEquals("recipient_user_id=eq.u1", change["filter"]!!.jsonPrimitive.content)
        assertEquals("public", change["schema"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscription without a filter omits the filter key`() {
        val frame = decode(RealtimeProtocol.joinFrame(RealtimeProtocol.Subscription("mm_messages"), "jwt", 2))
        val change = frame["payload"]!!.jsonObject["config"]!!.jsonObject["postgres_changes"]!!
            .let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertNull(change["filter"])
    }

    @Test
    fun `heartbeat uses the phoenix topic`() {
        val frame = decode(RealtimeProtocol.heartbeatFrame(7))
        assertEquals("phoenix", frame["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", frame["event"]!!.jsonPrimitive.content)
        assertEquals("7", frame["ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `postgres changes frame becomes a typed change`() {
        val raw = """
            {"event":"postgres_changes","payload":{"data":{
              "type":"INSERT","table":"mm_messages",
              "record":{"id":"m1","conversation_id":"c1"},
              "old_record":null}}}
        """.trimIndent()
        val frame = RealtimeProtocol.parse(raw)
        assertTrue(frame is RealtimeProtocol.Frame.Changes)
        val change = (frame as RealtimeProtocol.Frame.Changes).changes.single()
        assertEquals("INSERT", change.type)
        assertEquals("mm_messages", change.table)
        assertEquals("m1", change.record["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `join replies are recognised in both directions`() {
        val ok = RealtimeProtocol.parse("""{"event":"phx_reply","payload":{"status":"ok"},"ref":"1"}""")
        assertTrue(ok is RealtimeProtocol.Frame.Joined)
        val failed = RealtimeProtocol.parse(
            """{"event":"phx_reply","payload":{"status":"error","response":{"reason":"bad token"}},"ref":"1"}"""
        )
        assertTrue(failed is RealtimeProtocol.Frame.JoinFailed)
        assertEquals("bad token", (failed as RealtimeProtocol.Frame.JoinFailed).reason)
    }

    @Test
    fun `malformed frames never throw`() {
        assertNull(RealtimeProtocol.parse("not json"))
        assertNull(RealtimeProtocol.parse("{}"))
        assertTrue(RealtimeProtocol.parse("""{"event":"phx_close"}""") is RealtimeProtocol.Frame.Other)
    }
}
