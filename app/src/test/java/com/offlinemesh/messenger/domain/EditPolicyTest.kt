package com.offlinemesh.messenger.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPolicyTest {

    private fun mayApply(
        targetOutgoing: Boolean = false,
        targetDeleted: Boolean = false,
        targetIsText: Boolean = true,
        sameConversation: Boolean = true,
        targetSenderDeviceId: String = "device-alice-1",
        editSenderDeviceId: String = "device-alice-1",
        targetSenderUserId: String? = null,
        editSenderUserId: String? = null
    ) = EditPolicy.mayApply(
        targetOutgoing = targetOutgoing,
        targetDeleted = targetDeleted,
        targetIsText = targetIsText,
        sameConversation = sameConversation,
        targetSenderDeviceId = targetSenderDeviceId,
        editSenderDeviceId = editSenderDeviceId,
        targetSenderUserId = targetSenderUserId,
        editSenderUserId = editSenderUserId
    )

    @Test
    fun `the author's device may edit its own message`() {
        assertTrue(mayApply())
    }

    @Test
    fun `another device of the same account may edit`() {
        assertTrue(
            mayApply(
                editSenderDeviceId = "device-alice-2",
                targetSenderUserId = "alice",
                editSenderUserId = "alice"
            )
        )
    }

    @Test
    fun `another member cannot rewrite somebody else's message`() {
        assertFalse(
            mayApply(
                editSenderDeviceId = "device-mallory",
                targetSenderUserId = "alice",
                editSenderUserId = "mallory"
            )
        )
    }

    @Test
    fun `an unknown device is refused when the accounts cannot be compared`() {
        assertFalse(mayApply(editSenderDeviceId = "device-unknown"))
        assertFalse(mayApply(editSenderDeviceId = "device-unknown", targetSenderUserId = "alice"))
        assertFalse(mayApply(editSenderDeviceId = "device-unknown", editSenderUserId = "alice"))
        assertFalse(
            mayApply(editSenderDeviceId = "device-unknown", targetSenderUserId = "", editSenderUserId = "")
        )
    }

    @Test
    fun `my own messages are never rewritten by an incoming edit`() {
        assertFalse(mayApply(targetOutgoing = true))
    }

    @Test
    fun `deleted and non-text messages are not editable`() {
        assertFalse(mayApply(targetDeleted = true))
        assertFalse(mayApply(targetIsText = false))
    }

    @Test
    fun `an edit from another conversation is ignored`() {
        assertFalse(mayApply(sameConversation = false))
    }

    @Test
    fun `blank device identifiers never match each other`() {
        assertFalse(mayApply(targetSenderDeviceId = "", editSenderDeviceId = ""))
    }
}
