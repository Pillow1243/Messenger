package com.offlinemesh.messenger.core.crypto

import android.util.Log
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.KeyValueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

/** Keys used inside the encrypted key/value table. */
object CryptoKeys {
    const val IDENTITY_KEY_PAIR = "signal_identity_key_pair"
    const val REGISTRATION_ID = "signal_registration_id"
    const val SIGNED_PRE_KEY_ID = "signal_signed_pre_key_id"
    const val KYBER_PRE_KEY_ID = "signal_kyber_pre_key_id"
    const val NEXT_PRE_KEY_ID = "signal_next_pre_key_id"
    const val LAST_HANDED_PRE_KEY_ID = "signal_last_handed_pre_key_id"
    const val LAST_PREKEY_ROTATED_AT = "signal_last_prekey_rotated_at"

    /** One device per installation: the mesh address is (deviceId, 1). */
    const val DEVICE_ID = 1
    const val TARGET_PRE_KEY_COUNT = 20
    const val MIN_PRE_KEY_COUNT = 6
    const val PREKEY_ROTATION_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    const val SAFETY_NUMBER_ITERATIONS = 5200
    const val SAFETY_NUMBER_VERSION = 2
}

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** No session has been established yet - the peer has not been in range. */
class NoSessionYetException(val deviceId: String) : Exception("No session with $deviceId")

/** The ciphertext was already decrypted once (a retransmission reached us twice). */
class DuplicateCiphertextException(val deviceId: String, cause: Throwable?) :
    Exception("Duplicate ciphertext from $deviceId", cause)

/** The peer presented a different identity key than the one we stored. */
class IdentityChangedException(val deviceId: String) :
    Exception("Identity key for $deviceId changed")

/**
 * End-to-end encryption on top of the official Signal Protocol implementation
 * (PQXDH initial agreement + Double Ratchet), one session per peer device.
 *
 * Every method blocks on SQLCipher I/O, so the public suspend API hops to Dispatchers.IO.
 */
