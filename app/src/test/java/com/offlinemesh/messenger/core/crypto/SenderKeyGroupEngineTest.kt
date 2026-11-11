package com.offlinemesh.messenger.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

/**
 * End-to-end cryptographic verification of [SenderKeyGroupEngine] backed by real
 * `org.signal:libsignal-client:0.86.5` JNI primitives (`GroupSessionBuilder`, `GroupCipher`,
 * `SenderKeyDistributionMessage`, PQXDH `SessionBuilder`, and `SessionCipher`).
 */
class SenderKeyGroupEngineTest {

    private class TestNode(deviceId: String) {
        val address = SignalProtocolAddress(deviceId, 1)
        val store = TestSignalStore()
        val engine = SenderKeyGroupEngine(store)

        fun createPreKeyBundle(oneTimeId: Int = 10): PreKeyBundle {
            val identity = store.getIdentityKeyPair()
            val preKeyPair = ECKeyPair.generate()
            store.storePreKey(oneTimeId, PreKeyRecord(oneTimeId, preKeyPair))

            val signedId = 1
            val signedPair = ECKeyPair.generate()
            val signedSig = identity.privateKey.calculateSignature(signedPair.publicKey.serialize())
            store.storeSignedPreKey(
                signedId,
                SignedPreKeyRecord(signedId, System.currentTimeMillis(), signedPair, signedSig)
            )

            val kyberId = 1
            val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSig = identity.privateKey.calculateSignature(kyberPair.publicKey.serialize())
            store.storeKyberPreKey(
                kyberId,
                KyberPreKeyRecord(kyberId, System.currentTimeMillis(), kyberPair, kyberSig)
            )

            return PreKeyBundle(
                store.getLocalRegistrationId(),
                1,
                oneTimeId,
                preKeyPair.publicKey,
                signedId,
                signedPair.publicKey,
                signedSig,
                identity.publicKey,
                kyberId,
                kyberPair.publicKey,
                kyberSig
            )
        }
    }

    private fun establishPairwiseSession(initiator: TestNode, responder: TestNode, preKeyId: Int = 10) {
        val bundle = responder.createPreKeyBundle(preKeyId)
        SessionBuilder(initiator.store, responder.address).process(bundle)
    }

    @Test
    fun `sender keys encrypt once and all member devices decrypt including out-of-order messages`() {
        val alice = TestNode("alice-dev-1")
        val aliceTablet = TestNode("alice-dev-2")
        val bob = TestNode("bob-dev-1")
        val charlie = TestNode("charlie-dev-1")

        establishPairwiseSession(alice, aliceTablet, 11)
        establishPairwiseSession(alice, bob, 12)
        establishPairwiseSession(alice, charlie, 13)

        val epoch1DistId = UUID.randomUUID()
        val rawSkdm = alice.engine.createDistribution(alice.address, epoch1DistId)

        // Distribute SKDM pairwise-encrypted via SessionCipher to every member device
        for (recipient in listOf(aliceTablet, bob, charlie)) {
            val (cipherType, encryptedSkdm) = alice.engine.encryptSkdmForPeer(rawSkdm, recipient.address)
            recipient.engine.decryptAndProcessSkdmFromPeer(alice.address, cipherType, encryptedSkdm)
        }

        val msg1Plain = "First SenderKey group message".toByteArray(Charsets.UTF_8)
        val msg2Plain = "Second SenderKey group message (delivered first!)".toByteArray(Charsets.UTF_8)

        val cipher1 = alice.engine.encryptGroupMessage(alice.address, epoch1DistId, msg1Plain)
        val cipher2 = alice.engine.encryptGroupMessage(alice.address, epoch1DistId, msg2Plain)

        // Deliver in-order to Bob & Alice's second device
        assertArrayEquals(msg1Plain, bob.engine.decryptGroupMessage(alice.address, cipher1))
        assertArrayEquals(msg2Plain, bob.engine.decryptGroupMessage(alice.address, cipher2))
        assertArrayEquals(msg1Plain, aliceTablet.engine.decryptGroupMessage(alice.address, cipher1))
        assertArrayEquals(msg2Plain, aliceTablet.engine.decryptGroupMessage(alice.address, cipher2))

        // Deliver OUT-OF-ORDER (cipher2 before cipher1) to Charlie
        assertArrayEquals(msg2Plain, charlie.engine.decryptGroupMessage(alice.address, cipher2))
        assertArrayEquals(msg1Plain, charlie.engine.decryptGroupMessage(alice.address, cipher1))
    }

