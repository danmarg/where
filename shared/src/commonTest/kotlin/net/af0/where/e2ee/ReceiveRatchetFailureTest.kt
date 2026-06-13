package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ReceiveRatchetFailureTest {
    init {
        initializeE2eeTests()
    }

    /**
     * A body-AEAD failure after a valid header must advance recvSeq and the chain key
     * to prevent permanent DH desync (§8.3.1(4)). The failed message's key must NOT
     * be cached — there is no robustness benefit to caching it, since a server willing
     * to deliver a corrupted copy can equally just drop the message.
     */
    @Test
    fun bodyFailAdvancesStateWithoutCachingSeqKey() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        val (_, original) = Session.encryptMessage(
            aliceSession,
            MessagePlaintext.Location(1.0, 2.0, 3.0, 4L),
        )

        val sessionAad = bobSession.aliceFp + bobSession.bobFp
        val header = try {
            Session.decryptHeader(bobSession.headerKey, original.envelope, sessionAad)
        } catch (_: Exception) {
            Session.decryptHeader(bobSession.nextHeaderKey, original.envelope, sessionAad)
        }

        val tampered = original.copy(
            ct = original.ct.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 0xFF).toByte() },
        )

        val bobAfterFailure: SessionState = try {
            Session.decryptMessage(bobSession, tampered, header)
            fail("Expected DecryptionExceptionWithState on tampered body")
        } catch (e: DecryptionExceptionWithState) {
            e.newState
        }

        // recvSeq must advance so the ratchet state stays consistent.
        assertTrue(bobAfterFailure.recvSeq >= 1, "recvSeq should have advanced past the failed message")

        // The seq key must NOT be cached — the message is lost, equivalent to a drop.
        assertFalse(
            bobAfterFailure.skippedMessageKeys.keys.any { it.endsWith(":${header.seq}") },
            "seq=${header.seq} key must not be cached after body-fail",
        )

        // A subsequent attempt to decrypt the (uncorrupted) original is rejected as a replay.
        assertFailsWith<ReplayException> {
            Session.decryptMessage(bobAfterFailure, original, header)
        }
    }
}
