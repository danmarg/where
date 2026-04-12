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
        val seq = 123L
        val pn = 45L

        val envelope = Session.encryptHeader(key, dhPub, seq, pn)
        
        // Envelope should be 12 (nonce) + 49 (plain) + 16 (tag) = 77 bytes
        assertEquals(77, envelope.size)

        val decrypted = Session.decryptHeader(key, envelope)
        assertTrue(dhPub.contentEquals(decrypted.dhPub))
        assertEquals(seq, decrypted.seq)
        assertEquals(pn, decrypted.pn)
    }

    @Test
    fun testEnvelopeFailureWithWrongKey() {
        val key1 = randomBytes(32)
        val key2 = randomBytes(32)
        val dhPub = randomBytes(32)
        
        val envelope = Session.encryptHeader(key1, dhPub, 1L, 0L)
        
        assertFailsWith<AuthenticationException> {
            Session.decryptHeader(key2, envelope)
        }
    }

    @Test
    fun testEnvelopeCorruption() {
        val key = randomBytes(32)
        val envelope = Session.encryptHeader(key, randomBytes(32), 1L, 0L)
        
        // Corrupt a byte in the encrypted portion
        envelope[20] = (envelope[20].toInt() xor 0xFF).toByte()

        assertFailsWith<AuthenticationException> {
            Session.decryptHeader(key, envelope)
        }
    }
}
