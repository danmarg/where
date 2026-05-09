package net.af0.where.e2ee

import kotlin.test.*

class PruningReproTest {
    init {
        initializeE2eeTests()
    }

    data class ExchangeResult(
        val aliceSession: SessionState,
        val bobSession: SessionState,
    )

    private fun exchangeKeys(): ExchangeResult {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        return ExchangeResult(aliceSession, bobSession)
    }

    @Test
    fun testPruningAggression() {
        var (aSess, bSess) = exchangeKeys()
        val loc = MessagePlaintext.Location(0.0, 0.0, 0.0, 0L)

        // Epoch 1 (Alice's initial E1)
        val e1Hex = aSess.localDhPub.toHex()

        // Alice sends M1_1 (skipped), then M1_2 (received).
        val (aS1_1, m1_1) = Session.encryptMessage(aSess, loc)
        val (aS1_2, m1_2) = Session.encryptMessage(aS1_1, loc)
        val (bS1_2, _) = Session.decryptMessage(bSess, m1_2)
        aSess = aS1_2
        bSess = bS1_2

        assertTrue(bSess.skippedMessageKeys.containsKey("$e1Hex:1"), "Bob should have skipped key for E1:1")

        // Alice ratchets to Epoch 2.
        // Bob must send a message for Alice to ratchet.
        val (bS_to_a1, bMsg1) = Session.encryptMessage(bSess, loc)
        val (aS2_pre, _) = Session.decryptMessage(aSess, bMsg1)
        // Alice sends M2_2 (E2), Bob receives.
        val (aS2_1, m2_1) = Session.encryptMessage(aS2_pre, loc)
        val (aS2_2, m2_2) = Session.encryptMessage(aS2_1, loc)

        val e2Hex = aS2_2.localDhPub.toHex()
        assertNotEquals(e1Hex, e2Hex)

        val (bS2_2, _) = Session.decryptMessage(bS_to_a1, m2_2)
        aSess = aS2_2
        bSess = bS2_2

        assertTrue(bSess.skippedMessageKeys.containsKey("$e1Hex:1"), "Bob should still have E1:1 after ratcheting to E2")
        assertTrue(bSess.skippedMessageKeys.containsKey("$e2Hex:1"), "Bob should have skipped key for E2:1")

        // Alice ratchets to Epoch 3.
        val (bS_to_a2, bMsg2) = Session.encryptMessage(bSess, loc)
        val (aS3_pre, _) = Session.decryptMessage(aSess, bMsg2)
        // Alice sends M3_2 (E3), Bob receives.
        val (aS3_1, m3_1) = Session.encryptMessage(aS3_pre, loc)
        val (aS3_2, m3_2) = Session.encryptMessage(aS3_1, loc)

        val e3Hex = aS3_2.localDhPub.toHex()
        assertNotEquals(e2Hex, e3Hex)

        val (bS3_2, _) = Session.decryptMessage(bS_to_a2, m3_2)
        aSess = aS3_2
        bSess = bS3_2

        // This used to fail with aggressive pruning
        assertTrue(bSess.skippedMessageKeys.containsKey("$e1Hex:1"), "Bob should still have E1:1 after ratcheting to E3")
    }
}
