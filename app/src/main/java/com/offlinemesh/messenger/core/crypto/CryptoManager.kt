package com.offlinemesh.messenger.core.crypto

import android.util.Base64
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.KeyValueEntity
import com.offlinemesh.messenger.core.db.PreKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.whispersystems.signal.protocol.CiphertextMessage
import org.whispersystems.signal.protocol.Curve
import org.whispersystems.signal.protocol.IdentityKey
import org.whispersystems.signal.protocol.IdentityKeyPair
import org.whispersystems.signal.protocol.KeyPairType
import org.whispersystems.signal.protocol.PreKeyBundle
import org.whispersystems.signal.protocol.RemoteSessionName
import org.whispersystems.signal.protocol.SessionBuilder
import org.whispersystems.signal.protocol.SessionCipher
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager(
    private val db: AppDatabase,
    private val store: SignalStore
) {
    companion object {
        private const val MODE_SIGNAL: Byte = 0x01
        private const val MODE_AES_GCM: Byte = 0x02
        private const val HKDF_INFO = "mesh-e2ee-v1"
        private const val DEVICE_ID = 1
    }

    suspend fun ensureIdentityKeys() = withContext(Dispatchers.IO) {
        if (db.keyValueDao().get("identity_public") != null) return@withContext
        val keyPair = Curve.generateKeyPair(KeyPairType.KEY_TYPE_EC)
        val identity = IdentityKeyPair(IdentityKey(keyPair.public), keyPair.private)
        val registrationId = Curve.generateRegistrationId(false)
        val signedPreKey = Curve.generateSignedPreKey(identity, 1)
        val preKeys = Curve.generatePreKeys(1, 20)
        db.keyValueDao().put(
            KeyValueEntity("identity_public", Base64.encodeToString(Curve.encodePublicKey(keyPair.public), Base64.NO_WRAP))
        )
        db.keyValueDao().put(
            KeyValueEntity("identity_private", Base64.encodeToString(Curve.encodePrivateKey(keyPair.private), Base64.NO_WRAP))
        )
        db.keyValueDao().put(KeyValueEntity("registration_id", registrationId.toString()))
        db.keyValueDao().put(KeyValueEntity("signed_prekey_id", "1"))
        db.keyValueDao().put(
            KeyValueEntity(
                "signed_prekey_public",
                Base64.encodeToString(Curve.encodePublicKey(signedPreKey.publicKey), Base64.NO_WRAP)
            )
        )
        db.keyValueDao().put(
            KeyValueEntity(
                "signed_prekey_signature",
                Base64.encodeToString(signedPreKey.signature, Base64.NO_WRAP)
            )
        )
        preKeys.forEach { pk ->
            db.signalDao().upsertPreKey(
                PreKeyEntity(pk.id, Base64.encodeToString(Curve.encodePublicKey(pk.publicKey), Base64.NO_WRAP))
            )
        }
    }

    fun getIdentityPublicKeyBytes(): ByteArray = runBlocking {
        val b64 = db.keyValueDao().get("identity_public") ?: throw IllegalStateException("Identity not ready")
        Base64.decode(b64, Base64.NO_WRAP)
    }

    fun getRegistrationId(): Int = runBlocking {
        db.keyValueDao().get("registration_id")?.toIntOrNull() ?: 0
    }

    fun buildPreKeyBundle(): PreKeyBundle = runBlocking {
        val signedId = db.keyValueDao().get("signed_prekey_id")?.toIntOrNull() ?: 1
        val signed = store.getSignedPreKey(signedId)
        var preKey: org.whispersystems.signal.protocol.PreKeyRecord? = null
        for (id in 1..20) {
            try {
                preKey = store.getPreKey(id)
                break
            } catch (e: Exception) {
            }
        }
        if (preKey == null) throw IllegalStateException("No prekeys available")
        val identityPair = store.getIdentityKeyPair() ?: throw IllegalStateException("Identity not ready")
        val identityBytes = identityPair.publicKey.serialize()
        val signature = Curve.generateSignature(identityPair.privateKey, signed.publicKey.encoded)
        PreKeyBundle(getRegistrationId(), DEVICE_ID, preKey, signed, identityBytes, signature)
    }

    fun processPreKeyBundle(remoteDeviceId: String, encodedBundle: String) {
        val bundle = PreKeyBundleCodec.decode(encodedBundle)
        val builder = SessionBuilder(store, RemoteSessionName(remoteDeviceId, DEVICE_ID))
        builder.processPreKeyBundle(bundle)
    }

    fun processIdentity(remoteDeviceId: String, identityBytes: ByteArray) {
        val identity = IdentityKey(identityBytes)
        store.saveIdentity(RemoteSessionName(remoteDeviceId, DEVICE_ID), identity)
    }

    fun encrypt(remoteDeviceId: String, plaintext: ByteArray): ByteArray {
        val name = RemoteSessionName(remoteDeviceId, DEVICE_ID)
        if (store.containsSession(name)) {
            val cipher = SessionCipher(store, name)
            val message = cipher.encrypt(plaintext)
            return byteArrayOf(MODE_SIGNAL) + message.serialize()
        }
        val identity = store.getIdentity(name)
            ?: throw IllegalStateException("No identity known for $remoteDeviceId")
        val myPair = store.getIdentityKeyPair() ?: throw IllegalStateException("Identity not ready")
        val agreement = Curve.calculateAgreement(myPair.privateKey, identity.publicKey)
        val key = hkdf(agreement, HKDF_INFO.toByteArray(Charsets.UTF_8), 32)
        return byteArrayOf(MODE_AES_GCM) + aesGcmEncrypt(key, plaintext)
    }

    fun decrypt(remoteDeviceId: String, data: ByteArray): ByteArray {
        if (data.isEmpty()) throw IllegalArgumentException("Empty ciphertext")
        return when (data[0]) {
            MODE_SIGNAL -> {
                val message = CiphertextMessage.deserialize(data.copyOfRange(1, data.size))
                SessionCipher(store, RemoteSessionName(remoteDeviceId, DEVICE_ID)).decrypt(message)
            }
            MODE_AES_GCM -> {
                val identity = store.getIdentity(RemoteSessionName(remoteDeviceId, DEVICE_ID))
                    ?: throw IllegalStateException("No identity known for $remoteDeviceId")
                val myPair = store.getIdentityKeyPair() ?: throw IllegalStateException("Identity not ready")
                val agreement = Curve.calculateAgreement(myPair.privateKey, identity.publicKey)
                val key = hkdf(agreement, HKDF_INFO.toByteArray(Charsets.UTF_8), 32)
                aesGcmDecrypt(key, data.copyOfRange(1, data.size))
            }
            else -> throw IllegalArgumentException("Unknown crypto mode: ${data[0]}")
        }
    }

    fun fingerprint(remoteDeviceId: String): String {
        val identity = store.getIdentity(RemoteSessionName(remoteDeviceId, DEVICE_ID)) ?: return ""
        return identity.serialize().joinToString("") { "%02x".format(it) }.chunked(8).joinToString(" ")
    }

    fun myFingerprint(): String {
        val bytes = getIdentityPublicKeyBytes()
        return bytes.joinToString("") { "%02x".format(it) }.chunked(8).joinToString(" ")
    }

    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    private fun aesGcmDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
