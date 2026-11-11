package com.offlinemesh.messenger.core.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Wire/QR encoding for a Signal [PreKeyBundle].
 *
 * Layout (big endian):
 *   magic "MB1" | version | regId | deviceId | preKeyId | signedPreKeyId | kyberPreKeyId
 *   | len+bytes preKeyPublic | len+bytes signedPreKeyPublic | len+bytes signedPreKeySignature
 *   | len+bytes identityKey | len+bytes kyberPreKeyPublic | len+bytes kyberPreKeySignature
 *
 * A zero length pre-key means "no one-time pre-key available".
 */
object PreKeyBundleCodec {

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1
    private const val MAX_FIELD = 8 * 1024

    fun encode(bundle: PreKeyBundle): ByteArray {
        val out = ByteArrayOutputStream(2048)
        DataOutputStream(out).use { stream ->
            stream.write(MAGIC)
            stream.writeByte(VERSION)
            stream.writeInt(bundle.registrationId)
            stream.writeInt(bundle.deviceId)
            stream.writeInt(bundle.preKeyId)
            stream.writeInt(bundle.signedPreKeyId)
            stream.writeInt(bundle.kyberPreKeyId)
            stream.writeBlob(bundle.preKey?.serialize() ?: ByteArray(0))
            stream.writeBlob(bundle.signedPreKey.serialize())
            stream.writeBlob(bundle.signedPreKeySignature)
            stream.writeBlob(bundle.identityKey.serialize())
            stream.writeBlob(bundle.kyberPreKey.serialize())
            stream.writeBlob(bundle.kyberPreKeySignature)
            stream.flush()
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): PreKeyBundle {
        val buffer = ByteBuffer.wrap(bytes)
        try {
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            require(magic.contentEquals(MAGIC)) { "not a mesh pre-key bundle" }
            val version = buffer.get().toInt()
            require(version == VERSION) { "unsupported bundle version $version" }
            val registrationId = buffer.getInt()
            val deviceId = buffer.getInt()
            val preKeyId = buffer.getInt()
            val signedPreKeyId = buffer.getInt()
            val kyberPreKeyId = buffer.getInt()
            val preKeyBytes = buffer.readBlob()
            val signedPreKeyBytes = buffer.readBlob()
            val signedPreKeySignature = buffer.readBlob()
            val identityKeyBytes = buffer.readBlob()
            val kyberBytes = buffer.readBlob()
            val kyberSignature = buffer.readBlob()
            require(buffer.remaining() == 0) { "trailing bytes in bundle" }

            val preKeyPublic = if (preKeyBytes.isEmpty()) null else ECPublicKey(preKeyBytes)
            return PreKeyBundle(
                registrationId,
                deviceId,
                preKeyId,
                preKeyPublic,
                signedPreKeyId,
                ECPublicKey(signedPreKeyBytes),
                signedPreKeySignature,
                IdentityKey(identityKeyBytes),
                kyberPreKeyId,
                KEMPublicKey(kyberBytes),
                kyberSignature
            )
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("truncated pre-key bundle", e)
        }
    }

    private fun DataOutputStream.writeBlob(bytes: ByteArray) {
        require(bytes.size <= MAX_FIELD) { "bundle field too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteBuffer.readBlob(): ByteArray {
        val length = getInt()
        require(length in 0..MAX_FIELD) { "invalid bundle field length $length" }
        val bytes = ByteArray(length)
        get(bytes)
        return bytes
    }
}
