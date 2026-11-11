package com.offlinemesh.messenger.data

import com.offlinemesh.messenger.core.crypto.BundleKey
import com.offlinemesh.messenger.core.crypto.PreKeyBundleJson
import com.offlinemesh.messenger.network.PostgrestClient
import com.offlinemesh.messenger.network.SupabaseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

// ---------------------------------------------------------------------------- remote models

data class RemoteProfile(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?
)

data class RemoteDevice(
    val rowId: String,
    val userId: String,
    val deviceId: String,
    val identityKey: String
)

data class RemoteConversation(
    val id: String,
    val type: String,
    val title: String,
    val ownerId: String,
    val memberUserIds: List<String>,
    val memberRoles: Map<String, String>,
    val lastMessageAtMs: Long?
)

data class RemoteMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderDeviceId: String,
    val clientMsgId: String,
    val kind: String,
    val sentAtMs: Long,
    val deleted: Boolean,
    val meta: JsonObject
)

data class RemoteEnvelope(
    val messageId: String,
    val ciphertext: String,
    val cipherType: Int
)

data class RemoteReceipt(
    val messageId: String,
    val userId: String,
    val deliveredAtMs: Long?,
    val readAtMs: Long?
)

data class RemoteReaction(
    val messageId: String,
    val userId: String,
    val emoji: String
)

data class OutgoingEnvelope(
    val messageId: String,
    val recipientUserId: String,
    val recipientDeviceRowId: String,
    val senderDeviceRowId: String?,
    val cipherType: Int,
    val ciphertextBase64: String
)

data class RemoteChannel(
    val id: String,
    val title: String,
    val username: String?,
    val description: String?,
    val avatarUrl: String?
)

/**
 * One function per backend operation the client needs, so the sync engine contains no query
 * strings. Every call is authorised by RLS and the security-definer RPCs; this class never tries
 * to enforce permissions on the client, it simply surfaces the server's decision.
 */
