package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.decodeMailboxPayload
import net.af0.where.e2ee.decodeMailboxPayloads
import net.af0.where.e2ee.mailboxJson

/** decodeMailboxPayload on a payload as the server returns it, alone and as a poll response. */
object MailboxPayloadFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        Libsodium.ensure() // a decoded frame's default msgId is sha256(ct)
        val text = data.decodeToString()
        // The poll response LocationClient actually decodes: an array of payloads.
        refusedOnlyWithWhereException { decodeMailboxPayloads("[$text]") }
        val payload = refusedOnlyWithWhereException { decodeMailboxPayload(text) } ?: return
        // A decoded payload re-encodes to JSON that decodes to the same thing.
        val encoded = mailboxJson.encodeToString(MailboxPayload.serializer(), payload)
        val again = mailboxJson.encodeToString(MailboxPayload.serializer(), decodeMailboxPayload(encoded))
        fuzzCheck(again == encoded) { "round trip changed $encoded to $again" }
    }
}