    @Test
    fun `replaying a previously decrypted group message is rejected with DuplicateCiphertextException`() {
        val alice = TestNode("alice-dev-1")
        val bob = TestNode("bob-dev-1")
        establishPairwiseSession(alice, bob)

        val distId = UUID.randomUUID()
        val skdm = alice.engine.createDistribution(alice.address, distId)
        val (type, encSkdm) = alice.engine.encryptSkdmForPeer(skdm, bob.address)
        bob.engine.decryptAndProcessSkdmFromPeer(alice.address, type, encSkdm)

        val ciphertext = alice.engine.encryptGroupMessage(alice.address, distId, "once only".toByteArray())
        assertEquals("once only", String(bob.engine.decryptGroupMessage(alice.address, ciphertext)))

        try {
            bob.engine.decryptGroupMessage(alice.address, ciphertext)
            fail("Expected DuplicateCiphertextException on replay")
        } catch (_: DuplicateCiphertextException) {
            // Replay properly rejected by libsignal SenderKey chain!
        }
    }

    @Test
    fun `member removal rotates distributionId so removed member cannot decrypt future messages`() {
        val alice = TestNode("alice-dev-1")
        val bob = TestNode("bob-dev-1")
        val removedMember = TestNode("mallory-dev-1")

        establishPairwiseSession(alice, bob, 21)
        establishPairwiseSession(alice, removedMember, 22)

        // Epoch 1: all 3 members are in the group
        val epoch1Dist = UUID.randomUUID()
        val skdmEpoch1 = alice.engine.createDistribution(alice.address, epoch1Dist)
        for (peer in listOf(bob, removedMember)) {
            val (t, ct) = alice.engine.encryptSkdmForPeer(skdmEpoch1, peer.address)
            peer.engine.decryptAndProcessSkdmFromPeer(alice.address, t, ct)
        }

        val beforeRemoval = alice.engine.encryptGroupMessage(alice.address, epoch1Dist, "hello all".toByteArray())
        assertEquals("hello all", String(bob.engine.decryptGroupMessage(alice.address, beforeRemoval)))
        assertEquals("hello all", String(removedMember.engine.decryptGroupMessage(alice.address, beforeRemoval)))

        // Remove Mallory -> rotate group to Epoch 2 with fresh distributionId, distribute SKDM ONLY to Bob
        val epoch2Dist = UUID.randomUUID()
        val skdmEpoch2 = alice.engine.createDistribution(alice.address, epoch2Dist)
        val (typeBob, encBob) = alice.engine.encryptSkdmForPeer(skdmEpoch2, bob.address)
        bob.engine.decryptAndProcessSkdmFromPeer(alice.address, typeBob, encBob)

        val postRemovalSecret = "post-removal confidential plan".toByteArray()
        val postRemovalCipher = alice.engine.encryptGroupMessage(alice.address, epoch2Dist, postRemovalSecret)

        // Remaining member Bob decrypts cleanly
        assertArrayEquals(postRemovalSecret, bob.engine.decryptGroupMessage(alice.address, postRemovalCipher))

        // Removed member Mallory CANNOT decrypt post-removal ciphertext
        try {
            removedMember.engine.decryptGroupMessage(alice.address, postRemovalCipher)
            fail("Removed member must NEVER decrypt post-removal SenderKey messages")
        } catch (_: NoSessionYetException) {
            // Expected: Mallory lacks the rotated Epoch 2 SenderKey!
        }
    }

    @Test
    fun `device revocation rotates group SenderKey so revoked device cannot decrypt future messages`() {
        val alice = TestNode("alice-dev-1")
        val bobActivePhone = TestNode("bob-phone-active")
        val bobRevokedLaptop = TestNode("bob-laptop-revoked")

        establishPairwiseSession(alice, bobActivePhone, 31)
        establishPairwiseSession(alice, bobRevokedLaptop, 32)

        // Epoch 1: both of Bob's devices receive SKDM
        val epoch1 = UUID.randomUUID()
        val skdm1 = alice.engine.createDistribution(alice.address, epoch1)
        for (dev in listOf(bobActivePhone, bobRevokedLaptop)) {
            val (t, ct) = alice.engine.encryptSkdmForPeer(skdm1, dev.address)
            dev.engine.decryptAndProcessSkdmFromPeer(alice.address, t, ct)
        }
        val msgEpoch1 = alice.engine.encryptGroupMessage(alice.address, epoch1, "epoch-1".toByteArray())
        assertEquals("epoch-1", String(bobActivePhone.engine.decryptGroupMessage(alice.address, msgEpoch1)))
        assertEquals("epoch-1", String(bobRevokedLaptop.engine.decryptGroupMessage(alice.address, msgEpoch1)))

        // Bob revokes laptop -> mm_revoke_device rotates group to Epoch 2 -> Alice sends SKDM only to active phone
        val epoch2 = UUID.randomUUID()
        val skdm2 = alice.engine.createDistribution(alice.address, epoch2)
        val (t2, ct2) = alice.engine.encryptSkdmForPeer(skdm2, bobActivePhone.address)
        bobActivePhone.engine.decryptAndProcessSkdmFromPeer(alice.address, t2, ct2)

        val msgEpoch2 = alice.engine.encryptGroupMessage(alice.address, epoch2, "after-device-revocation".toByteArray())
        assertEquals("after-device-revocation", String(bobActivePhone.engine.decryptGroupMessage(alice.address, msgEpoch2)))

        try {
            bobRevokedLaptop.engine.decryptGroupMessage(alice.address, msgEpoch2)
            fail("Revoked device must NEVER decrypt group messages after epoch rotation")
        } catch (_: NoSessionYetException) {
            // Expected!
        }
    }

