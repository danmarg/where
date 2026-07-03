package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression test for the cross-epoch pn gap-fill DoS (issue #324).
 *
 * A transition frame (seq=1, new DH epoch) with an inflated pn value must be
 * rejected by the admission check before any kdfCk iterations are performed.
 * Prior to the fix, the admission check capped pnGaps at MAX_SKIPPED_KEYS for
 * the projectedSize comparison but then ran the fill loop on the uncapped value,
 * allowing a paired peer to force ~500k kdfCk calls with a single frame.
 */
class PnGapCapTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun inflatedPnOnTransitionFrameThrowsProtocolGapException() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, bob0) = KeyExchange.bobProcessQr(qr, "Bob")
        val alice0 = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

        // Alice sends one message so Bob has recvSeq=1.
        val (alice1, msgA1) = Session.encryptMessage(alice0, MessagePlaintext.Location(1.0, 1.0, 1.0, 1L))
        val (bob1, _) = Session.decryptMessage(bob0, msgA1)

        // Bob sends a message; Alice receives it, triggering a DH ratchet on Alice's side.
        val (_, msgB1) = Session.encryptMessage(bob1, MessagePlaintext.Location(0.0, 0.0, 0.0, 0L))
        val (alice2, _) = Session.decryptMessage(alice1, msgB1)

        // alice2 is now in a new DH epoch with pn=1 (previous chain length).
        // Forge Alice's state to claim a previous chain of 500_001 messages.
        val alice2Forged = alice2.copy(pn = 500_001L)

        // This produces a real, otherwise-valid message in Alice's new epoch (seq=1)
        // but with pn=500_001 in the encrypted header. Bob will see isNewDhEpoch=true,
        // diff = 500_001 - 1 = 500_000 >> MAX_SKIPPED_KEYS=1000.
        val (_, forgedMsg) = Session.encryptMessage(alice2Forged, MessagePlaintext.Location(2.0, 2.0, 2.0, 2L))

        assertFailsWith<ProtocolGapException> {
            Session.decryptMessage(bob1, forgedMsg)
        }
    }
}
