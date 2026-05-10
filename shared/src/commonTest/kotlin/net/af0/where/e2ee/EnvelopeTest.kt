package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvelopeTest {
    @Test
    fun testEnvelopeEncryptionDecryption() {
        val key = randomBytes(32)
        val dhPub = randomBytes(32)
        val ackRemoteDhPub = randomBytes(32)
        val seq = 123L
        val pn = 45L

        val envelope = Session.encryptHeader(key, dhPub, ackRemoteDhPub, seq, pn)

        // Envelope should be 12 (nonce) + 82 (plain) + 16 (tag) = 110 bytes
        assertEquals(110, envelope.size)

        val decrypted = Session.decryptHeader(key, envelope)
        assertTrue(dhPub.contentEquals(decrypted.dhPub))
        assertTrue(ackRemoteDhPub.contentEquals(decrypted.ackRemoteDhPub))
        assertEquals(seq, decrypted.seq)
        assertEquals(pn, decrypted.pn)
    }

    @Test
    fun testEnvelopeFailureWithWrongKey() {
        val key1 = randomBytes(32)
        val key2 = randomBytes(32)
        val dhPub = randomBytes(32)
        val ackRemoteDhPub = randomBytes(32)

        val envelope = Session.encryptHeader(key1, dhPub, ackRemoteDhPub, 1L, 0L)

        assertFailsWith<AuthenticationException> {
            Session.decryptHeader(key2, envelope)
        }
    }

    @Test
    fun testEnvelopeCorruption() {
        val key = randomBytes(32)
        val dhPub = randomBytes(32)
        val ackRemoteDhPub = randomBytes(32)
        val envelope = Session.encryptHeader(key, dhPub, ackRemoteDhPub, 1L, 0L)

        // Corrupt a byte in the encrypted portion
        envelope[20] = (envelope[20].toInt() xor 0xFF).toByte()

        assertFailsWith<AuthenticationException> {
            Session.decryptHeader(key, envelope)
        }
    }
}