    /**
     * Pure JVM [SignalProtocolStore] mirroring [SignalStore]'s serialization invariants
     * (defensive byte-array copies on `loadSenderKey` and `loadSession`).
     */
    private class TestSignalStore : SignalProtocolStore {
        private val identityKeyPair = IdentityKeyPair.generate()
        private val registrationId = KeyHelper.generateRegistrationId(false)
        private val identities = mutableMapOf<SignalProtocolAddress, ByteArray>()
        private val sessions = mutableMapOf<SignalProtocolAddress, ByteArray>()
        private val preKeys = mutableMapOf<Int, ByteArray>()
        private val signedPreKeys = mutableMapOf<Int, ByteArray>()
        private val kyberPreKeys = mutableMapOf<Int, ByteArray>()
        private val usedKyber = mutableSetOf<String>()
        private val senderKeys = mutableMapOf<Pair<SignalProtocolAddress, UUID>, ByteArray>()

        override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair
        override fun getLocalRegistrationId(): Int = registrationId

        override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
            val bytes = identityKey.serialize()
            val prev = identities.put(address, bytes)
            return if (prev != null && !prev.contentEquals(bytes)) {
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
            val prev = identities[address] ?: return true
            return prev.contentEquals(identityKey.serialize())
        }

        override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
            identities[address]?.let { IdentityKey(it) }

        override fun loadSession(address: SignalProtocolAddress): SessionRecord =
            sessions[address]?.let { SessionRecord(it) } ?: SessionRecord()

        override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
            addresses.map { addr ->
                val raw = sessions[addr] ?: throw NoSessionException("Missing session for $addr")
                SessionRecord(raw)
            }

        override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

        override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
            sessions[address] = record.serialize()
        }

        override fun containsSession(address: SignalProtocolAddress): Boolean = sessions.containsKey(address)
        override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address) }
        override fun deleteAllSessions(name: String) {
            sessions.keys.removeIf { it.name == name }
        }

        override fun loadPreKey(preKeyId: Int): PreKeyRecord =
            preKeys[preKeyId]?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("No prekey $preKeyId")

        override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
            preKeys[preKeyId] = record.serialize()
        }

        override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)
        override fun removePreKey(preKeyId: Int) { preKeys.remove(preKeyId) }

        override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
            signedPreKeys[signedPreKeyId]?.let { SignedPreKeyRecord(it) }
                ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")

        override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
            signedPreKeys.values.map { SignedPreKeyRecord(it) }

        override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
            signedPreKeys[signedPreKeyId] = record.serialize()
        }

        override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeys.containsKey(signedPreKeyId)
        override fun removeSignedPreKey(signedPreKeyId: Int) { signedPreKeys.remove(signedPreKeyId) }

        override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
            kyberPreKeys[kyberPreKeyId]?.let { KyberPreKeyRecord(it) }
                ?: throw InvalidKeyIdException("No kyber prekey $kyberPreKeyId")

        override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
            kyberPreKeys.values.map { KyberPreKeyRecord(it) }

        override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
            kyberPreKeys[kyberPreKeyId] = record.serialize()
        }

        override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeys.containsKey(kyberPreKeyId)

        override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
            val tag = "$kyberPreKeyId:$signedPreKeyId:${baseKey.serialize().contentToString()}"
            if (!usedKyber.add(tag)) throw ReusedBaseKeyException("Kyber reused")
        }

        override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
            senderKeys[sender to distributionId] = record.serialize()
        }

        override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
            senderKeys[sender to distributionId]?.let { SenderKeyRecord(it) }
    }
}
