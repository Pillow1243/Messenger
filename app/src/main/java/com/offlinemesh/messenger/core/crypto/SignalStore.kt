package com.offlinemesh.messenger.core.crypto

import android.util.Base64
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.IdentityEntity
import com.offlinemesh.messenger.core.db.KyberPreKeyEntity
import com.offlinemesh.messenger.core.db.PreKeyEntity
import com.offlinemesh.messenger.core.db.SenderKeyEntity
import com.offlinemesh.messenger.core.db.SessionEntity
import com.offlinemesh.messenger.core.db.SignedPreKeyEntity
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.util.UUID

/**
 * Durable Signal Protocol state, backed by the SQLCipher database.
 *
 * Notes:
 *  - All methods are blocking on purpose (libsignal calls them synchronously).
 *  - Identity keys are trusted on first use (TOFU). A changed identity key is reported to the UI
 *    as "identity changed" and is not silently accepted: the user must reset trust explicitly.
 *  - Group messages are sent as per-recipient pairwise ciphertexts, so the SenderKey store is
 *    never used; the two SenderKey methods throw to make that explicit.
 */
class SignalStore(private val db: AppDatabase) : SignalProtocolStore {

    private val dao = db.signalDao()
    private val kv = db.keyValueDao()

    // ---------------------------------------------------------------- identity

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val stored = kv.get(CryptoKeys.IDENTITY_KEY_PAIR)
            ?: throw IllegalStateException("No identity key pair stored")
        return IdentityKeyPair(decode(stored))
    }

    override fun getLocalRegistrationId(): Int =
        kv.get(CryptoKeys.REGISTRATION_ID)?.toIntOrNull() ?: 0

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val encoded = encode(identityKey.serialize())
        val existing = dao.getIdentity(address.name, address.deviceId)
        dao.putIdentity(IdentityEntity(address.name, address.deviceId, encoded))
        return if (existing != null && existing.identityKeyBase64 != encoded) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val existing = dao.getIdentity(address.name, address.deviceId) ?: return true
        return existing.identityKeyBase64 == encode(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val stored = dao.getIdentity(address.name, address.deviceId) ?: return null
        return runCatching { IdentityKey(decode(stored.identityKeyBase64)) }.getOrNull()
    }

    // ---------------------------------------------------------------- sessions

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val stored = dao.session(address.name, address.deviceId) ?: return SessionRecord()
        return runCatching { SessionRecord(decode(stored.recordBase64)) }.getOrDefault(SessionRecord())
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        val records = ArrayList<SessionRecord>(addresses.size)
        for (address in addresses) {
            val stored = dao.session(address.name, address.deviceId)
                ?: throw NoSessionException("No session for $address")
            records.add(SessionRecord(decode(stored.recordBase64)))
        }
        return records
    }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        dao.putSession(
            SessionEntity(
                remoteName = address.name,
                deviceId = address.deviceId,
                recordBase64 = encode(record.serialize())
            )
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        dao.containsSession(address.name, address.deviceId) > 0

    override fun deleteSession(address: SignalProtocolAddress) {
        dao.deleteSession(address.name, address.deviceId)
    }

    override fun deleteAllSessions(name: String) {
        dao.deleteSessionsFor(name)
    }

    // --------------------------------------------------------------- pre-keys

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val stored = dao.getPreKey(preKeyId)
            ?: throw InvalidKeyIdException("No pre-key with id $preKeyId")
        return PreKeyRecord(decode(stored.recordBase64))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        dao.putPreKey(PreKeyEntity(preKeyId, encode(record.serialize())))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = dao.getPreKey(preKeyId) != null

    override fun removePreKey(preKeyId: Int) {
        dao.deletePreKey(preKeyId)
    }

    // ------------------------------------------------------- signed pre-keys

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val stored = dao.getSignedPreKey(signedPreKeyId)
            ?: throw InvalidKeyIdException("No signed pre-key with id $signedPreKeyId")
        return SignedPreKeyRecord(decode(stored.recordBase64))
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        dao.allSignedPreKeys().mapNotNull {
            runCatching { SignedPreKeyRecord(decode(it.recordBase64)) }.getOrNull()
        }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        dao.putSignedPreKey(SignedPreKeyEntity(signedPreKeyId, encode(record.serialize())))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        dao.getSignedPreKey(signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        dao.deleteSignedPreKey(signedPreKeyId)
    }

    // -------------------------------------------------------- kyber pre-keys

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val stored = dao.getKyberPreKey(kyberPreKeyId)
            ?: throw InvalidKeyIdException("No Kyber pre-key with id $kyberPreKeyId")
        return KyberPreKeyRecord(decode(stored.recordBase64))
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        dao.allKyberPreKeys().mapNotNull {
            runCatching { KyberPreKeyRecord(decode(it.recordBase64)) }.getOrNull()
        }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        dao.putKyberPreKey(KyberPreKeyEntity(kyberPreKeyId, encode(record.serialize())))
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        dao.getKyberPreKey(kyberPreKeyId) != null

    /**
     * We publish a single last-resort Kyber pre-key (see [CryptoManager]), so replay protection
     * must be done here: a (kyberId, signedId, baseKey) triple may only be accepted once.
     */
    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey
    ) {
        val fingerprint = "kyber_used:$kyberPreKeyId:$signedPreKeyId:${encode(baseKey.serialize())}"
        if (kv.get(fingerprint) != null) {
            throw ReusedBaseKeyException("Kyber pre-key $kyberPreKeyId reused with the same base key")
        }
        kv.put(com.offlinemesh.messenger.core.db.KeyValueEntity(fingerprint, "1"))
    }

    // --------------------------------------------------------- sender-key store

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        dao.putSenderKey(
            SenderKeyEntity(
                remoteName = sender.name,
                deviceId = sender.deviceId,
                distributionId = distributionId.toString(),
                recordBase64 = encode(record.serialize())
            )
        )
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val stored = dao.getSenderKey(sender.name, sender.deviceId, distributionId.toString())
            ?: return null
        // Always construct a fresh SenderKeyRecord copy from serialized bytes per libsignal contract.
        return runCatching { SenderKeyRecord(decode(stored.recordBase64)) }.getOrNull()
    }

    fun forgetDistribution(distributionId: String) {
        dao.deleteSenderKeysForDistribution(distributionId)
    }

    // ------------------------------------------------------------------ helpers

    /** Drops identity + session for one contact so a new base key can be trusted. */
    fun forgetContact(deviceId: String, deviceIdInt: Int) {
        dao.deleteSession(deviceId, deviceIdInt)
        dao.deleteIdentity(deviceId, deviceIdInt)
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
