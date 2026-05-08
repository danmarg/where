package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatchetCollisionTest {
    init {
        initializeE2eeTests()
    }

    /**
     * Tests convergence when both parties send messages that arrive out-of-order
     * relative to DH ratchet steps.
     *
     * Scenario:
     * 1. Alice sends Msg_A1_S1 (DH A1).
     * 2. Bob receives A1, ratchets to B1, and prepares Msg_B1_S1.
     * 3. Simultaneously, Alice sends Msg_A1_S2 (still DH A1).
     * 4. Alice receives Bob's B1, ratchets to A2, and sends Msg_A2_S1.
     * 5. Bob receives Alice's A1_S2 (late) and then Alice's A2_S1.
     *
     * This verifies that:
     * - Alice can handle Bob's transition while she has a pending A1 message.
     * - Bob can handle a late A1 message after he has already ratcheted to B1.
     */
    @Test
    fun testSimultaneousSymmetricAndDhRatchet() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, relay)
            val bobClient = LocationClient("http://fake", bobStore, relay)

            // 1. Initial pairing
            val qr = aliceStore.createInvite("Alice")
            val (init, _) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val aliceToBobId = aliceStore.listFriends().find { it.name == "Bob" }!!.id
            val bobToAliceId = bobStore.listFriends().find { it.name == "Alice" }!!.id

            // 2. Alice sends A1_S1 (Bootstrap transition).
            // Alice: local=A1, remote=B0, isSendTokenPending=true (posting to T_A0)
            aliceClient.sendKeepalive(aliceToBobId)

            // 3. Bob polls, receives A1_S1.
            // Bob: ratchets to B1 (triggered by A1).
            // Bob's state: local=B1, remote=A1, recvToken=T_A1, isSendTokenPending=true (posting to T_B0)
            bobClient.poll()
            val bobAfterA1 = bobStore.getFriend(bobToAliceId)!!
            val b1 = bobAfterA1.session.localDhPub.toHex()
            assertTrue(bobAfterA1.session.isSendTokenPending)

            // 4. COLLISION PHASE
            // Alice sends A1_S2 (Location 1.0) BEFORE receiving Bob's B1.
            // Alice's state: local=A1, remote=B0, sendToken=T_A1, recvToken=T_B0.
            // Since isSendTokenPending was cleared after Msg_A1_S1, she posts to T_A1.
            aliceClient.sendLocation(1.0, 1.0)

            // Bob sends B1_S1 (Keepalive) BEFORE receiving Alice's A1_S2.
            // Bob's state: local=B1, remote=A1, sendToken=T_B1, recvToken=T_A1.
            // He posts to T_B0 (transition).
            bobClient.sendKeepalive(bobToAliceId)

            // 5. Alice polls. She receives Bob's B1_S1.
            // Alice: ratchets to A2 (triggered by B1).
            // Alice's state: local=A2, remote=B1, recvToken=T_B1.
            val aliceUpdates = aliceClient.poll()
            val aliceAfterB1 = aliceStore.getFriend(aliceToBobId)!!
            val a2 = aliceAfterB1.session.localDhPub.toHex()
            assertTrue(aliceAfterB1.session.isSendTokenPending, "Alice should have a pending transition after seeing B1")

            // 6. Bob polls. He receives Alice's A1_S2.
            // Bob: recvToken is T_A1. Alice posted A1_S2 to T_A1.
            // Even though Bob is at local=B1, he can still decrypt A1 messages.
            val bobUpdates = bobClient.poll()
            assertEquals(1, bobUpdates.size, "Bob should receive the A1_S2 location")
            assertEquals(1.0, bobUpdates.first().lat)

            // 7. FINAL CONVERGENCE
            // Alice sends A2_S1 (Location 2.0).
            aliceClient.sendLocation(2.0, 2.0)

            // Bob polls. He receives A2_S1.
            // Bob: ratchets to B2 (triggered by A2).
            val bobUpdatesFinal = bobClient.poll()
            assertEquals(1, bobUpdatesFinal.size, "Bob should receive the A2_S1 location")
            assertEquals(2.0, bobUpdatesFinal.first().lat)

            // Verify session health
            val finalAlice = aliceStore.getFriend(aliceToBobId)!!
            val finalBob = bobStore.getFriend(bobToAliceId)!!
            assertEquals(a2, finalBob.session.remoteDhPub.toHex())
            assertEquals(b1, finalAlice.session.remoteDhPub.toHex())
        }

    /**
     * Tests "Cross-Initialization" where both parties send messages immediately
     * after pairing, potentially before tokens are fully established.
     */
    @Test
    fun testCrossInitialization() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, relay)
            val bobClient = LocationClient("http://fake", bobStore, relay)

            val qr = aliceStore.createInvite("Alice")
            val (init, _) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val aliceToBobId = aliceStore.listFriends().find { it.name == "Bob" }!!.id
            val bobToAliceId = bobStore.listFriends().find { it.name == "Alice" }!!.id

            // Alice and Bob both send a location BEFORE polling each other.
            // Alice: sends A1_S1 to T_A0 (transition).
            aliceClient.sendLocation(10.0, 0.0)

            // Bob: sends B0_S1 to T_B0 (Bob hasn't ratcheted yet, he's still at B0).
            bobClient.sendLocation(0.0, 20.0)

            // Now they both poll.
            val aliceUpdates = aliceClient.poll()
            val bobUpdates = bobClient.poll()

            assertEquals(1, aliceUpdates.size)
            assertEquals(20.0, aliceUpdates.first().lng)
            assertEquals(1, bobUpdates.size)
            assertEquals(10.0, bobUpdates.first().lat)

            // Verify tokens
            val aliceEntry = aliceStore.getFriend(aliceToBobId)!!
            val bobEntry = bobStore.getFriend(bobToAliceId)!!

            // Bob should have ratcheted to B1 after seeing Alice's A1.
            assertTrue(bobEntry.session.isSendTokenPending, "Bob should be pending transition to B1")

            // Alice should have ratcheted to A2 after seeing Bob's B0.
            // Wait, Alice already ratcheted to A1 at bootstrap.
            // Receiving B0 (Bob's bootstrap key) doesn't trigger a DH ratchet if it's the SAME key.
            // But Bob's B0_S1 uses the same B0 she already knew.
        }
}
