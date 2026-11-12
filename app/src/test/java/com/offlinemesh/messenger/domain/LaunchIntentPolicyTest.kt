package com.offlinemesh.messenger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentPolicyTest {

    private val token = "3f9a0c5e7b1d4a688e2f0b9c6d7e1a23"

    @Test
    fun `answering needs the answer action and this installation's token`() {
        assertTrue(LaunchIntentPolicy.mayAnswer("answer", token, token))
        assertFalse(LaunchIntentPolicy.mayAnswer("answer", null, token))
        assertFalse(LaunchIntentPolicy.mayAnswer("answer", "", token))
        assertFalse(LaunchIntentPolicy.mayAnswer("answer", token.dropLast(1) + "4", token))
        assertFalse(LaunchIntentPolicy.mayAnswer("open", token, token))
        assertFalse(LaunchIntentPolicy.mayAnswer(null, token, token))
    }

    @Test
    fun `an installation without a token never answers`() {
        assertFalse(LaunchIntentPolicy.mayAnswer("answer", "", ""))
    }

    @Test
    fun `ids as the app writes them are accepted`() {
        assertEquals(
            "7b0d8a4e-52c1-4f0a-9b1e-0c2d3e4f5a6b",
            LaunchIntentPolicy.safeId("7b0d8a4e-52c1-4f0a-9b1e-0c2d3e4f5a6b")
        )
        assertEquals("online:7b0d8a4e-52c1", LaunchIntentPolicy.safeId(" online:7b0d8a4e-52c1 "))
    }

    @Test
    fun `anything that is not an id is refused`() {
        assertNull(LaunchIntentPolicy.safeId(null))
        assertNull(LaunchIntentPolicy.safeId(""))
        assertNull(LaunchIntentPolicy.safeId("../../etc/passwd"))
        assertNull(LaunchIntentPolicy.safeId("a b"))
        assertNull(LaunchIntentPolicy.safeId("a?x=1"))
        assertNull(LaunchIntentPolicy.safeId("a".repeat(129)))
        assertNull(LaunchIntentPolicy.safeId("-leading-dash"))
    }

    @Test
    fun `links open a chat or a call screen and nothing else`() {
        assertEquals(
            LaunchIntentPolicy.Target(conversationId = "abc-1"),
            LaunchIntentPolicy.fromUri("offlinemesh", "chat", "abc-1", null)
        )
        assertEquals(
            LaunchIntentPolicy.Target(callId = "call-1"),
            LaunchIntentPolicy.fromUri("offlinemesh", "call", null, "call-1")
        )
        assertNull(LaunchIntentPolicy.fromUri("https", "chat", "abc-1", null))
        assertNull(LaunchIntentPolicy.fromUri("offlinemesh", "settings", "abc-1", null))
        assertNull(LaunchIntentPolicy.fromUri("offlinemesh", "chat", "a/../b", null))
        assertNull(LaunchIntentPolicy.fromUri("offlinemesh", "chat", null, null))
    }
}
