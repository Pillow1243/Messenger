package com.offlinemesh.messenger.data

import android.util.Base64
import com.offlinemesh.messenger.core.crypto.CryptoKeys
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ContactEntity
import org.signal.libsignal.protocol.SignalProtocolAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Contact book + identity pinning.
 *
 * Contacts are created either by scanning/copying a pairing code (which pins the identity key
 * out-of-band, i.e. verified) or automatically when a peer shows up in the mesh (trust on first
 * use, unverified). If a known contact later presents a different identity key we do not silently
 * replace it: the row is flagged so the UI can warn the user.
 */
class ContactRepository(
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val settings: SettingsRepository
) {
    private val dao = db.contactDao()

    fun observeContacts(): Flow<List<ContactEntity>> = dao.observeAll()

    suspend fun get(deviceId: String): ContactEntity? = dao.getByDeviceId(deviceId)

    suspend fun all(): List<ContactEntity> = dao.getAll()

    suspend fun displayName(deviceId: String): String =
        dao.getByDeviceId(deviceId)?.displayName ?: deviceId.take(8)

    /** The code shown as a QR image / shareable text so someone else can add us. */
    suspend fun myPairingCode(): String = withContext(Dispatchers.IO) {
        val deviceId = settings.deviceId()
        val name = settings.snapshot().displayName.ifBlank { "Mesh device" }
        val identity = Base64.encodeToString(crypto.myIdentityKey().serialize(), Base64.NO_WRAP)
        PairingCodec.encode(
            PairingPayload(
                deviceId = deviceId,
                name = name,
                identityKey = identity,
                registrationId = crypto.registrationId()
            )
        )
    }

    /**
     * Adds a contact from a scanned/pasted code. The identity key in the code is pinned and the
     * contact is marked verified, because the code travelled through a trusted channel.
     */
    suspend fun addFromPairing(payload: PairingPayload): ContactEntity? {
        val identityKeyBytes = runCatching {
            Base64.decode(payload.identityKey, Base64.NO_WRAP)
        }.getOrNull() ?: return null
        if (identityKeyBytes.size != 33) return null
        val existing = dao.getByDeviceId(payload.deviceId)
        val entity = ContactEntity(
            deviceId = payload.deviceId,
            displayName = payload.name.ifBlank { payload.deviceId.take(8) },
            identityKeyBase64 = payload.identityKey,
            registrationId = payload.registrationId,
            verified = true,
            identityChanged = existing != null && existing.identityKeyBase64 != payload.identityKey,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            lastSeenAt = existing?.lastSeenAt ?: 0L
        )
        dao.upsert(entity)
        // Pinning a new identity invalidates any session built on the previous one.
        if (existing != null && existing.identityKeyBase64 != payload.identityKey) {
            crypto.standaloneForget(payload.deviceId)
        }
        return entity
    }

    /** Called when a peer's HELLO arrives; remembers the name and the identity it presents. */
    suspend fun rememberPeer(deviceId: String, displayName: String) = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) return@withContext
        val identity = crypto.store.getIdentity(
            SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID)
        )
        val identityBase64 = identity?.let {
            Base64.encodeToString(it.serialize(), Base64.NO_WRAP)
        } ?: ""
        val existing = dao.getByDeviceId(deviceId)
        val changed = existing != null &&
            existing.identityKeyBase64.isNotEmpty() &&
            identityBase64.isNotEmpty() &&
            existing.identityKeyBase64 != identityBase64

        dao.upsert(
            ContactEntity(
                deviceId = deviceId,
                displayName = displayName.ifBlank {
                    existing?.displayName ?: deviceId.take(8)
                },
                identityKeyBase64 = identityBase64.ifEmpty { existing?.identityKeyBase64 ?: "" },
                registrationId = existing?.registrationId ?: 0,
                verified = if (changed) false else (existing?.verified ?: false),
                identityChanged = changed || (existing?.identityChanged ?: false),
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setVerified(deviceId: String, verified: Boolean) {
        dao.setVerified(deviceId, verified)
        if (verified) dao.setIdentityChanged(deviceId, false)
    }

    /** Forgets the stored identity + session so a new identity key can be trusted. */
    suspend fun resetTrust(deviceId: String) = withContext(Dispatchers.IO) {
        crypto.standaloneForget(deviceId)
        dao.setIdentityChanged(deviceId, false)
        Unit
    }

    suspend fun safetyNumber(deviceId: String): String? =
        crypto.safetyNumber(deviceId, settings.deviceId())

    suspend fun delete(deviceId: String) = withContext(Dispatchers.IO) {
        crypto.standaloneForget(deviceId)
        dao.delete(deviceId)
    }
}
