package com.offlinemesh.messenger.core.crypto

import android.util.Base64
import org.whispersystems.signal.protocol.Curve
import org.whispersystems.signal.protocol.PreKeyBundle
import org.whispersystems.signal.protocol.PreKeyRecord
import org.whispersystems.signal.protocol.SignedPreKeyRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object PreKeyBundleCodec {

    fun encode(bundle: PreKeyBundle): String {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeInt(bundle.registrationId)
        out.writeInt(bundle.deviceId)
        out.writeInt(bundle.preKey.id)
        writeBytes(out, Curve.encodePublicKey(bundle.preKey.publicKey))
        out.writeInt(bundle.signedPreKey.id)
        writeBytes(out, Curve.encodePublicKey(bundle.signedPreKey.publicKey))
        writeBytes(out, bundle.signedPreKey.signature)
        writeBytes(out, bundle.identityKey)
        writeBytes(out, bundle.identitySignature)
        out.flush()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun decode(encoded: String): PreKeyBundle {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        val registrationId = inp.readInt()
        val deviceId = inp.readInt()
        val preKeyId = inp.readInt()
        val preKeyPub = readBytes(inp)
        val signedId = inp.readInt()
        val signedPub = readBytes(inp)
        val signedSig = readBytes(inp)
        val identityKey = readBytes(inp)
        val identitySig = readBytes(inp)
        val preKey = PreKeyRecord(preKeyId, Curve.decodePublicKey(preKeyPub))
        val signed = SignedPreKeyRecord(signedId, Curve.decodePublicKey(signedPub), signedSig)
        return PreKeyBundle(registrationId, deviceId, preKey, signed, identityKey, identitySig)
    }

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(inp: DataInputStream): ByteArray {
        val len = inp.readInt()
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return bytes
    }
}
