package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.AuthenticationException
import net.af0.where.e2ee.HeaderKey
import net.af0.where.e2ee.PROTOCOL_VERSION
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.bytesToLong
import net.af0.where.e2ee.sealRandomNonce

/**
 * Session.decryptHeader on an envelope that is either raw bytes or a fuzzer-chosen plaintext
 * sealed under the right key, so the parsing after authentication is reached too.
 */
object DecryptHeaderFuzzer {
    private val key = HeaderKey(ByteArray(32) { it.toByte() })
    private val aad = "header-aad".encodeToByteArray()

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        Libsodium.ensure()
        val input = FuzzInput(data)
        when (input.byte() % 3) {
            0 -> refusedOnlyWithWhereException { Session.decryptHeader(key, input.rest(), aad) }
            1 -> {
                val plaintext = input.rest()
                val envelope = sealRandomNonce(key, plaintext.copyOf(), aad)
                val header = refusedOnlyWithWhereException { Session.decryptHeader(key, envelope, aad) } ?: return
                // Authenticated: the fields are exactly the plaintext's.
                fuzzCheck(plaintext.size >= 82 && plaintext[0].toInt() == PROTOCOL_VERSION) { "accepted a malformed header" }
                fuzzCheck(header.dhPub.toByteArray().contentEquals(plaintext.copyOfRange(1, 33))) { "dhPub differs" }
                fuzzCheck(header.ackRemoteDhPub.toByteArray().contentEquals(plaintext.copyOfRange(33, 65))) { "ack differs" }
                fuzzCheck(header.seq == bytesToLong(plaintext.copyOfRange(65, 73))) { "seq differs" }
                fuzzCheck(header.pn == bytesToLong(plaintext.copyOfRange(73, 81))) { "pn differs" }
            }
            else -> {
                // Sealed under one key and opened under another: never accepted.
                val envelope = sealRandomNonce(HeaderKey(ByteArray(32)), input.rest(), aad)
                try {
                    Session.decryptHeader(key, envelope, aad)
                    throw FuzzPropertyViolation("header opened under the wrong key")
                } catch (_: AuthenticationException) {
                } catch (_: net.af0.where.e2ee.ProtocolException) {
                    // Too small to be a header at all.
                }
            }
        }
    }
}
