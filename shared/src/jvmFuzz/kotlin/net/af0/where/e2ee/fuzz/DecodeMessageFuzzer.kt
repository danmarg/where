package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.MessagePlaintext
import net.af0.where.e2ee.Session

/** Session.decodeMessage on an authenticated plaintext's bytes (after unpadding). */
object DecodeMessageFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val message = refusedOnlyWithWhereException { Session.decodeMessage(data) } ?: return
        // What we decode, we re-encode to the same message.
        val again = Session.decodeMessage(Session.encodeMessage(message))
        val same =
            when (message) {
                is MessagePlaintext.Keepalive -> again is MessagePlaintext.Keepalive
                else -> again == message
            }
        fuzzCheck(same) { "round trip changed $message to $again" }
    }
}
