package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GapFillTest {
    init {
        initializeE2eeTests()
    }

    data class ExchangeResult(
        val aliceSession: SessionState,
        val bobSession: SessionState,
    )

    /** Full key exchange: Alice sends, Bob receives. */
    private fun exchangeKeys(): ExchangeResult {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        return ExchangeResult(aliceSession, bobSession)
    }

    @Test
    fun testCrossEpochGapFillUsingHeaderPn() =
        runTest {
            val (aliceSession, bobSession) = exchangeKeys()
            val loc = MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 15.0, ts = 1711152000L)

            // 1. Alice sends 3 messages in current epoch (A1).
            // bobSession is at A0, so m1 will trigger Bob's ratchet to A1.
            val (aS1, m1) = Session.encryptMessage(aliceSession, loc)
            val (aS2, m2) = Session.encryptMessage(aS1, loc)
            val (aS3, m3) = Session.encryptMessage(aS2, loc)

            // 2. Bob receives only m1. Bob's recvSeq for A1 is now 1.
            val (bS1, _) = Session.decryptMessage(bobSession, m1)
            assertEquals(1L, bS1.recvSeq)
            assertEquals(aliceSession.localDhPub.toHex(), bS1.remoteDhPub.toHex())

            // 3. Bob sends a message to Alice (this triggers Bob's send-ratchet to B1).
            val (bS2, bMsg) = Session.encryptMessage(bS1, loc)

            // 4. Alice receives Bob's message and ratchets to A2.
            // Alice's new session will have pn=3 (because she sent 3 messages in A1).
            val (aS4, _) = Session.decryptMessage(aS3, bMsg)
            assertEquals(3L, aS4.pn)

            // 5. Alice sends a message in the new epoch (A2).
            // This message will have pn=3 in its header and plaintext.
            val (aS5, m4) = Session.encryptMessage(aS4, loc)

            // 6. Bob receives m4.
            // Bob's current state is (remoteDhPub=A1, recvSeq=1).
            // Bob sees m4 is from a NEW epoch (A2).
            // Bob should see header.pn=3 and skip messages 2 and 3 from epoch A1.
            val (bS3, _) = Session.decryptMessage(bS2, m4)

            // Verify Bob skipped messages 2 and 3 from A1.
            val aliceA1PubHex = aliceSession.localDhPub.toHex()
            val skipKey2 = aliceA1PubHex + "_2"
            val skipKey3 = aliceA1PubHex + "_3"

            assertTrue(bS3.skippedMessageKeys.containsKey(skipKey2), "Should have skipped message 2 from A1")
            assertTrue(bS3.skippedMessageKeys.containsKey(skipKey3), "Should have skipped message 3 from A1")

            // Verify we can actually decrypt m2 using the skipped key.
            val (bS4, dec2) = Session.decryptMessage(bS3, m2)
            assertTrue(dec2 is MessagePlaintext.Location)
            assertFalse(bS4.skippedMessageKeys.containsKey(skipKey2), "Used key should be removed from cache")
        }

    @Test
    fun testPnInPlaintextMatchesHeader() =
        runTest {
            val (aliceSession, bobSession) = exchangeKeys()
            val loc = MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 15.0, ts = 1711152000L)

            // 1. Bob receives Alice's first message to ratchet his side to B1.
            val (aS1, m1_alice) = Session.encryptMessage(aliceSession, loc)
            val (bS1, _) = Session.decryptMessage(bobSession, m1_alice)

            // 2. Alice sends 4 more messages in Epoch A1.
            var aSess = aS1
            repeat(4) {
                val (newA, _) = Session.encryptMessage(aSess, loc)
                aSess = newA
            }
            assertEquals(5L, aSess.sendSeq)

            // 3. Bob sends a message to Alice (Epoch B1).
            val (bS2, bMsg) = Session.encryptMessage(bS1, loc)

            // 4. Alice receives bMsg, ratchets to A2.
            val (aS2, _) = Session.decryptMessage(aSess, bMsg)
            assertEquals(5L, aS2.pn)

            // 5. Alice sends a message in new epoch (A2).
            val (_, m1) = Session.encryptMessage(aS2, loc)

            // 6. Decrypt and check pn. Bob must use bS2 because it has his new DH key B1.
            val (_, dec) = Session.decryptMessage(bS2, m1)
            assertEquals(5L, dec.pn, "Plaintext pn should be injected by encryptMessage")
        }
}
