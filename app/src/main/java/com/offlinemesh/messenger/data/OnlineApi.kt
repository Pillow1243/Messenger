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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import java.io.File
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
    val identityKey: String,
    val name: String = "",
    val platform: String = "android",
    val lastSeenAtMs: Long = 0L,
    val revokedAtMs: Long? = null
)

data class RemoteConversation(
    val id: String,
    val type: String,
    val title: String,
    val ownerId: String,
    val memberUserIds: List<String>,
    val memberRoles: Map<String, String>,
    val lastMessageAtMs: Long?,
    val encryptionVersion: Int = 1,
    val senderKeyEpoch: Int = 1,
    val distributionId: String? = null
)

data class RemoteMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderDeviceId: String,
    val senderDeviceRowId: String? = null,
    val clientMsgId: String,
    val kind: String,
    val sentAtMs: Long,
    val deleted: Boolean,
    val meta: JsonObject,
    val attachmentId: String? = null,
    val groupCiphertext: String? = null,
    val groupCipherEpoch: Int? = null,
    val groupDistributionId: String? = null
)

data class RemoteEnvelope(
    val messageId: String,
    val ciphertext: String,
    val cipherType: Int
)

data class OutgoingSkdm(
    val conversationId: String,
    val epoch: Int,
    val distributionId: String,
    val senderUserId: String,
    val senderDeviceRowId: String,
    val recipientUserId: String,
    val recipientDeviceRowId: String,
    val cipherType: Int,
    val ciphertextBase64: String
)

data class RemoteSkdm(
    val id: Long,
    val conversationId: String,
    val epoch: Int,
    val distributionId: String,
    val senderUserId: String,
    val senderDeviceRowId: String,
    val cipherType: Int,
    val ciphertextBase64: String
)

