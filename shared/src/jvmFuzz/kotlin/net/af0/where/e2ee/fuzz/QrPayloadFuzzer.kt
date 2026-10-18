package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.KeyExchange
import net.af0.where.e2ee.QrPayload
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * QrPayload.fromUrl on a scanned or tapped invite link, then Bob's processing of whatever it
 * accepts. Mode 1 wraps the input as a link's Base64 fragment so the JSON layer is reached easily.
 */
@OptIn(ExperimentalEncodingApi::class)
object QrPayloadFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        Libsodium.ensure()
        val input = FuzzInput(data)
        val url =
            when (input.byte() % 3) {
                0 -> input.rest().decodeToString()
                1 -> "https://where.af0.net/invite#" + Base64.UrlSafe.encode(input.rest())
                else -> "where://invite?q=" + Base64.UrlSafe.encode(input.rest()) + "&x=1"
            }
        // fromUrl reports a bad link as null, never by throwing.
        val qr = QrPayload.fromUrl(url) ?: return
        fuzzCheck(QrPayload.fromUrl(qr.toUrl()) == qr) { "invite link round trip changed $qr" }
        refusedOnlyWithWhereException { KeyExchange.bobProcessQr(qr, "Bob") }
    }
}
