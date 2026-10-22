package net.af0.where.e2ee

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What an invite link can carry. The link is Base64 of UTF-8 JSON, and JSON can spell a lone UTF-16
 * surrogate ("\ud800") that UTF-8 cannot hold, so a payload parsed from a link must not keep one:
 * it would change the first time it was written back out (found by the QrPayload fuzzer).
 */
@OptIn(ExperimentalEncodingApi::class)
class QrPayloadLinkTest {
    init {
        initializeE2eeTests()
    }

    private fun linkWithName(escapedName: String): String {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        val fragment = qr.toUrl().substringAfter("#")
        val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(fragment).decodeToString()
        check("\"Alice\"" in json)
        return "https://where.af0.net/invite#" + Base64.UrlSafe.encode(json.replace("\"Alice\"", "\"$escapedName\"").encodeToByteArray())
    }

    @Test
    fun `a lone surrogate in the suggested name becomes a replacement character`() {
        // Lone high, lone low, a reversed pair, a high at the end; a real pair (emoji) is kept.
        val cases =
            listOf(
                "A\\ud800B" to "A\uFFFDB",
                "A\\udc00B" to "A\uFFFDB",
                "A\\udc00\\ud800B" to "A\uFFFD\uFFFDB",
                "A\\ud800" to "A\uFFFD",
                "A\\ud83d\\ude00B" to "A\uD83D\uDE00B",
            )
        for ((escaped, expected) in cases) {
            val parsed = assertNotNull(QrPayload.fromUrl(linkWithName(escaped)), escaped)
            assertEquals(expected, parsed.suggestedName, escaped)
            assertEquals(parsed, QrPayload.fromUrl(parsed.toUrl()), "round trip of $escaped")
        }
    }
}
