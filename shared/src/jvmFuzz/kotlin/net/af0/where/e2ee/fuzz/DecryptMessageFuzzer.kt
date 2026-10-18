package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.DhPublicKey
import net.af0.where.e2ee.EncryptedMessagePayload
import net.af0.where.e2ee.ReceivedMessage
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.SystemRng
import net.af0.where.e2ee.WhereException

/**
 * Session.decryptMessage against a fixed, valid receive state ([SessionFixture.bob]) with an
 * envelope that is raw bytes, a genuine frame with bytes flipped, an authenticated frame with an
 * arbitrary plaintext, or a genuine frame with a fuzzer-chosen pre-decrypted header. Whatever the
 * outcome, the input state is untouched, every failure is a typed [WhereException], and only a
 * frame's true header (as far as the body authenticates it) can deliver it.
 */
object DecryptMessageFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val bob = SessionFixture.bob
        val input = FuzzInput(data)
        val mode = input.byte() % 4
        val pick = input.byte() % SessionFixture.frames.size
        val genuine = SessionFixture.frames[pick]
        var header: Session.DecryptedHeader? = null
        val frame =
            when (mode) {
                0 -> EncryptedMessagePayload(envelope = input.sized(255), ct = input.rest())
                1 -> {
                    val envelope = genuine.envelope.copyOf()
                    val ct = genuine.ct.copyOf()
                    repeat(1 + input.byte() % 4) {
                        val target = if (input.byte() % 2 == 0) envelope else ct
                        val at = input.byte() % target.size
                        target[at] = (target[at].toInt() xor maxOf(1, input.byte())).toByte()
                    }
                    EncryptedMessagePayload(envelope = envelope, ct = ct.copyOf(ct.size - input.byte() % 3))
                }
                2 -> Session.encryptPadded(SessionFixture.aliceFirstEpoch, input.sized(600), SystemRng).payload
                else -> {
                    val real = SessionFixture.headers[pick]
                    header =
                        Session.DecryptedHeader(
                            dhPub = pickKey(input, real.dhPub),
                            ackRemoteDhPub = pickKey(input, real.ackRemoteDhPub),
                            seq = if (input.byte() % 2 == 0) real.seq + (input.byte() - 128) else input.long(),
                            pn = if (input.byte() % 2 == 0) real.pn + (input.byte() - 128) else input.long(),
                        )
                    genuine
                }
            }

        val result = Session.decryptMessage(bob, frame, header)
        fuzzCheck(bob == SessionFixture.bobSnapshot) { "decryptMessage modified its input state" }
        val cause =
            when (result) {
                is ReceivedMessage.Delivered -> null
                is ReceivedMessage.Undeliverable -> result.cause
                is ReceivedMessage.Replay -> result.cause
                is ReceivedMessage.Rejected -> result.cause
            }
        fuzzCheck(cause == null || cause is WhereException) { "untyped failure: $cause" }
        when (mode) {
            // An authenticated frame always advances the ratchet, delivered or not.
            2 ->
                fuzzCheck(
                    result is ReceivedMessage.Delivered || result is ReceivedMessage.Undeliverable,
                ) { "authenticated frame: $result" }
            // The body's AAD binds seq and both DH keys, so only those can't be forged here. pn is
            // authenticated by the header AEAD alone, which a pre-decrypted header skips.
            3 ->
                if (result is ReceivedMessage.Delivered) {
                    val real = SessionFixture.headers[pick]
                    fuzzCheck(header?.copy(pn = real.pn) == real) { "delivered under a forged header $header" }
                }
        }
    }

    /** The real key, another fixture key, or arbitrary bytes (low-order points included). */
    private fun pickKey(
        input: FuzzInput,
        real: DhPublicKey,
    ): DhPublicKey =
        when (input.byte() % 3) {
            0 -> real
            1 -> SessionFixture.headers[input.byte() % SessionFixture.headers.size].dhPub
            else -> DhPublicKey(input.fixed(32))
        }
}
