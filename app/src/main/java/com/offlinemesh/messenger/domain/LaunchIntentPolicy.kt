package com.offlinemesh.messenger.domain

import java.security.MessageDigest

/**
 * What an intent that starts the main activity is allowed to do.
 *
 * The activity is exported (it is the launcher entry and handles `offlinemesh://` links), so any
 * app on the device - and, for links, any web page - can start it with whatever extras and URI it
 * likes. Opening a chat on request is harmless. Answering a call is not: it turns the microphone
 * on. That action is therefore only honoured when the intent carries a token that this app put
 * into its own notification, which other apps cannot read.
 */
object LaunchIntentPolicy {

    const val ACTION_ANSWER = "answer"

    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9:_.-]{0,127}$")

    data class Target(val conversationId: String? = null, val callId: String? = null)

    /** A conversation or call id as this app writes them, or null for anything else. */
    fun safeId(value: String?): String? = value?.trim()?.takeIf { ID.matches(it) }

    /** True only for the answer action accompanied by the token of this installation. */
    fun mayAnswer(action: String?, presentedToken: String?, expectedToken: String): Boolean {
        if (action != ACTION_ANSWER || presentedToken.isNullOrEmpty() || expectedToken.isEmpty()) return false
        return MessageDigest.isEqual(
            presentedToken.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8)
        )
    }

    /** Target of an `offlinemesh://chat/<id>` or `offlinemesh://call/<id>` link. */
    fun fromUri(scheme: String?, host: String?, lastPathSegment: String?, queryId: String?): Target? {
        if (scheme != "offlinemesh") return null
        val id = safeId(lastPathSegment) ?: safeId(queryId) ?: return null
        return when (host) {
            "chat" -> Target(conversationId = id)
            "call" -> Target(callId = id)
            else -> null
        }
    }
}
