package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ReceiveRatchetFailureTest {
    init {
        initializeE2eeTests()
    }

    /**
     * Issue #2: a malicious server bit-flips the body of a genuine message.
     * The header still authenticates (header keys are secret), so decryptMessage
     * advances the receive ratchet (per §5.5 — to prevent permanent DH desync).
     * Without the seq-key cache, when the clean original later arrives in the
     * same batch, it is `seq <= recvSeq` → ReplayException and permanently lost.
     *
     * This test exercises the within-batch case (Bob's pre-decrypted header is
     * reused across both decryption attempts, matching E2eeProtocol.decryptBatch).
     */
    @Test
    fun cleanCopyDecryptsAfterTamperedAdvanceCachesSeqKey() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        val (_, original) = Session.encryptMessage(
            aliceSession,
            MessagePlaintext.Location(1.0, 2.0, 3.0, 4L),
        )

        // Pre-decrypt the header once (matching the batch path). Both the
        // tampered and the clean copy share the same envelope, so this header
        // applies to both.
        val sessionAad = bobSession.aliceFp + bobSession.bobFp
        val header = try {
            Session.decryptHeader(bobSession.headerKey, original.envelope, sessionAad)
        } catch (_: Exception) {
            Session.decryptHeader(bobSession.nextHeaderKey, original.envelope, sessionAad)
        }

        // Simulate a malicious server bit-flipping the body.
        val tampered = original.copy(
            ct = original.ct.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 0xFF).toByte() },
        )

        val bobAfterFailure: SessionState = try {
            Session.decryptMessage(bobSession, tampered, header)
            fail("Expected DecryptionExceptionWithState on tampered body")
        } catch (e: DecryptionExceptionWithState) {
            e.newState
        }

        assertTrue(bobAfterFailure.recvSeq >= 1, "recvSeq should have advanced past the failed message")

        // The clean original now arrives. The cached seq key must rescue it
        // from the recvSeq replay rejection.
        val (bobFinal, plaintext) = Session.decryptMessage(bobAfterFailure, original, header)
        assertIs<MessagePlaintext.Location>(plaintext)
        assertEquals(1.0, plaintext.lat)
        assertEquals(2.0, plaintext.lng)
        assertEquals(4L, plaintext.ts)

        // Cache entry for this seq was consumed by the successful decryption.
        assertTrue(
            bobFinal.skippedMessageKeys.keys.none { it.endsWith(":${header.seq}") },
            "seq=${header.seq} cache entry should have been consumed",
        )
    }
}
