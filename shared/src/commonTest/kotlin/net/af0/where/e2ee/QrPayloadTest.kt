package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class QrPayloadTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun `toUrl produces where scheme`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        val url = qr.toUrl()
        assertTrue(url.startsWith("where://invite?q="), "URL should start with where:// scheme, got: $url")
    }

    @Test
    fun `fromUrl decodes where scheme`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        val url = qr.toUrl()
        val decoded = QrPayload.fromUrl(url)
        assertNotNull(decoded)
        assertEquals(qr, decoded)
    }

    @Test
    fun `fromUrl decodes legacy https scheme`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")

        // Manually construct legacy URL
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(QrPayload.serializer(), qr)
        val encoded = kotlin.io.encoding.Base64.UrlSafe.encode(jsonStr.encodeToByteArray())
        val legacyUrl = "https://where.af0.net/invite#$encoded"

        val decoded = QrPayload.fromUrl(legacyUrl)
        assertNotNull(decoded)
        assertEquals(qr, decoded)
    }

    private fun assertTrue(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