data class RemoteAttachment(
    val id: String,
    val conversationId: String,
    val bucket: String,
    val objectPath: String,
    val sizeBytes: Long,
    val sha256: String
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

data class RemoteCall(
    val id: String,
    val conversationId: String,
    val initiatorId: String,
    val kind: String,
    val state: String,
    val startedAtMs: Long,
    val answeredAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val endReason: String? = null
)

data class RemoteCallSignal(
    val id: Long,
    val callId: String,
    val fromUserId: String,
    val toUserId: String,
    val kind: String,
    val payload: JsonObject,
    val clientSignalId: String? = null
)

data class TurnIceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class TurnCredentialsResponse(
    val iceServers: List<TurnIceServerConfig>,
    val turnConfigured: Boolean,
    val provider: String
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

    suspend fun rotateSignedPreKeys(deviceId: String, signed: BundleKey, kyber: BundleKey) {
        rest.rpc(
            "mm_rotate_signed_prekeys",
            buildJsonObject {
                put("p_device_id", deviceId)
                put("p_signed", signed.toJson())
                put("p_kyber", kyber.toJson())
            }
        )
    }

    suspend fun devicesOf(userIds: List<String>, includeRevoked: Boolean = false): List<RemoteDevice> {
        if (userIds.isEmpty()) return emptyList()
        val params = buildList {
            add("select" to "id,user_id,device_id,name,platform,identity_key,last_seen_at,revoked_at")
            add("user_id" to "in.(${userIds.joinToString(",")})")
            if (!includeRevoked) add("revoked_at" to "is.null")
            add("order" to "last_seen_at.desc")
        }
        val rows = rest.select("mm_devices", params)
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val userId = obj.str("user_id") ?: return@mapNotNull null
            RemoteDevice(
                rowId = id,
                userId = userId,
                deviceId = obj.str("device_id").orEmpty(),
                identityKey = obj.str("identity_key").orEmpty(),
                name = obj.str("name").orEmpty(),
                platform = obj.str("platform") ?: "android",
                lastSeenAtMs = obj.str("last_seen_at")?.let(::isoToMillis) ?: 0L,
                revokedAtMs = obj.str("revoked_at")?.let(::isoToMillis)
            )
        }
    }

    suspend fun myDevices(myUserId: String): List<RemoteDevice> =
        devicesOf(listOf(myUserId), includeRevoked = true)

    suspend fun deviceByRowId(deviceRowId: String): RemoteDevice? {
        val rows = rest.select(
            "mm_devices",
            listOf(
                "select" to "id,user_id,device_id,name,platform,identity_key,last_seen_at,revoked_at",
                "id" to "eq.$deviceRowId",
                "limit" to "1"
            )
        )
        val obj = rows.firstOrNull() as? JsonObject ?: return null
        val id = obj.str("id") ?: return null
        val userId = obj.str("user_id") ?: return null
        return RemoteDevice(
            rowId = id,
            userId = userId,
            deviceId = obj.str("device_id").orEmpty(),
            identityKey = obj.str("identity_key").orEmpty(),
            name = obj.str("name").orEmpty(),
            platform = obj.str("platform") ?: "android",
            lastSeenAtMs = obj.str("last_seen_at")?.let(::isoToMillis) ?: 0L,
            revokedAtMs = obj.str("revoked_at")?.let(::isoToMillis)
        )
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

    // ------------------------------------------------------------------ push & prefs

    suspend fun registerPushToken(
        deviceId: String,
        provider: String,
        token: String,
        appId: String,
        locale: String
    ) {
        rest.rpc(
            "mm_register_push_token",
            buildJsonObject {
                put("p_device_id", deviceId)
                put("p_provider", provider)
                put("p_token", token)
                put("p_app_id", appId)
                put("p_locale", locale)
            }
        )
    }

    suspend fun revokePushToken(deviceId: String) {
        runCatching {
            rest.rpc("mm_revoke_push_token", buildJsonObject { put("p_device_id", deviceId) })
        }
    }

    suspend fun syncNotificationPrefs(
        userId: String,
        enabled: Boolean,
        directEnabled: Boolean,
        groupEnabled: Boolean,
        channelEnabled: Boolean,
        callsEnabled: Boolean
    ) {
        rest.insert(
            "mm_notification_prefs",
            listOf(
                buildJsonObject {
                    put("user_id", userId)
                    put("enabled", enabled)
                    put("direct_enabled", directEnabled)
                    put("group_enabled", groupEnabled)
                    put("channel_enabled", channelEnabled)
                    put("calls_enabled", callsEnabled)
                }
            ),
            onConflict = "user_id"
        )
    }

    /**
     * Triggers a zero-plaintext remote push wakeup via the `mm-push-dispatch` Edge Function.
     * Only opaque server UUIDs are transmitted — never message bodies, previews, or keys.
     */
    suspend fun dispatchPushWakeup(
        eventKind: String,
        conversationId: String,
        messageId: String? = null,
        callId: String? = null
    ) {
        runCatching {
            rest.invokeFunction(
                "mm-push-dispatch",
                buildJsonObject {
                    put("eventKind", eventKind)
                    put("conversationId", conversationId)
                    messageId?.let { put("messageId", it) }
                    callId?.let { put("callId", it) }
                }
            )
        }
    }

    // ----------------------------------------------------------------- conversations & groups

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

    suspend fun addGroupMembers(conversationId: String, memberUserIds: List<String>): Int {
        if (memberUserIds.isEmpty()) return 0
        val result = rest.rpc(
            "mm_add_group_members",
            buildJsonObject {
                put("p_conversation", conversationId)
                put("p_members", buildJsonArray { memberUserIds.forEach { add(JsonPrimitive(it)) } })
            }
        )
        return (result as? JsonPrimitive)?.intOrNull ?: 0
    }

    suspend fun removeGroupMember(conversationId: String, memberUserId: String): Boolean {
        val result = rest.rpc(
            "mm_remove_group_member",
            buildJsonObject {
                put("p_conversation", conversationId)
                put("p_member", memberUserId)
            }
        )
        return (result as? JsonPrimitive)?.booleanOrNull ?: false
    }

    suspend fun upgradeGroupEncryption(conversationId: String): Pair<Int, String>? {
        val result = rest.rpc(
            "mm_upgrade_group_encryption",
            buildJsonObject { put("p_conversation", conversationId) }
        )
        val obj = result as? JsonObject ?: return null
        val epoch = (obj["epoch"] as? JsonPrimitive)?.intOrNull ?: return null
        val distId = obj.str("distributionId") ?: return null
        return epoch to distId
    }

    suspend fun setConversationMute(conversationId: String, myUserId: String, muted: Boolean, mutedUntilMs: Long?) {
        rest.patch(
            "mm_conversation_members",
            listOf(
                "conversation_id" to "eq.$conversationId",
                "user_id" to "eq.$myUserId"
            ),
            buildJsonObject {
                put("notification_level", if (muted && mutedUntilMs == null) "none" else "all")
                if (mutedUntilMs != null) {
                    put("muted_until", Instant.ofEpochMilli(mutedUntilMs).toString())
                } else {
                    put("muted_until", kotlinx.serialization.json.JsonNull)
                }
            }
        )
    }

    suspend fun conversationById(conversationId: String): RemoteConversation? {
        val rows = rest.select(
            "mm_conversations",
            listOf(
                "select" to CONVERSATION_FIELDS,
                "id" to "eq.$conversationId",
                "deleted_at" to "is.null",
                "limit" to "1"
            )
        )
        val obj = rows.firstOrNull() as? JsonObject ?: return null
        val roles = membersOf(conversationId)
        return RemoteConversation(
            id = conversationId,
            type = obj.str("type") ?: "direct",
            title = obj.str("title").orEmpty(),
            ownerId = obj.str("owner_id").orEmpty(),
            memberUserIds = roles.keys.toList(),
            memberRoles = roles,
            lastMessageAtMs = obj.str("last_message_at")?.let(::isoToMillis),
            encryptionVersion = (obj["encryption_version"] as? JsonPrimitive)?.intOrNull ?: 1,
            senderKeyEpoch = (obj["sender_key_epoch"] as? JsonPrimitive)?.intOrNull ?: 1,
            distributionId = obj.str("distribution_id")
        )
    }

    suspend fun listConversations(myUserId: String): List<RemoteConversation> {
        val conversations = rest.select(
            "mm_conversations",
            listOf(
                "select" to CONVERSATION_FIELDS,
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
                lastMessageAtMs = obj.str("last_message_at")?.let { isoToMillis(it) },
                encryptionVersion = (obj["encryption_version"] as? JsonPrimitive)?.intOrNull ?: 1,
                senderKeyEpoch = (obj["sender_key_epoch"] as? JsonPrimitive)?.intOrNull ?: 1,
                distributionId = obj.str("distribution_id")
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

    // ---------------------------------------------------------------------- messages & sender keys

    suspend fun uploadMessage(
        senderUserId: String,
        senderDeviceId: String,
        conversationId: String,
        clientMsgId: String,
        kind: String,
        meta: JsonObject,
        senderDeviceRowId: String? = null,
        attachmentId: String? = null,
        groupCiphertext: String? = null,
        groupCipherEpoch: Int? = null,
        groupDistributionId: String? = null
    ): RemoteMessage {
        val rows = rest.insert(
            "mm_messages",
            listOf(
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("sender_id", senderUserId)
                    put("sender_device_id", senderDeviceId)
                    senderDeviceRowId?.let { put("sender_device_row_id", it) }
                    put("client_msg_id", clientMsgId)
                    put("kind", kind)
                    put("meta", meta)
                    attachmentId?.let { put("attachment_id", it) }
                    groupCiphertext?.let { put("group_ciphertext", it) }
                    groupCipherEpoch?.let { put("group_cipher_epoch", it) }
                    groupDistributionId?.let { put("group_distribution_id", it) }
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
        // First write wins. Envelopes are immutable on the server (there is no UPDATE grant), so a
        // retried fan-out must be a no-op rather than a merge that the database refuses.
        rest.insert(
            "mm_message_envelopes",
            rows,
            onConflict = "message_id,recipient_device",
            ignoreDuplicates = true
        )
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

    suspend fun putSenderKeyDistributions(entries: List<OutgoingSkdm>) {
        if (entries.isEmpty()) return
        val rows = entries.map { e ->
            buildJsonObject {
                put("conversation_id", e.conversationId)
                put("epoch", e.epoch)
                put("distribution_id", e.distributionId)
                put("sender_user_id", e.senderUserId)
                put("sender_device", e.senderDeviceRowId)
                put("recipient_user_id", e.recipientUserId)
                put("recipient_device", e.recipientDeviceRowId)
                put("cipher_type", e.cipherType)
                put("ciphertext", e.ciphertextBase64)
            }
        }
        rest.insert(
            "mm_sender_key_distributions",
            rows,
            onConflict = "conversation_id,epoch,sender_device,recipient_device",
            // An undelivered distribution for the same epoch still opens the chain (the receiver
            // ratchets forward), and the row is immutable server-side, so retries are no-ops.
            ignoreDuplicates = true
        )
    }

    suspend fun senderKeyDistributionsFor(
        conversationId: String,
        epoch: Int,
        recipientUserId: String,
        recipientDeviceRowId: String?
    ): List<RemoteSkdm> {
        val params = buildList {
            add("select" to "id,conversation_id,epoch,distribution_id,sender_user_id,sender_device,cipher_type,ciphertext")
            add("conversation_id" to "eq.$conversationId")
            add("epoch" to "eq.$epoch")
            add("recipient_user_id" to "eq.$recipientUserId")
            if (recipientDeviceRowId != null) add("recipient_device" to "eq.$recipientDeviceRowId")
            add("order" to "id.asc")
        }
        val rows = rest.select("mm_sender_key_distributions", params)
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            RemoteSkdm(
                id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                conversationId = obj.str("conversation_id") ?: return@mapNotNull null,
                epoch = (obj["epoch"] as? JsonPrimitive)?.intOrNull ?: epoch,
                distributionId = obj.str("distribution_id") ?: return@mapNotNull null,
                senderUserId = obj.str("sender_user_id") ?: return@mapNotNull null,
                senderDeviceRowId = obj.str("sender_device") ?: return@mapNotNull null,
                cipherType = (obj["cipher_type"] as? JsonPrimitive)?.intOrNull ?: 3,
                ciphertextBase64 = obj.str("ciphertext") ?: return@mapNotNull null
            )
        }
    }

    suspend fun deleteSenderKeyDistribution(id: Long) {
        runCatching {
            rest.delete("mm_sender_key_distributions", listOf("id" to "eq.$id"))
        }
    }

    // --------------------------------------------------------- E2EE attachments & storage

    suspend fun insertAttachmentRecord(
        attachmentId: String,
        conversationId: String,
        uploaderId: String,
        objectPath: String,
        cipherSizeBytes: Long,
        cipherSha256: String
    ): String {
        val rows = rest.insert(
            "mm_attachments",
            listOf(
                buildJsonObject {
                    put("id", attachmentId)
                    put("conversation_id", conversationId)
                    put("uploader_id", uploaderId)
                    put("bucket", MEDIA_BUCKET)
                    put("object_path", objectPath)
                    put("file_name", "encrypted.bin")
                    put("mime_type", "application/octet-stream")
                    put("size_bytes", cipherSizeBytes)
                    put("sha256", cipherSha256)
                }
            ),
            onConflict = "id",
            // Attachment rows are immutable; a resumed upload re-registers the same id.
            ignoreDuplicates = true
        )
        return (rows.firstOrNull() as? JsonObject)?.str("id") ?: attachmentId
    }

    suspend fun attachmentById(attachmentId: String): RemoteAttachment? {
        val rows = rest.select(
            "mm_attachments",
            listOf(
                "select" to "id,conversation_id,bucket,object_path,size_bytes,sha256",
                "id" to "eq.$attachmentId",
                "limit" to "1"
            )
        )
        val obj = rows.firstOrNull() as? JsonObject ?: return null
        return RemoteAttachment(
            id = obj.str("id") ?: return null,
            conversationId = obj.str("conversation_id") ?: return null,
            bucket = obj.str("bucket") ?: MEDIA_BUCKET,
            objectPath = obj.str("object_path") ?: return null,
            sizeBytes = (obj["size_bytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256 = obj.str("sha256").orEmpty()
        )
    }

    suspend fun uploadEncryptedBlob(
        transferId: String,
        objectPath: String,
        encryptedFile: File,
        resumeTusUrl: String? = null,
        onTusUrlCreated: ((String) -> Unit)? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        rest.uploadEncryptedMedia(
            transferId = transferId,
            bucket = MEDIA_BUCKET,
            objectPath = objectPath,
            encryptedFile = encryptedFile,
            resumeTusUrl = resumeTusUrl,
            onTusUrlCreated = onTusUrlCreated,
            onProgress = onProgress
        )
    }

    suspend fun createSignedMediaUrl(objectPath: String, expiresInSeconds: Int = 300): HttpUrl =
        rest.createSignedStorageUrl(MEDIA_BUCKET, objectPath, expiresInSeconds)

    suspend fun downloadEncryptedBlob(
        transferId: String,
        signedUrl: HttpUrl,
        destPartFile: File,
        expectedTotalBytes: Long? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File = rest.downloadEncryptedMedia(
        transferId = transferId,
        signedUrl = signedUrl,
        destPartFile = destPartFile,
        expectedTotalBytes = expectedTotalBytes,
        onProgress = onProgress
    )

    suspend fun deleteEncryptedBlob(objectPath: String, attachmentId: String?) {
        runCatching { rest.deleteStorageObject(MEDIA_BUCKET, objectPath) }
        if (!attachmentId.isNullOrBlank()) {
            runCatching { rest.delete("mm_attachments", listOf("id" to "eq.$attachmentId")) }
        }
    }

    fun cancelTransfer(transferId: String) = rest.cancelTransfer(transferId)

    fun clearCancelledTransfer(transferId: String) = rest.clearCancelled(transferId)

    fun isTransferCancelled(transferId: String): Boolean = rest.isTransferCancelled(transferId)

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

    // -------------------------------------------------------------------- typing & reactions

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

    // --------------------------------------------------------- 1:1 WebRTC calling

    suspend fun fetchTurnCredentials(): TurnCredentialsResponse {
        val obj = runCatching { rest.invokeFunction("mm-turn-credentials", buildJsonObject { }) }.getOrNull()
        if (obj == null) {
            return TurnCredentialsResponse(
                iceServers = listOf(TurnIceServerConfig(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"))),
                turnConfigured = false,
                provider = "stun_only"
            )
        }
        val serversArr = obj["iceServers"] as? JsonArray ?: JsonArray(emptyList())
        val servers = serversArr.mapNotNull { elem ->
            val s = elem as? JsonObject ?: return@mapNotNull null
            val urlsElem = s["urls"]
            val urls = when (urlsElem) {
                is JsonArray -> urlsElem.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(urlsElem.contentOrNull)
                else -> emptyList()
            }
            if (urls.isEmpty()) return@mapNotNull null
            TurnIceServerConfig(
                urls = urls,
                username = s.str("username"),
                credential = s.str("credential")
            )
        }
        return TurnCredentialsResponse(
            iceServers = servers.ifEmpty {
                listOf(TurnIceServerConfig(listOf("stun:stun.cloudflare.com:3478")))
            },
            turnConfigured = (obj["turnConfigured"] as? JsonPrimitive)?.booleanOrNull ?: false,
            provider = obj.str("provider") ?: "stun_only"
        )
    }

    suspend fun createCall(conversationId: String, kind: String): String {
        val result = rest.rpc(
            "mm_create_call",
            buildJsonObject {
                put("p_conversation", conversationId)
                put("p_kind", kind)
            }
        )
        return result?.jsonPrimitive?.contentOrNull
            ?: throw SupabaseException(0, "call", "Could not initiate call")
    }

    suspend fun setCallState(callId: String, state: String, endReason: String? = null) {
        rest.rpc(
            "mm_set_call_state",
            buildJsonObject {
                put("p_call", callId)
                put("p_state", state)
                endReason?.let { put("p_end_reason", it) }
            }
        )
    }

    suspend fun sendCallSignal(
        callId: String,
        toUserId: String,
        kind: String,
        payload: JsonObject,
        clientSignalId: String
    ): Long {
        val result = rest.rpc(
            "mm_send_call_signal",
            buildJsonObject {
                put("p_call", callId)
                put("p_to_user", toUserId)
                put("p_kind", kind)
                put("p_payload", payload)
                put("p_client_signal_id", clientSignalId)
            }
        )
        return (result as? JsonPrimitive)?.longOrNull ?: 0L
    }

    suspend fun callById(callId: String): RemoteCall? {
        val rows = rest.select(
            "mm_calls",
            listOf(
                "select" to "id,conversation_id,initiator_id,kind,state,started_at,answered_at,ended_at,end_reason",
                "id" to "eq.$callId",
                "limit" to "1"
            )
        )
        return (rows.firstOrNull() as? JsonObject)?.let(::callOf)
    }

    suspend fun activeIncomingCalls(myUserId: String): List<RemoteCall> {
        val rows = rest.select(
            "mm_calls",
            listOf(
                "select" to "id,conversation_id,initiator_id,kind,state,started_at,answered_at,ended_at,end_reason",
                "state" to "eq.ringing",
                "initiator_id" to "neq.$myUserId",
                "order" to "started_at.desc",
                "limit" to "5"
            )
        )
        val nowMs = System.currentTimeMillis()
        return rows.mapNotNull { (it as? JsonObject)?.let(::callOf) }
            .filter { nowMs - it.startedAtMs < 65_000L }
    }

    suspend fun callSignalsSince(callId: String, myUserId: String, afterId: Long): List<RemoteCallSignal> {
        val rows = rest.select(
            "mm_call_signals",
            listOf(
                "select" to "id,call_id,from_user,to_user,kind,payload,client_signal_id",
                "call_id" to "eq.$callId",
                "to_user" to "eq.$myUserId",
                "id" to "gt.$afterId",
                "order" to "id.asc",
                "limit" to "200"
            )
        )
        return rows.mapNotNull { (it as? JsonObject)?.let(::callSignalOf) }
    }

    // ------------------------------------------------------------- discovery/channels/blocks

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

    suspend fun blockUser(myUserId: String, targetUserId: String) {
        rest.insert(
            "mm_blocks",
            listOf(
                buildJsonObject {
                    put("blocker_id", myUserId)
                    put("blocked_id", targetUserId)
                }
            ),
            onConflict = "blocker_id,blocked_id",
            ignoreDuplicates = true
        )
    }

    suspend fun unblockUser(myUserId: String, targetUserId: String) {
        rest.delete(
            "mm_blocks",
            listOf(
                "blocker_id" to "eq.$myUserId",
                "blocked_id" to "eq.$targetUserId"
            )
        )
    }

    suspend fun blockedUserIds(myUserId: String): Set<String> {
        val rows = rest.select(
            "mm_blocks",
            listOf(
                "select" to "blocked_id",
                "blocker_id" to "eq.$myUserId"
            )
        )
        return rows.mapNotNull { (it as? JsonObject)?.str("blocked_id") }.toSet()
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
        val edgeSucceeded = runCatching {
            rest.invokeFunction("mm-account-delete", buildJsonObject { put("confirm", "DELETE") })
        }.isSuccess
        if (!edgeSucceeded) {
            rest.rpc("mm_delete_me", buildJsonObject { })
        }
    }

    /**
     * Flags a message as edited. The new text never goes through this call: it is published as an
     * end-to-end encrypted "text-edit" message that references [messageId].
     */
    suspend fun markMessageEdited(
        messageId: String,
        conversationId: String,
        editedAtMs: Long
    ) {
        rest.patch(
            "mm_messages",
            listOf("id" to "eq.$messageId", "conversation_id" to "eq.$conversationId"),
            // Only the marker is writable: `kind` and every other column of a stored message are
            // immutable for clients. The new text travels as an encrypted "text-edit" message.
            buildJsonObject { put("edited_at", Instant.ofEpochMilli(editedAtMs).toString()) }
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
            senderDeviceRowId = obj.str("sender_device_row_id"),
            clientMsgId = obj.str("client_msg_id").orEmpty(),
            kind = obj.str("kind") ?: "text",
            sentAtMs = (obj["sent_at_ms"] as? JsonPrimitive)?.longOrNull
                ?: obj.str("sent_at")?.let { isoToMillis(it) }
                ?: System.currentTimeMillis(),
            deleted = !obj.str("deleted_at").isNullOrBlank(),
            meta = meta,
            attachmentId = obj.str("attachment_id"),
            groupCiphertext = obj.str("group_ciphertext"),
            groupCipherEpoch = (obj["group_cipher_epoch"] as? JsonPrimitive)?.intOrNull,
            groupDistributionId = obj.str("group_distribution_id")
        )
    }

    internal fun callOf(obj: JsonObject): RemoteCall? {
        val id = obj.str("id") ?: return null
        val conversationId = obj.str("conversation_id") ?: return null
        return RemoteCall(
            id = id,
            conversationId = conversationId,
            initiatorId = obj.str("initiator_id").orEmpty(),
            kind = obj.str("kind") ?: "voice",
            state = obj.str("state") ?: "ringing",
            startedAtMs = obj.str("started_at")?.let(::isoToMillis) ?: System.currentTimeMillis(),
            answeredAtMs = obj.str("answered_at")?.let(::isoToMillis),
            endedAtMs = obj.str("ended_at")?.let(::isoToMillis),
            endReason = obj.str("end_reason")
        )
    }

    internal fun callSignalOf(obj: JsonObject): RemoteCallSignal? {
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val callId = obj.str("call_id") ?: return null
        return RemoteCallSignal(
            id = id,
            callId = callId,
            fromUserId = obj.str("from_user").orEmpty(),
            toUserId = obj.str("to_user").orEmpty(),
            kind = obj.str("kind").orEmpty(),
            payload = (obj["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
            clientSignalId = obj.str("client_signal_id")
        )
    }

    private fun BundleKey.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("public", public)
        signature?.let { put("signature", it) }
    }

    companion object {
        const val MEDIA_BUCKET = "mm-media"
        const val CONVERSATION_FIELDS =
            "id,type,title,owner_id,last_message_at,encryption_version,sender_key_epoch,distribution_id"
        const val MESSAGE_FIELDS =
            "id,conversation_id,sender_id,sender_device_id,sender_device_row_id,client_msg_id," +
                "kind,sent_at,sent_at_ms,deleted_at,meta,attachment_id,group_ciphertext," +
                "group_cipher_epoch,group_distribution_id"

        fun isoToMillis(value: String): Long? =
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
