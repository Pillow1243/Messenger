package com.offlinemesh.messenger.core.crypto

import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.util.UUID

/**
 * Audited Signal Sender Keys group encryption engine backed directly by official
 * `org.signal:libsignal` (`GroupSessionBuilder`, `GroupCipher`, `SenderKeyDistributionMessage`).
 *
 * Protocol versioning:
 *  - [PROTOCOL_PAIRWISE_V1] = 1: Legacy pairwise per-device fan-out (`SessionCipher` per device).
 *    Existing conversations retain version 1 unless explicitly upgraded.
 *  - [PROTOCOL_SENDER_KEYS_V2] = 2: Signal Sender Keys. Each sender generates a per-epoch
 *    `SenderKeyDistributionMessage` bound to `(senderAddress, distributionId)`, distributes it
 *    pairwise-encrypted via `SessionCipher` to active group member devices, and encrypts group
 *    messages in O(1) via `GroupCipher`.
 *
 * Membership & device revocation invariant:
 *  - Whenever a member leaves or is removed, or any member revokes a device, the conversation
 *    rotates to a fresh `distributionId` (and increments `senderKeyEpoch`). Remaining members
 *    generate fresh Sender Keys for the new `distributionId` and distribute SKDMs exclusively to
 *    current non-revoked member devices, preventing removed members or revoked devices from
 *    decrypting any subsequent group traffic.
 */
class SenderKeyGroupEngine(
    private val store: SignalProtocolStore
) {
    /**
     * Creates (or loads the current) serialized [SenderKeyDistributionMessage] for [senderAddress]
     * and [distributionId].
     */
    fun createDistribution(
        senderAddress: SignalProtocolAddress,
        distributionId: UUID
    ): ByteArray {
        val skdm = GroupSessionBuilder(store).create(senderAddress, distributionId)
        return skdm.serialize()
    }

    /**
     * Installs a peer's [SenderKeyDistributionMessage] so subsequent [decrypt] calls for
     * `(senderAddress, distributionId)` succeed.
     */
    fun processDistribution(
        senderAddress: SignalProtocolAddress,
        serializedSkdm: ByteArray
    ) {
        val skdm = try {
            SenderKeyDistributionMessage(serializedSkdm)
        } catch (e: Exception) {
            throw CryptoException("Invalid SenderKeyDistributionMessage from ${senderAddress.name}", e)
        }
        GroupSessionBuilder(store).process(senderAddress, skdm)
    }

    /**
     * Encrypts [serializedSkdm] for a single [recipientAddress] using the established pairwise
     * Signal Double Ratchet / PQXDH session, returning `(cipherType, ciphertextBytes)`.
     */
    fun encryptSkdmForPeer(
        serializedSkdm: ByteArray,
        recipientAddress: SignalProtocolAddress
    ): Pair<Int, ByteArray> {
        if (!store.containsSession(recipientAddress)) {
            throw NoSessionYetException(recipientAddress.name)
        }
        val msg = SessionCipher(store, recipientAddress).encrypt(serializedSkdm)
        return msg.type to msg.serialize()
    }

    /**
     * Decrypts a pairwise-encrypted SKDM received from [senderAddress] and installs it into the
     * local [SenderKeyStore].
     */
    fun decryptAndProcessSkdmFromPeer(
        senderAddress: SignalProtocolAddress,
        cipherType: Int,
        ciphertext: ByteArray
    ) {
        val cipher = SessionCipher(store, senderAddress)
        val skdmBytes = try {
            when (cipherType) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(ciphertext))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(ciphertext))
                else -> throw CryptoException("Unsupported SKDM cipher type $cipherType")
            }
        } catch (e: DuplicateMessageException) {
            throw DuplicateCiphertextException(senderAddress.name, e)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("Failed to decrypt SKDM from ${senderAddress.name}", e)
        }
        processDistribution(senderAddress, skdmBytes)
    }

    /**
     * O(1) group message encryption using `GroupCipher`. Caller must have called
     * [createDistribution] for `(senderAddress, distributionId)` at least once in this epoch.
     */
    fun encryptGroupMessage(
        senderAddress: SignalProtocolAddress,
        distributionId: UUID,
        plaintext: ByteArray
    ): ByteArray {
        return try {
            GroupCipher(store, senderAddress).encrypt(distributionId, plaintext).serialize()
        } catch (e: NoSessionException) {
            throw NoSessionYetException("${senderAddress.name}@$distributionId")
        } catch (e: Exception) {
            throw CryptoException("SenderKey encryption failed for $distributionId", e)
        }
    }

    /**
     * Decrypts a `SenderKeyMessage` from [senderAddress]. Enforces replay protection via
     * libsignal's SenderKey ratchet chain (`DuplicateMessageException` -> [DuplicateCiphertextException]).
     */
    fun decryptGroupMessage(
        senderAddress: SignalProtocolAddress,
        ciphertext: ByteArray
    ): ByteArray {
        return try {
            GroupCipher(store, senderAddress).decrypt(ciphertext)
        } catch (e: DuplicateMessageException) {
            throw DuplicateCiphertextException(senderAddress.name, e)
        } catch (e: NoSessionException) {
            throw NoSessionYetException(senderAddress.name)
        } catch (e: Exception) {
            throw CryptoException("SenderKey decryption failed from ${senderAddress.name}", e)
        }
    }

    companion object {
        const val PROTOCOL_PAIRWISE_V1 = 1
        const val PROTOCOL_SENDER_KEYS_V2 = 2
    }
}