class CryptoManager(
    private val db: AppDatabase,
    val store: SignalStore
) {
    private val kv = db.keyValueDao()
    private val dao = db.signalDao()
    val senderKeys = SenderKeyGroupEngine(store)

    // ------------------------------------------------------------------ setup

    suspend fun ensureIdentity() = withContext(Dispatchers.IO) { ensureIdentityBlocking() }

    fun ensureIdentityBlocking() {
        if (kv.get(CryptoKeys.IDENTITY_KEY_PAIR) == null) {
            val identity = IdentityKeyPair.generate()
            kv.put(KeyValueEntity(CryptoKeys.IDENTITY_KEY_PAIR, Base64Codec.encode(identity.serialize())))
            kv.put(
                KeyValueEntity(
                    CryptoKeys.REGISTRATION_ID,
                    KeyHelper.generateRegistrationId(false).toString()
                )
            )
            kv.put(KeyValueEntity(CryptoKeys.NEXT_PRE_KEY_ID, "1"))
            createSignedPreKey(identity)
            createKyberPreKey(identity)
            generatePreKeys(CryptoKeys.TARGET_PRE_KEY_COUNT)
            kv.put(KeyValueEntity(CryptoKeys.LAST_PREKEY_ROTATED_AT, System.currentTimeMillis().toString()))
            Log.i(TAG, "Generated fresh Signal identity")
        } else {
            if (dao.preKeyCount() < CryptoKeys.MIN_PRE_KEY_COUNT) {
                generatePreKeys(CryptoKeys.TARGET_PRE_KEY_COUNT - dao.preKeyCount())
            }
            if (dao.allKyberPreKeys().isEmpty()) {
                createKyberPreKey(store.getIdentityKeyPair())
            }
            if (dao.allSignedPreKeys().isEmpty()) {
                createSignedPreKey(store.getIdentityKeyPair())
            }
        }
    }

    private fun createSignedPreKey(identity: IdentityKeyPair): Int {
        val id = (kv.get(CryptoKeys.SIGNED_PRE_KEY_ID)?.toIntOrNull() ?: 0) + 1
        val pair = ECKeyPair.generate()
        // The signature proves the signed pre-key belongs to this identity.
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        store.storeSignedPreKey(
            id,
            SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
        )
        kv.put(KeyValueEntity(CryptoKeys.SIGNED_PRE_KEY_ID, id.toString()))
        return id
    }

    private fun createKyberPreKey(identity: IdentityKeyPair): Int {
        val id = (kv.get(CryptoKeys.KYBER_PRE_KEY_ID)?.toIntOrNull() ?: 0) + 1
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        store.storeKyberPreKey(
            id,
            KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
        )
        kv.put(KeyValueEntity(CryptoKeys.KYBER_PRE_KEY_ID, id.toString()))
        return id
    }

    private fun generatePreKeys(count: Int) {
        var next = kv.get(CryptoKeys.NEXT_PRE_KEY_ID)?.toIntOrNull() ?: 1
        repeat(count.coerceAtLeast(0)) {
            val id = next++
            store.storePreKey(id, PreKeyRecord(id, ECKeyPair.generate()))
        }
        kv.put(KeyValueEntity(CryptoKeys.NEXT_PRE_KEY_ID, next.toString()))
    }

    // ---------------------------------------------------------------- bundles

    /**
     * Builds this device's pre-key bundle. A fresh one-time pre-key is handed out per call and
     * rotates through the pool; when the pool is empty we fall back to "no one-time pre-key"
     * (0xFF.. sentinel) which is still a valid PQXDH bundle.
     */
    suspend fun currentBundle(): PreKeyBundle = withContext(Dispatchers.IO) { buildBundle() }

    /**
     * The bundle published to the online backend: identity key, signed pre-key and Kyber pre-key.
     * One-time pre-keys are uploaded separately and handed out by the server one at a time, so
     * building this must not consume a pre-key locally.
     */
    suspend fun publishedBundle(): PreKeyBundleJson = withContext(Dispatchers.IO) {
        PreKeyBundleJson.fromBundle(buildBundle(consumePreKey = false), includeOneTimePreKey = false)
    }

    /** Public halves of the local one-time pre-key pool, for upload. */
    suspend fun preKeyPublics(): List<BundleKey> = withContext(Dispatchers.IO) {
        dao.preKeyIds().sorted().mapNotNull { id ->
            runCatching { store.loadPreKey(id) }.getOrNull()?.let { record ->
                BundleKey(id = id, public = Base64Codec.encode(record.keyPair.publicKey.serialize()))
            }
        }
    }

    suspend fun oneTimePreKeyCount(): Int = withContext(Dispatchers.IO) { dao.preKeyCount() }

    /** Replenishes the local one-time pre-key pool up to [CryptoKeys.TARGET_PRE_KEY_COUNT]. */
    suspend fun replenishPreKeysIfLow(): Boolean = withContext(Dispatchers.IO) {
        val current = dao.preKeyCount()
        if (current >= CryptoKeys.MIN_PRE_KEY_COUNT) return@withContext false
        generatePreKeys(CryptoKeys.TARGET_PRE_KEY_COUNT - current)
        true
    }

    /**
     * Rotates the signed pre-key and Kyber-1024 pre-key when older than [maxAgeMs] (or [force]).
     * Previous signed/Kyber pre-keys remain in the local store for up to 30 days so asynchronous
     * in-flight envelopes encrypted against the prior bundle still decrypt cleanly.
     */
    suspend fun rotateSignedAndKyberPreKeysIfDue(
        maxAgeMs: Long = CryptoKeys.PREKEY_ROTATION_INTERVAL_MS,
        force: Boolean = false
    ): Pair<BundleKey, BundleKey>? = withContext(Dispatchers.IO) {
        ensureIdentityBlocking()
        val now = System.currentTimeMillis()
        val last = kv.get(CryptoKeys.LAST_PREKEY_ROTATED_AT)?.toLongOrNull() ?: 0L
        if (!force && last > 0L && now - last < maxAgeMs) return@withContext null

        val identity = store.getIdentityKeyPair()
        val signedId = createSignedPreKey(identity)
        val kyberId = createKyberPreKey(identity)
        kv.put(KeyValueEntity(CryptoKeys.LAST_PREKEY_ROTATED_AT, now.toString()))

        val signed = store.loadSignedPreKey(signedId)
        val kyber = store.loadKyberPreKey(kyberId)

        // Retain the 3 newest signed pre-keys and prune older ones (> 30 days).
        val retentionCutoff = now - 30L * 24 * 60 * 60 * 1000
        store.loadSignedPreKeys()
            .sortedByDescending { it.id }
            .drop(3)
            .filter { it.timestamp < retentionCutoff }
            .forEach { store.removeSignedPreKey(it.id) }

        Pair(
            BundleKey(
                id = signedId,
                public = Base64Codec.encode(signed.keyPair.publicKey.serialize()),
                signature = Base64Codec.encode(signed.signature)
            ),
            BundleKey(
                id = kyberId,
                public = Base64Codec.encode(kyber.keyPair.publicKey.serialize()),
                signature = Base64Codec.encode(kyber.signature)
            )
        )
    }

    /** Establishes a session from a bundle fetched over HTTPS instead of a mesh HELLO. */
    suspend fun processBundleJson(deviceId: String, bundle: PreKeyBundleJson) =
        withContext(Dispatchers.IO) {
            val address = SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID)
            try {
                SessionBuilder(store, address).process(bundle.toPreKeyBundle())
            } catch (e: UntrustedIdentityException) {
                throw IdentityChangedException(deviceId)
            } catch (e: Exception) {
                throw CryptoException("Could not establish a session with $deviceId", e)
            }
        }

    private fun buildBundle(consumePreKey: Boolean = true): PreKeyBundle {
        val identity = store.getIdentityKeyPair()
        val signedId = kv.get(CryptoKeys.SIGNED_PRE_KEY_ID)?.toIntOrNull()
            ?: createSignedPreKey(identity)
        val signed = store.loadSignedPreKey(signedId)
        val kyberId = kv.get(CryptoKeys.KYBER_PRE_KEY_ID)?.toIntOrNull()
            ?: createKyberPreKey(identity)
        val kyber = store.loadKyberPreKey(kyberId)
        val preKey = if (consumePreKey) pickPreKey() else null

        return PreKeyBundle(
            store.getLocalRegistrationId(),
            CryptoKeys.DEVICE_ID,
            preKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            preKey?.keyPair?.publicKey,
            signedId,
            signed.keyPair.publicKey,
            signed.signature,
            identity.publicKey,
            kyberId,
            kyber.keyPair.publicKey,
            kyber.signature
        )
    }

    private fun pickPreKey(): PreKeyRecord? {
        val ids = dao.preKeyIds().sorted()
        if (ids.isEmpty()) return null
        val last = kv.get(CryptoKeys.LAST_HANDED_PRE_KEY_ID)?.toIntOrNull() ?: -1
        val chosen = ids.firstOrNull { it > last } ?: ids.first()
        kv.put(KeyValueEntity(CryptoKeys.LAST_HANDED_PRE_KEY_ID, chosen.toString()))
        return runCatching { store.loadPreKey(chosen) }.getOrNull()
    }

    /** Called when a peer's HELLO arrived with their bundle. Establishes/refreshes the session. */
    suspend fun processPeerBundle(deviceId: String, encodedBundle: ByteArray) =
        withContext(Dispatchers.IO) {
            val bundle = PreKeyBundleCodec.decode(encodedBundle)
            val address = SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID)
            try {
                SessionBuilder(store, address).process(bundle)
            } catch (e: UntrustedIdentityException) {
                throw IdentityChangedException(deviceId)
            } catch (e: Exception) {
                throw CryptoException("Could not establish a session with $deviceId", e)
            }
        }

    // ------------------------------------------------------------- encryption

    fun hasSession(deviceId: String): Boolean =
        store.containsSession(SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID))

    suspend fun encrypt(deviceId: String, plaintext: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            val address = SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID)
            if (!store.containsSession(address)) throw NoSessionYetException(deviceId)
            try {
                val message = SessionCipher(store, address).encrypt(plaintext)
                // Prepend the Signal message type so the receiver knows how to parse it.
                byteArrayOf(message.type.toByte()) + message.serialize()
            } catch (e: Exception) {
                throw CryptoException("Encryption failed for $deviceId", e)
            }
        }

    suspend fun decrypt(deviceId: String, envelope: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            require(envelope.size > 1) { "ciphertext too short" }
            val type = envelope[0].toInt()
            val payload = envelope.copyOfRange(1, envelope.size)
            val address = SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID)
            val cipher = SessionCipher(store, address)
            try {
                when (type) {
                    CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(payload))
                    CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(payload))
                    else -> throw CryptoException("Unknown ciphertext type $type")
                }
            } catch (e: DuplicateMessageException) {
                throw DuplicateCiphertextException(deviceId, e)
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException("Decryption failed for $deviceId", e)
            }
        }

    // --------------------------------------------------- group sender keys (v2)

    suspend fun createGroupDistribution(myDeviceId: String, distributionId: UUID): ByteArray =
        withContext(Dispatchers.IO) {
            senderKeys.createDistribution(
                SignalProtocolAddress(myDeviceId, CryptoKeys.DEVICE_ID),
                distributionId
            )
        }

    suspend fun encryptSkdmForPeer(serializedSkdm: ByteArray, peerDeviceId: String): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            senderKeys.encryptSkdmForPeer(
                serializedSkdm,
                SignalProtocolAddress(peerDeviceId, CryptoKeys.DEVICE_ID)
            )
        }

    suspend fun decryptAndProcessSkdmFromPeer(
        senderDeviceId: String,
        cipherType: Int,
        ciphertext: ByteArray
    ) = withContext(Dispatchers.IO) {
        senderKeys.decryptAndProcessSkdmFromPeer(
            SignalProtocolAddress(senderDeviceId, CryptoKeys.DEVICE_ID),
            cipherType,
            ciphertext
        )
    }

    suspend fun encryptGroup(
        myDeviceId: String,
        distributionId: UUID,
        plaintext: ByteArray
    ): ByteArray = withContext(Dispatchers.IO) {
        senderKeys.encryptGroupMessage(
            SignalProtocolAddress(myDeviceId, CryptoKeys.DEVICE_ID),
            distributionId,
            plaintext
        )
    }

    suspend fun decryptGroup(senderDeviceId: String, ciphertext: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            senderKeys.decryptGroupMessage(
                SignalProtocolAddress(senderDeviceId, CryptoKeys.DEVICE_ID),
                ciphertext
            )
        }

    // -------------------------------------------------------------- identities

    suspend fun myIdentityKey(): IdentityKey = withContext(Dispatchers.IO) {
        store.getIdentityKeyPair().publicKey
    }

    fun encodeIdentityKey(key: IdentityKey): ByteArray = key.serialize()

    suspend fun registrationId(): Int = withContext(Dispatchers.IO) {
        store.getLocalRegistrationId()
    }

    /** The 60-digit safety number shown to users for out-of-band verification. */
    suspend fun safetyNumber(deviceId: String, localDeviceId: String): String? =
        withContext(Dispatchers.IO) {
            val local = store.getIdentityKeyPair()
            val remote = store.getIdentity(
                SignalProtocolAddress(deviceId, CryptoKeys.DEVICE_ID)
            ) ?: return@withContext null
            val generator = NumericFingerprintGenerator(CryptoKeys.SAFETY_NUMBER_ITERATIONS)
            val fingerprint = generator.createFor(
                CryptoKeys.SAFETY_NUMBER_VERSION,
                localDeviceId.toByteArray(Charsets.UTF_8),
                local.publicKey,
                deviceId.toByteArray(Charsets.UTF_8),
                remote
            )
            fingerprint.displayableFingerprint.displayText
        }

    /** Computes a 60-digit safety number against an explicit Base64 peer device identity key. */
    suspend fun safetyNumberForIdentityKey(
        remoteDeviceId: String,
        remoteIdentityKeyBase64: String,
        localDeviceId: String
    ): String? = withContext(Dispatchers.IO) {
        val local = runCatching { store.getIdentityKeyPair() }.getOrNull() ?: return@withContext null
        val remote = runCatching { IdentityKey(Base64Codec.decode(remoteIdentityKeyBase64)) }
            .getOrNull() ?: return@withContext null
        val generator = NumericFingerprintGenerator(CryptoKeys.SAFETY_NUMBER_ITERATIONS)
        generator.createFor(
            CryptoKeys.SAFETY_NUMBER_VERSION,
            localDeviceId.toByteArray(Charsets.UTF_8),
            local.publicKey,
            remoteDeviceId.toByteArray(Charsets.UTF_8),
            remote
        ).displayableFingerprint.displayText
    }

    /** Drops the session and stored identity for a peer, so a new identity can be trusted. */
    suspend fun forgetIdentity(deviceId: String) = withContext(Dispatchers.IO) {
        standaloneForget(deviceId)
    }

    fun standaloneForget(deviceId: String) {
        store.forgetContact(deviceId, CryptoKeys.DEVICE_ID)
    }

    /** Erase every key on the device ("erase all data"). */
    suspend fun wipeAllKeys() = withContext(Dispatchers.IO) {
        dao.deleteAllSessions()
        dao.deleteAllIdentities()
        dao.deleteAllSenderKeys()
        db.groupSkdmDao().clear()
        kv.deleteAll()
        db.contactDao().deleteAll()
    }

    private companion object {
        const val TAG = "CryptoManager"
    }
}

/** Thin wrapper so the crypto layer never mixes Android and JVM base64 flavours. */
internal object Base64Codec {
    fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun decode(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
}