class OnlineApi(
    private val rest: PostgrestClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
) {

    // ------------------------------------------------------------------ profile/devices

    suspend fun ensureProfile(displayName: String, username: String?): RemoteProfile {
        val result = rest.rpc(
            "mm_ensure_profile",
            buildJsonObject {
                put("p_display_name", displayName)
                put("p_username", username?.trim()?.lowercase())
            }
        )
        val obj = result?.jsonObject ?: throw SupabaseException(0, "profile", "Profile was not returned")
        return profileOf(obj)
    }

    suspend fun registerDevice(
        ownerUserId: String,
        deviceId: String,
        deviceName: String,
        bundle: PreKeyBundleJson,
        preKeys: List<BundleKey>
    ): String {
        val args = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_name", deviceName)
            put("p_platform", "android")
            put("p_identity_key", bundle.identityKey)
            put("p_registration_id", bundle.registrationId)
            put("p_signed", bundle.signedPreKey.toJson())
            put("p_kyber", bundle.kyberPreKey.toJson())
            put("p_prekeys", buildJsonArray { preKeys.forEach { add(it.toJson()) } })
        }
        val result = rest.rpc("mm_register_device", args)
        return result?.jsonPrimitive?.contentOrNull
            ?: throw SupabaseException(0, "device", "Device registration failed")
    }

    suspend fun devicesOf(userIds: List<String>): List<RemoteDevice> {
        if (userIds.isEmpty()) return emptyList()
        val rows = rest.select(
            "mm_devices",
            listOf(
                "select" to "id,user_id,device_id,identity_key",
                "user_id" to "in.(${userIds.joinToString(",")})",
                "revoked_at" to "is.null"
            )
        )
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val userId = obj.str("user_id") ?: return@mapNotNull null
            RemoteDevice(
                rowId = id,
                userId = userId,
                deviceId = obj.str("device_id").orEmpty(),
                identityKey = obj.str("identity_key").orEmpty()
            )
        }
    }

    suspend fun claimBundle(deviceRowId: String): PreKeyBundleJson {
        val result = rest.rpc("mm_claim_prekey_bundle", buildJsonObject { put("p_device", deviceRowId) })
        val obj = result?.jsonObject ?: throw SupabaseException(0, "bundle", "Pre-key bundle unavailable")
        return json.decodeFromString(PreKeyBundleJson.serializer(), obj.toString())
    }

    suspend fun revokeDevice(deviceRowId: String) {
        rest.rpc("mm_revoke_device", buildJsonObject { put("p_device", deviceRowId) })
    }

    suspend fun touchLastSeen() {
        runCatching { rest.rpc("mm_touch_last_seen", buildJsonObject { }) }
    }

    // ----------------------------------------------------------------- conversations

    suspend fun startDirectConversation(peerUserId: String): String {
        val result = rest.rpc(
            "mm_start_direct_conversation",
            buildJsonObject { put("p_other", peerUserId) }
        )
        return result?.jsonPrimitive?.contentOrNull
            ?: throw SupabaseException(0, "conversation", "Could not open the conversation")
    }

    suspend fun createGroup(title: String, memberUserIds: List<String>): String {
        val result = rest.rpc(
            "mm_create_group",
            buildJsonObject {
                put("p_title", title)
                put("p_members", buildJsonArray { memberUserIds.forEach { add(JsonPrimitive(it)) } })
            }
        )
        return result?.jsonPrimitive?.contentOrNull
            ?: throw SupabaseException(0, "conversation", "Could not create the group")
    }

    suspend fun listConversations(myUserId: String): List<RemoteConversation> {
        val conversations = rest.select(
            "mm_conversations",
            listOf(
                "select" to "id,type,title,owner_id,last_message_at",
                "deleted_at" to "is.null",
                "order" to "last_message_at.desc.nullslast",
                "limit" to "200"
            )
        )
        val memberRows = rest.select(
            "mm_conversation_members",
            listOf("select" to "conversation_id,user_id,role", "user_id" to "eq.$myUserId")
        )
        val mine = memberRows.mapNotNull { (it as? JsonObject)?.str("conversation_id") }.toSet()
        val rolesByConversation = HashMap<String, MutableMap<String, String>>()
        if (mine.isNotEmpty()) {
            val allMembers = rest.select(
                "mm_conversation_members",
                listOf(
                    "select" to "conversation_id,user_id,role",
                    "conversation_id" to "in.(${mine.joinToString(",")})"
                )
            )
            allMembers.forEach { row ->
                val obj = row as? JsonObject ?: return@forEach
                val conversationId = obj.str("conversation_id") ?: return@forEach
                val userId = obj.str("user_id") ?: return@forEach
                val role = obj.str("role") ?: "member"
                rolesByConversation.getOrPut(conversationId) { HashMap() }[userId] = role
            }
        }
        return conversations.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            if (id !in mine) return@mapNotNull null
            val roles = rolesByConversation[id].orEmpty()
            RemoteConversation(
                id = id,
                type = obj.str("type") ?: "direct",
                title = obj.str("title").orEmpty(),
                ownerId = obj.str("owner_id").orEmpty(),
                memberUserIds = roles.keys.toList(),
                memberRoles = roles,
                lastMessageAtMs = obj.str("last_message_at")?.let { isoToMillis(it) }
            )
        }
    }

    suspend fun membersOf(conversationId: String): Map<String, String> {
        val rows = rest.select(
            "mm_conversation_members",
            listOf(
                "select" to "user_id,role",
                "conversation_id" to "eq.$conversationId"
            )
        )
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val userId = obj.str("user_id") ?: return@mapNotNull null
            userId to (obj.str("role") ?: "member")
        }.toMap()
    }

    suspend fun profiles(userIds: List<String>): List<RemoteProfile> {
        if (userIds.isEmpty()) return emptyList()
        val rows = rest.select(
            "mm_profiles",
            listOf(
                "select" to "id,username,display_name,avatar_url",
                "id" to "in.(${userIds.joinToString(",")})"
            )
        )
        return rows.mapNotNull { (it as? JsonObject)?.let(::profileOf) }
    }

    // ---------------------------------------------------------------------- messages

    suspend fun uploadMessage(
        senderUserId: String,
        senderDeviceId: String,
        conversationId: String,
        clientMsgId: String,
        kind: String,
        meta: JsonObject
    ): RemoteMessage {
        val rows = rest.insert(
            "mm_messages",
            listOf(
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("sender_id", senderUserId)
                    put("sender_device_id", senderDeviceId)
                    put("client_msg_id", clientMsgId)
                    put("kind", kind)
                    put("meta", meta)
                    put("sent_at_ms", System.currentTimeMillis())
                }
            ),
            onConflict = "conversation_id,client_msg_id",
            ignoreDuplicates = true
        )
        rows.firstOrNull()?.let { row ->
            val obj = row as? JsonObject ?: return@let
            messageOf(obj)?.let { return it }
        }
        // Re-upload of an already stored message (offline retry): fetch the existing row.
        return messagesByClientId(conversationId, clientMsgId)
            ?: throw SupabaseException(0, "message", "Message upload was not stored")
    }

    suspend fun messagesByClientId(conversationId: String, clientMsgId: String): RemoteMessage? {
        val rows = rest.select(
            "mm_messages",
            listOf(
                "select" to MESSAGE_FIELDS,
                "conversation_id" to "eq.$conversationId",
                "client_msg_id" to "eq.$clientMsgId",
                "limit" to "1"
            )
        )
        return (rows.firstOrNull() as? JsonObject)?.let(::messageOf)
    }

    suspend fun messagesSince(conversationId: String, sinceMs: Long, limit: Int = 200): List<RemoteMessage> {
        val rows = rest.select(
            "mm_messages",
            listOf(
                "select" to MESSAGE_FIELDS,
                "conversation_id" to "eq.$conversationId",
                "sent_at_ms" to "gte.$sinceMs",
                "order" to "sent_at_ms.asc",
                "limit" to limit.toString()
            )
        )
        return rows.mapNotNull { (it as? JsonObject)?.let(::messageOf) }
    }

    suspend fun messageById(messageId: String): RemoteMessage? {
        val rows = rest.select(
            "mm_messages",
            listOf("select" to MESSAGE_FIELDS, "id" to "eq.$messageId", "limit" to "1")
        )
        return (rows.firstOrNull() as? JsonObject)?.let(::messageOf)
    }

    suspend fun putEnvelopes(envelopes: List<OutgoingEnvelope>) {
        if (envelopes.isEmpty()) return
        val rows = envelopes.map { envelope ->
            buildJsonObject {
                put("message_id", envelope.messageId)
                put("recipient_user_id", envelope.recipientUserId)
                put("recipient_device", envelope.recipientDeviceRowId)
                envelope.senderDeviceRowId?.let { put("sender_device", it) }
                put("cipher_type", envelope.cipherType)
                put("ciphertext", envelope.ciphertextBase64)
            }
        }
        rest.insert("mm_message_envelopes", rows, onConflict = "message_id,recipient_device")
    }

    suspend fun envelopesFor(messageIds: List<String>, recipientUserId: String): List<RemoteEnvelope> {
        if (messageIds.isEmpty()) return emptyList()
        val rows = rest.select(
            "mm_message_envelopes",
            listOf(
                "select" to "message_id,ciphertext,cipher_type",
                "recipient_user_id" to "eq.$recipientUserId",
                "message_id" to "in.(${messageIds.joinToString(",")})",
                "limit" to "500"
            )
        )
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            RemoteEnvelope(
                messageId = obj.str("message_id") ?: return@mapNotNull null,
                ciphertext = obj.str("ciphertext") ?: return@mapNotNull null,
                cipherType = (obj["cipher_type"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 3
            )
        }
    }

    suspend fun deleteEnvelope(messageId: String, recipientUserId: String) {
        rest.delete(
            "mm_message_envelopes",
            listOf(
                "message_id" to "eq.$messageId",
                "recipient_user_id" to "eq.$recipientUserId"
            )
        )
    }

    // ------------------------------------------------------------------- receipts

    suspend fun markDelivered(messageId: String, userId: String) {
        rest.insert(
            "mm_message_receipts",
            listOf(
                buildJsonObject {
                    put("message_id", messageId)
                    put("user_id", userId)
                    put("delivered_at", Instant.now().toString())
                }
            ),
            onConflict = "message_id,user_id"
        )
    }

    suspend fun markRead(conversationId: String, messageId: String) {
        rest.rpc(
            "mm_set_read",
            buildJsonObject {
                put("p_conversation", conversationId)
                put("p_message", messageId)
            }
        )
    }

    suspend fun receiptsFor(messageIds: List<String>): List<RemoteReceipt> {
        if (messageIds.isEmpty()) return emptyList()
        val rows = rest.select(
            "mm_message_receipts",
            listOf(
                "select" to "message_id,user_id,delivered_at,read_at",
                "message_id" to "in.(${messageIds.joinToString(",")})"
            )
        )
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            RemoteReceipt(
                messageId = obj.str("message_id") ?: return@mapNotNull null,
                userId = obj.str("user_id").orEmpty(),
                deliveredAtMs = obj.str("delivered_at")?.let { isoToMillis(it) },
                readAtMs = obj.str("read_at")?.let { isoToMillis(it) }
            )
        }
    }

    // -------------------------------------------------------------------- typing

    suspend fun setTyping(conversationId: String, userId: String) {
        rest.insert(
            "mm_typing",
            listOf(
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("user_id", userId)
                    put("expires_at", Instant.now().plusSeconds(12).toString())
                }
            ),
            onConflict = "conversation_id,user_id"
        )
    }

    // ------------------------------------------------------------------ reactions

    suspend fun addReaction(messageId: String, userId: String, emoji: String) {
        rest.insert(
            "mm_reactions",
            listOf(
                buildJsonObject {
                    put("message_id", messageId)
                    put("user_id", userId)
                    put("emoji", emoji)
                }
            ),
            onConflict = "message_id,user_id,emoji",
            ignoreDuplicates = true
        )
    }

    suspend fun removeReaction(messageId: String, userId: String, emoji: String) {
        rest.delete(
            "mm_reactions",
            listOf(
                "message_id" to "eq.$messageId",
                "user_id" to "eq.$userId",
                "emoji" to "eq.$emoji"
            )
        )
    }

    suspend fun reactionsFor(messageIds: List<String>): List<RemoteReaction> {
        if (messageIds.isEmpty()) return emptyList()
        val rows = rest.select(
            "mm_reactions",
            listOf(
                "select" to "message_id,user_id,emoji",
                "message_id" to "in.(${messageIds.joinToString(",")})"
            )
        )
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            RemoteReaction(
                messageId = obj.str("message_id") ?: return@mapNotNull null,
                userId = obj.str("user_id").orEmpty(),
                emoji = obj.str("emoji").orEmpty()
            )
        }
    }

    // ------------------------------------------------------------- discovery/channels

    suspend fun searchUsers(query: String): List<RemoteProfile> {
        val result = rest.rpc("mm_search_users", buildJsonObject { put("p_query", query) })
        val array = result as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::profileOf) }
    }

    suspend fun searchChannels(query: String): List<RemoteChannel> {
        val result = rest.rpc("mm_search_public_channels", buildJsonObject { put("p_query", query) })
        val array = result as? JsonArray ?: return emptyList()
        return array.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            RemoteChannel(
                id = obj.str("id") ?: return@mapNotNull null,
                title = obj.str("title").orEmpty(),
                username = obj.str("username"),
                description = obj.str("description"),
                avatarUrl = obj.str("avatar_url")
            )
        }
    }

    suspend fun createChannel(title: String, username: String?, isPublic: Boolean, description: String?): String {
        val result = rest.rpc(
            "mm_create_channel",
            buildJsonObject {
                put("p_title", title)
                put("p_username", username)
                put("p_public", isPublic)
                put("p_description", description)
            }
        )
        return result?.jsonPrimitive?.contentOrNull
            ?: throw SupabaseException(0, "channel", "Could not create the channel")
    }

    suspend fun joinChannel(conversationId: String, inviteCode: String?): Boolean {
        val result = rest.rpc(
            "mm_join_channel",
            buildJsonObject {
                put("p_conversation", conversationId)
                put("p_invite", inviteCode)
            }
        )
        return (result as? JsonPrimitive)?.booleanOrNull ?: false
    }

    suspend fun leaveConversation(conversationId: String) {
        rest.rpc("mm_leave_conversation", buildJsonObject { put("p_conversation", conversationId) })
    }

    suspend fun report(targetKind: String, targetId: String, reason: String, details: String? = null) {
        rest.rpc(
            "mm_report",
            buildJsonObject {
                put("p_target_kind", targetKind)
                put("p_target_id", targetId)
                put("p_reason", reason)
                put("p_details", details)
            }
        )
    }

    suspend fun deleteAccount() {
        rest.rpc("mm_delete_me", buildJsonObject { })
    }

    suspend fun editMessage(
        messageId: String,
        conversationId: String,
        body: String,
        editedAtMs: Long
    ) {
        rest.patch(
            "mm_messages",
            listOf("id" to "eq.$messageId", "conversation_id" to "eq.$conversationId"),
            buildJsonObject {
                put("edited_at", Instant.ofEpochMilli(editedAtMs).toString())
                // The edited body still lives in the per-device envelopes; the row only records
                // that an edit happened so peers know to re-fetch and re-verify the ciphertext.
                put("kind", "text-edited")
            }
        )
    }

    suspend fun deleteMessageForEveryone(messageId: String, conversationId: String) {
        rest.patch(
            "mm_messages",
            listOf("id" to "eq.$messageId", "conversation_id" to "eq.$conversationId"),
            buildJsonObject { put("deleted_at", Instant.now().toString()) }
        )
    }

    // --------------------------------------------------------------------- mapping

    private fun profileOf(obj: JsonObject): RemoteProfile = RemoteProfile(
        userId = obj.str("id").orEmpty(),
        username = obj.str("username").orEmpty(),
        displayName = obj.str("display_name").orEmpty(),
        avatarUrl = obj.str("avatar_url")
    )

    private fun messageOf(obj: JsonObject): RemoteMessage? {
        val id = obj.str("id") ?: return null
        val conversationId = obj.str("conversation_id") ?: return null
        val meta = (obj["meta"] as? JsonObject) ?: JsonObject(emptyMap())
        return RemoteMessage(
            id = id,
            conversationId = conversationId,
            senderId = obj.str("sender_id").orEmpty(),
            senderDeviceId = obj.str("sender_device_id").orEmpty(),
            clientMsgId = obj.str("client_msg_id").orEmpty(),
            kind = obj.str("kind") ?: "text",
            sentAtMs = (obj["sent_at_ms"] as? JsonPrimitive)?.longOrNull
                ?: obj.str("sent_at")?.let { isoToMillis(it) }
                ?: System.currentTimeMillis(),
            deleted = !obj.str("deleted_at").isNullOrBlank(),
            meta = meta
        )
    }

    private fun BundleKey.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("public", public)
        signature?.let { put("signature", it) }
    }

    private companion object {
        const val MESSAGE_FIELDS =
            "id,conversation_id,sender_id,sender_device_id,client_msg_id,kind,sent_at,sent_at_ms,deleted_at,meta"

        fun isoToMillis(value: String): Long? =
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
