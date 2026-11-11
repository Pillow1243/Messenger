package com.offlinemesh.messenger.core.crypto

import android.util.Base64
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.IdentityEntity
import com.offlinemesh.messenger.core.db.PreKeyEntity
import com.offlinemesh.messenger.core.db.SessionEntity
import com.offlinemesh.messenger.core.db.SignedPreKeyEntity
import kotlinx.coroutines.runBlocking
import org.whispersystems.signal.protocol.Curve
import org.whispersystems.signal.protocol.IdentityKey
import org.whispersystems.signal.protocol.IdentityKeyPair
import org.whispersystems.signal.protocol.InvalidKeyIdException
import org.whispersystems.signal.protocol.PreKeyRecord
import org.whispersystems.signal.protocol.RemoteSessionName
import org.whispersystems.signal.protocol.SessionRecord
import org.whispersystems.signal.protocol.SignedPreKeyRecord
import org.whispersystems.signal.protocol.SignalProtocolStore

class SignalStore(private val db: AppDatabase) : SignalProtocolStore {

    override fun getIdentityKeyPair(): IdentityKeyPair? {
        return runBlocking {
            val pubB64 = db.keyValueDao().get("identity_public") ?: return@runBlocking null
            val privB64 = db.keyValueDao().get("identity_private") ?: return@runBlocking null
            val pub = Curve.decodePublicKey(Base64.decode(pubB64, Base64.NO_WRAP))
            val priv = Curve.decodePrivateKey(Base64.decode(privB64, Base64.NO_WRAP))
            IdentityKeyPair(IdentityKey(pub), priv)
        }
    }

    override fun getLocalRegistrationId(): Int {
        return runBlocking {
            db.keyValueDao().get("registration_id")?.toIntOrNull() ?: 0
        }
    }

    override fun storeSession(name: RemoteSessionName, record: SessionRecord) {
        runBlocking {
            db.signalDao().upsertSession(
                SessionEntity(
                    remoteName = name.name,
                    deviceId = name.deviceId,
                    recordBase64 = Base64.encodeToString(record.serialize(), Base64.NO_WRAP)
                )
            )
        }
    }

    override fun getSession(name: RemoteSessionName): SessionRecord? {
        return runBlocking {
            val entity = db.signalDao().getSession(name.name) ?: return@runBlocking null
            SessionRecord(Base64.decode(entity.recordBase64, Base64.NO_WRAP))
        }
    }

    override fun deleteSession(name: RemoteSessionName) {
        runBlocking { db.signalDao().deleteSession(name.name) }
    }

    override fun containsSession(name: RemoteSessionName): Boolean {
        return runBlocking { db.signalDao().getSession(name.name) != null }
    }

    override fun deleteAllSessions() {
        runBlocking { db.signalDao().deleteAllSessions() }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runBlocking {
            db.signalDao().upsertPreKey(
                PreKeyEntity(
                    keyId = preKeyId,
                    publicKeyBase64 = Base64.encodeToString(Curve.encodePublicKey(record.publicKey), Base64.NO_WRAP)
                )
            )
        }
    }

    override fun getPreKey(preKeyId: Int): PreKeyRecord {
        return runBlocking {
            val entity = db.signalDao().getPreKey(preKeyId)
                ?: throw InvalidKeyIdException("Missing prekey $preKeyId")
            PreKeyRecord(preKeyId, Curve.decodePublicKey(Base64.decode(entity.publicKeyBase64, Base64.NO_WRAP)))
        }
    }

    override fun removePreKey(preKeyId: Int) {
        runBlocking { db.signalDao().deletePreKey(preKeyId) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        runBlocking {
            db.signalDao().upsertSignedPreKey(
                SignedPreKeyEntity(
                    keyId = signedPreKeyId,
                    publicKeyBase64 = Base64.encodeToString(Curve.encodePublicKey(record.publicKey), Base64.NO_WRAP),
                    signatureBase64 = Base64.encodeToString(record.signature, Base64.NO_WRAP)
                )
            )
        }
    }

    override fun getSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        return runBlocking {
            val entity = db.signalDao().getSignedPreKey(signedPreKeyId)
                ?: throw InvalidKeyIdException("Missing signed prekey $signedPreKeyId")
            SignedPreKeyRecord(
                signedPreKeyId,
                Curve.decodePublicKey(Base64.decode(entity.publicKeyBase64, Base64.NO_WRAP)),
                Base64.decode(entity.signatureBase64, Base64.NO_WRAP)
            )
        }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        runBlocking { db.signalDao().deleteSignedPreKey(signedPreKeyId) }
    }

    override fun storeIdentityKey(name: RemoteSessionName, identityKey: IdentityKey) {
        runBlocking {
            db.signalDao().upsertIdentity(
                IdentityEntity(
                    remoteName = name.name,
                    deviceId = name.deviceId,
                    identityKeyBase64 = Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)
                )
            )
        }
    }

    override fun getIdentity(name: RemoteSessionName): IdentityKey? {
        return runBlocking {
            val entity = db.signalDao().getIdentity(name.name) ?: return@runBlocking null
            IdentityKey(Base64.decode(entity.identityKeyBase64, Base64.NO_WRAP))
        }
    }

    override fun isTrustedIdentity(name: RemoteSessionName, identityKey: IdentityKey): Boolean {
        return true
    }

    override fun isTrustedIdentity(identifier: ByteArray, identityKey: ByteArray, isFirstMessage: Boolean): Boolean {
        return true
    }

    override fun saveIdentity(name: RemoteSessionName, identityKey: IdentityKey): Boolean {
        val existing = getIdentity(name)
        storeIdentityKey(name, identityKey)
        return existing == null || existing != identityKey
    }

    override fun setSessionVersion(name: RemoteSessionName, version: Int) {
    }

    override fun getSessionVersion(name: RemoteSessionName): Int {
        return 3
    }
}
