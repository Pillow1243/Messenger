package com.offlinemesh.messenger.domain

/**
 * Decides whether an incoming "text-edit" may rewrite a message that is already stored locally.
 *
 * The server enforces the same rule (only the author may publish an edit, see the
 * `mm_guard_client_message` trigger), but the server is not trusted with message content, so the
 * device re-checks it: an edit is applied only when it was decrypted from a session with the
 * device that wrote the original, or from another device that is known to belong to the same
 * account. Without this check any member of a conversation could rewrite what somebody else said.
 */
object EditPolicy {

    /**
     * @param targetOutgoing whether the stored message was written by this account.
     * @param targetDeleted whether the stored message was deleted for everyone.
     * @param targetIsText whether the stored message is a plain text message.
     * @param sameConversation whether the edit arrived in the conversation of the stored message.
     * @param targetSenderDeviceId device that wrote the stored message.
     * @param editSenderDeviceId device whose session decrypted the edit.
     * @param targetSenderUserId account owning [targetSenderDeviceId], when known.
     * @param editSenderUserId account owning [editSenderDeviceId], when known.
     */
    fun mayApply(
        targetOutgoing: Boolean,
        targetDeleted: Boolean,
        targetIsText: Boolean,
        sameConversation: Boolean,
        targetSenderDeviceId: String,
        editSenderDeviceId: String,
        targetSenderUserId: String?,
        editSenderUserId: String?
    ): Boolean {
        if (targetOutgoing || targetDeleted || !targetIsText || !sameConversation) return false
        if (editSenderDeviceId.isBlank() || targetSenderDeviceId.isBlank()) return false
        if (targetSenderDeviceId == editSenderDeviceId) return true
        return !targetSenderUserId.isNullOrBlank() && targetSenderUserId == editSenderUserId
    }
}
