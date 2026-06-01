package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.fail

/**
 * Issue #4: only the current and next receive header keys are retained, so
 * a previous-epoch straggler that arrives in a *separate* poll (after Bob
 * has already ratcheted past that epoch) fails `tryDecryptHeader` and is
 * dropped.
 *
 * Within-batch reordering is fine because `decryptAndSort` orders
 * previous-epoch messages before current-epoch ones. This test specifically
 * exercises the across-poll case the existing tests don't cover.
 */
class PreviousEpochStragglerTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun previousEpochStragglerAcrossPollsIsDropped() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bob0) = KeyExchange.bobProcessQr(qr, "Bob")
        val alice0 = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // Alice epoch 1: send two messages back-to-back. msgA2 is the straggler
        // we want to deliver across a later poll.
        val (alice1, msgA1) = Session.encryptMessage(alice0, MessagePlaintext.Location(1.0, 1.0, 1.0, 1L))
        val (alice2, msgA2) = Session.encryptMessage(alice1, MessagePlaintext.Location(2.0, 2.0, 2.0, 2L))

        // Bob receives msgA1 and ratchets.
        val (bob1, _) = Session.decryptMessage(bob0, msgA1)

        // Bob → Alice round trip so Alice ratchets to her next epoch.
        val (_bob2, msgB1) = Session.encryptMessage(bob1, MessagePlaintext.Location(0.0, 0.0, 0.0, 0L))
        val (alice3, _) = Session.decryptMessage(alice2, msgB1)

        // Alice now sends in her new epoch.
        val (_alice4, msgA3) = Session.encryptMessage(alice3, MessagePlaintext.Location(3.0, 3.0, 3.0, 3L))

        // Bob receives the new-epoch message — ratchets again, retiring the
        // previous receive header key.
        val (bob3, _) = Session.decryptMessage(_bob2, msgA3)

        // Late delivery of msgA2 (Alice's previous epoch, seq=2) in a fresh poll.
        // No preDecryptedHeader — header must be decrypted by Bob's current
        // headerKey or nextHeaderKey, both of which have rotated past the
        // epoch this message was encrypted under.
        try {
            val (_, _) = Session.decryptMessage(bob3, msgA2)
            fail("Expected previous-epoch straggler to be dropped — header decryption should fail")
        } catch (_: AuthenticationException) {
            // Expected: documents the current §5.3 across-poll gap (#4).
        }
    }
}
