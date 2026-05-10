package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DoubleRatchetSkipTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testDoubleRatchetSkipReproFinal() = runTest {
        val aliceManager = E2eeManager(MemoryStorage())
        val bobManager = E2eeManager(MemoryStorage())
        val relay = RelayMailboxClient()
        val aliceClient = LocationClient("http://fake", aliceManager, relay)
        val bobClient = LocationClient("http://fake", bobManager, relay)

        // 1. Pair
        val qr = aliceManager.createInvite("Alice")
        val (init, _) = bobManager.processScannedQr(qr)
        aliceManager.processKeyExchangeInit(init, "Bob", qr.ekPub)
        val aliceToBobId = aliceManager.listFriends().first().id
        val bobToAliceId = bobManager.listFriends().first().id

        // Alice is at: sendToken = T_A1, prevSendToken = T_A0, isSendTokenPending = true.
        // Bob is polling T_A0.
        val aliceInitial = aliceManager.getFriend(aliceToBobId)!!
        val tA0 = aliceInitial.session.prevSendToken.toHex()
        assertTrue(aliceInitial.session.isSendTokenPending)
        assertEquals(tA0, bobManager.getFriend(bobToAliceId)!!.session.recvToken.toHex())

        // 2. Bob sends a message (B1) BEFORE Alice sends anything.
        bobClient.sendLocation(1.0, 1.0) // B1 -> T_B0.

        // 3. Alice polls. She receives B1 and ratchets.
        aliceClient.poll()

        // Alice now has a new sendToken (T_A2) and a new prevSendToken.
        // BUG: Alice's prevSendToken would be T_A1 because it took the current sendToken.
        // FIX: Alice's prevSendToken should STILL be T_A0 because she never successfully sent to T_A0.
        val aliceAfterPoll = aliceManager.getFriend(aliceToBobId)!!
        val alicePrevSendToken = aliceAfterPoll.session.prevSendToken.toHex()

        println("Alice prevSendToken: $alicePrevSendToken (expected $tA0)")
        assertEquals(tA0, alicePrevSendToken, "Alice's prevSendToken must stay T_A0 if isSendTokenPending was true")

        // 4. Verification of recovery: Alice sends her first message, it MUST go to T_A0.
        aliceClient.sendLocation(5.0, 5.0)
        val aliceFinal = aliceManager.getFriend(aliceToBobId)!!
        assertNull(aliceFinal.outbox)

        // 5. Bob polls T_A0 and receives it.
        val updates = bobClient.poll()
        assertTrue(updates.any { it.lat == 5.0 }, "Bob should have received Alice's message on T_A0")
    }
}
