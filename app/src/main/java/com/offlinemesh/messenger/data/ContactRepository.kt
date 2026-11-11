package com.offlinemesh.messenger.data

import android.util.Base64
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class QrPayload(
    val v: Int = 1,
    val deviceId: String,
    val name: String,
    val identityKey: String
)

class ContactRepository(
    private val db: AppDatabase,
    private val crypto: CryptoManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeContacts(): Flow<List<ContactEntity>> = db.contactDao().observeAll()

    suspend fun getContact(deviceId: String): ContactEntity? = db.contactDao().getByDeviceId(deviceId)

    suspend fun addContact(deviceId: String, displayName: String, identityKeyBase64: String) {
        val existing = db.contactDao().getByDeviceId(deviceId)
        if (existing != null) {
            db.contactDao().upsert(existing.copy(displayName = displayName, identityKeyBase64 = identityKeyBase64))
        } else {
            db.contactDao().upsert(
                ContactEntity(
                    deviceId = deviceId,
                    displayName = displayName,
                    identityKeyBase64 = identityKeyBase64,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
        if (identityKeyBase64.isNotEmpty()) {
            try {
                crypto.processIdentity(deviceId, Base64.decode(identityKeyBase64, Base64.NO_WRAP))
            } catch (e: Exception) {
            }
        }
    }

    suspend fun updateLastSeen(deviceId: String) {
        db.contactDao().updateLastSeen(deviceId, System.currentTimeMillis())
    }

    fun buildQrPayload(deviceId: String, displayName: String): String {
        val identity = Base64.encodeToString(crypto.getIdentityPublicKeyBytes(), Base64.NO_WRAP)
        return json.encodeToString(QrPayload(deviceId = deviceId, name = displayName, identityKey = identity))
    }

    fun parseQrPayload(text: String): QrPayload? {
        return try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            null
        }
    }
}
