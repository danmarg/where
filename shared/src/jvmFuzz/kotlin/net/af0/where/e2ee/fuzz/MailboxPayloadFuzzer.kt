package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.decodeMailboxPayload
import net.af0.where.e2ee.mailboxJson

/** decodeMailboxPayload on a payload as the server returns it. */
object MailboxPayloadFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        Libsodium.ensure() // a decoded frame's default msgId is sha256(ct)
        val payload = refusedOnlyWithWhereException { decodeMailboxPayload(data.decodeToString()) } ?: return
        // A decoded payload re-encodes to JSON that decodes to the same thing.
        val encoded = mailboxJson.encodeToString(MailboxPayload.serializer(), payload)
        val again = mailboxJson.encodeToString(MailboxPayload.serializer(), decodeMailboxPayload(encoded))
        fuzzCheck(again == encoded) { "round trip changed $encoded to $again" }
    }
}
