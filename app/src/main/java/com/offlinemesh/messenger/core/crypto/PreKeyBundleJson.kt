package com.offlinemesh.messenger.core.crypto

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle

/** A key inside the online pre-key bundle JSON (base85-free, plain base64 of the public key). */
@Serializable
data class BundleKey(
    val id: Int,
    val public: String,
    val signature: String? = null
)

/**
 * JSON form of a Signal [PreKeyBundle] as stored by the online backend.
 *
 * The server keeps only public halves. The one-time pre-key is optional: when the pool is empty
 * the bundle is still a valid PQXDH bundle, exactly like the mesh HELLO path.
 */
@Serializable
data class PreKeyBundleJson(
    val device: String? = null,
    val user: String? = null,
    val deviceId: String? = null,
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: BundleKey,
    val kyberPreKey: BundleKey,
    val oneTimePreKey: BundleKey? = null
) {
    fun toPreKeyBundle(): PreKeyBundle {
        val preKeyId = oneTimePreKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID
        val preKeyPublic = oneTimePreKey?.let { ECPublicKey(unbase64(it.public)) }
        return PreKeyBundle(
            registrationId,
            CryptoKeys.DEVICE_ID,
            preKeyId,
            preKeyPublic,
            signedPreKey.id,
            ECPublicKey(unbase64(signedPreKey.public)),
            unbase64(signedPreKey.signature ?: ""),
            IdentityKey(unbase64(identityKey)),
            kyberPreKey.id,
            KEMPublicKey(unbase64(kyberPreKey.public)),
            unbase64(kyberPreKey.signature ?: "")
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): PreKeyBundleJson? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(bundle: PreKeyBundleJson): String = json.encodeToString(serializer(), bundle)

        /** Builds the JSON form from an in-memory libsignal bundle (used before upload). */
        fun fromBundle(bundle: PreKeyBundle, includeOneTimePreKey: Boolean = true): PreKeyBundleJson =
            PreKeyBundleJson(
                identityKey = base64(bundle.identityKey.serialize()),
                registrationId = bundle.registrationId,
                signedPreKey = BundleKey(
                    id = bundle.signedPreKeyId,
                    public = base64(bundle.signedPreKey.serialize()),
                    signature = base64(bundle.signedPreKeySignature)
                ),
                kyberPreKey = BundleKey(
                    id = bundle.kyberPreKeyId,
                    public = base64(bundle.kyberPreKey.serialize()),
                    signature = base64(bundle.kyberPreKeySignature)
                ),
                oneTimePreKey = if (includeOneTimePreKey) {
                    bundle.preKey?.let { BundleKey(bundle.preKeyId, base64(it.serialize())) }
                } else {
                    null
                }
            )

        private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

private fun unbase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
