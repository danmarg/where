package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format compatibility for the invite payload's optional `expires_at`.
 *
 * [LegacyQrPayload] is a frozen copy of the payload schema and decoder as shipped before
 * `expires_at` existed. Do NOT update it when QrPayload changes: it stands in for old installed apps.
 */
@OptIn(ExperimentalEncodingApi::class)
class QrPayloadCompatTest {
    init {
        initializeE2eeTests()
    }

    @Serializable
    private data class LegacyQrPayload(
        @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
        @SerialName("ek_pub")
        @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
        @SerialName("suggested_name") val suggestedName: String,
        @SerialName("discovery_secret")
        @Serializable(with = ByteArrayBase64Serializer::class) val discoverySecret: ByteArray,
    )

    private val legacyJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun b64Url(json: String) = Base64.UrlSafe.encode(json.encodeToByteArray())

    private fun httpsUrl(json: String) = "https://where.af0.net/invite#${b64Url(json)}"

    private fun schemeUrl(json: String) = "where://invite?q=${b64Url(json)}"

    private fun fragmentOf(url: String) =
        if (url.startsWith("where://")) url.substringAfter("q=").substringBefore("&") else url.substringAfter("#")

    private fun legacyDecode(url: String): LegacyQrPayload =
        legacyJson.decodeFromString(
            LegacyQrPayload.serializer(),
            Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(fragmentOf(url)).decodeToString(),
        )

    private fun legacyUrlFor(qr: QrPayload) =
        httpsUrl(
            legacyJson.encodeToString(
                LegacyQrPayload.serializer(),
                LegacyQrPayload(qr.protocolVersion, qr.ekPub, qr.suggestedName, qr.discoverySecret),
            ),
        )

    // 32 bytes of 0x01 / 0x02, base64.
    private val ek = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val secret = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="

    // ---- new client, old invite ----------------------------------------------------------------

    @Test
    fun oldFormatLiteral_parsesAndNeverExpires() {
        val json = """{"protocol_version":1,"ek_pub":"$ek","suggested_name":"Alice","discovery_secret":"$secret"}"""
        for (url in listOf(httpsUrl(json), schemeUrl(json))) {
            val qr = assertNotNull(QrPayload.fromUrl(url), "old-format invite must still parse: $url")
            assertEquals("Alice", qr.suggestedName)
            assertContentEquals(ByteArray(32) { 1 }, qr.ekPub)
            assertContentEquals(ByteArray(32) { 2 }, qr.discoverySecret)
            assertNull(qr.expiresAt)
            assertFalse(qr.isExpired(0L))
            assertFalse(qr.isExpired(Long.MAX_VALUE), "no expires_at means no expiry, however old")
        }
    }

    @Test
    fun oldFormatMissingProtocolVersion_stillParses() {
        // protocol_version has always had a default; keep accepting payloads that omit it.
        val qr = QrPayload.fromUrl(httpsUrl("""{"ek_pub":"$ek","suggested_name":"A","discovery_secret":"$secret"}"""))
        assertNotNull(qr)
        assertEquals(PROTOCOL_VERSION, qr.protocolVersion)
    }

    @Test
    fun oldFormatInvite_pairsEndToEnd() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val created = alice.createInvite("Alice")

            // What an old client would have put in the QR: no expires_at.
            val scanned = assertNotNull(QrPayload.fromUrl(legacyUrlFor(created)))
            assertNull(scanned.expiresAt)

            val (init, bobEntry) = bob.processScannedQr(scanned, "Bob")
            val aliceEntry = assertNotNull(alice.processKeyExchangeInit(init, "Bob", created.ekPub))
            assertContentEquals(bobEntry.session.aliceEkPub, aliceEntry.session.aliceEkPub)
            assertContentEquals(bobEntry.session.bobEkPub, aliceEntry.session.bobEkPub)
        }

    @Test
    fun newFormatInvite_pairsEndToEnd() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val created = alice.createInvite("Alice")
            assertNotNull(created.expiresAt)

            val scanned = assertNotNull(QrPayload.fromUrl(created.toUrl()))
            assertEquals(created, scanned)

            val (init, bobEntry) = bob.processScannedQr(scanned, "Bob")
            val aliceEntry = assertNotNull(alice.processKeyExchangeInit(init, "Bob", created.ekPub))
            assertContentEquals(bobEntry.session.aliceEkPub, aliceEntry.session.aliceEkPub)
            assertContentEquals(bobEntry.session.bobEkPub, aliceEntry.session.bobEkPub)
        }

    // ---- old client, new invite ----------------------------------------------------------------

    @Test
    fun newFormatInvite_decodesInLegacyClient() {
        val qr =
            QrPayload(
                ekPub = ByteArray(32) { 1 },
                suggestedName = "Alice",
                discoverySecret = ByteArray(32) { 2 },
                expiresAt = 1_800_000_000L,
            )
        val json = legacyJson.encodeToString(QrPayload.serializer(), qr)
        assertTrue("expires_at" in json, "the new field must actually be on the wire: $json")

        for (url in listOf(qr.toUrl(), schemeUrl(json))) {
            val old = legacyDecode(url)
            assertEquals("Alice", old.suggestedName)
            assertContentEquals(qr.ekPub, old.ekPub)
            assertContentEquals(qr.discoverySecret, old.discoverySecret)
            assertEquals(qr.protocolVersion, old.protocolVersion)
        }
    }

    @Test
    fun newFormatInvite_discoveryTokenIsUnchangedByExpiry() {
        val base = QrPayload(ekPub = ByteArray(32) { 1 }, suggestedName = "Alice", discoverySecret = ByteArray(32) { 2 })
        assertContentEquals(base.discoveryToken(), base.copy(expiresAt = 123L).discoveryToken())
    }

    // ---- format ---------------------------------------------------------------------------------

    @Test
    fun roundTrip_preservesExpiresAt() {
        val qr =
            QrPayload(
                ekPub = ByteArray(32) { 1 },
                suggestedName = "Alice",
                discoverySecret = ByteArray(32) { 2 },
                expiresAt = 1_800_000_000L,
            )
        val back = assertNotNull(QrPayload.fromUrl(qr.toUrl()))
        assertEquals(1_800_000_000L, back.expiresAt)
        assertEquals(qr, back)
        assertEquals(qr.hashCode(), back.hashCode())
        assertTrue(qr != qr.copy(expiresAt = 1_800_000_001L))
        assertTrue(qr != qr.copy(expiresAt = null))
    }

    @Test
    fun expiresAt_addsLittleToTheLink() {
        val withoutField = QrPayload(ekPub = ByteArray(32) { 1 }, suggestedName = "Alice", discoverySecret = ByteArray(32) { 2 })
        val withField = withoutField.copy(expiresAt = 1_800_000_000L)
        val extra = withField.toUrl().length - withoutField.toUrl().length
        assertTrue(extra in 1..40, "expires_at should add a few dozen characters at most, added $extra")
    }

    @Test
    fun wrongTypedExpiresAt_isRejectedAsInvalid() {
        // Documented behaviour: a non-numeric expires_at is a malformed invite, not "no expiry".
        val json = """{"protocol_version":1,"ek_pub":"$ek","suggested_name":"A","discovery_secret":"$secret","expires_at":"soon"}"""
        assertNull(QrPayload.fromUrl(httpsUrl(json)))
    }

    @Test
    fun unknownFutureFields_areStillIgnored() {
        val json =
            """{"protocol_version":1,"ek_pub":"$ek","suggested_name":"A","discovery_secret":"$secret",""" +
                """"expires_at":5,"something_new":{"x":1}}"""
        assertNotNull(QrPayload.fromUrl(httpsUrl(json)))
    }

    // ---- expiry semantics -----------------------------------------------------------------------

    @Test
    fun isExpired_boundaries() {
        val exp = 1_000_000L
        val qr = QrPayload(ekPub = ByteArray(32), suggestedName = "A", discoverySecret = ByteArray(32), expiresAt = exp)
        assertFalse(qr.isExpired(exp - 1))
        assertFalse(qr.isExpired(exp))
        assertFalse(qr.isExpired(exp + INVITE_EXPIRY_GRACE_SECONDS), "accepted up to the grace window")
        assertTrue(qr.isExpired(exp + INVITE_EXPIRY_GRACE_SECONDS + 1))
        assertTrue(qr.copy(expiresAt = 0L).isExpired(INVITE_EXPIRY_GRACE_SECONDS + 1))
        assertTrue(qr.copy(expiresAt = -5L).isExpired(INVITE_EXPIRY_GRACE_SECONDS))
        assertFalse(qr.copy(expiresAt = Long.MAX_VALUE - INVITE_EXPIRY_GRACE_SECONDS).isExpired(Long.MAX_VALUE - 1))
    }

    @Test
    fun scanner_rejectsExpiredInvite_andAcceptsWithinGrace() =
        runTest {
            val bob = testE2eeManager(createTestSqlDriver())
            val now = currentTimeSeconds()
            val valid = testE2eeManager(createTestSqlDriver()).createInvite("Alice")

            val expired = valid.copy(expiresAt = now - INVITE_EXPIRY_GRACE_SECONDS - 60)
            assertFailsWith<InviteExpiredException> { bob.processScannedQr(expired, "Bob") }
            assertFailsWith<InviteExpiredException> { KeyExchange.bobProcessQr(expired, "Bob") }

            val withinGrace = valid.copy(expiresAt = now - 30)
            assertNotNull(bob.processScannedQr(withinGrace, "Bob"))
        }

    @Test
    fun newInvite_expiresInTheAdvertisedLifetime() =
        runTest {
            val before = currentTimeSeconds()
            val qr = testE2eeManager(createTestSqlDriver()).createInvite("Alice")
            val after = currentTimeSeconds()
            val exp = assertNotNull(qr.expiresAt)
            assertTrue(exp in (before + INVITE_LIFETIME_SECONDS)..(after + INVITE_LIFETIME_SECONDS))
        }

    // ---- inviter retention ----------------------------------------------------------------------

    private class MutableClock(var seconds: Long) : TimeProvider {
        override fun currentTimeSeconds() = seconds

        override fun currentTimeMillis() = seconds * 1000

        override fun formatLocalTime(seconds: Long) = platformFormatLocalTime(seconds)
    }

    @Test
    fun inviter_keepsInviteForAsLongAsAScannerWouldAccept() =
        runTest {
            val clock = MutableClock(1_800_000_000L)
            TimeSource.setProvider(clock)
            try {
                val alice = testE2eeManager(createTestSqlDriver())
                val qr = alice.createInvite("Alice")
                val exp = assertNotNull(qr.expiresAt)

                // The last instant a scanner still accepts this invite...
                clock.seconds = exp + INVITE_EXPIRY_GRACE_SECONDS
                assertFalse(qr.isExpired())
                // ...the inviter must still hold the key.
                alice.cleanupExpiredInvites(INVITE_LIFETIME_SECONDS)
                assertEquals(1, alice.listPendingInvites().size, "inviter dropped an invite a scanner still accepts")

                // And it is eventually cleaned up.
                clock.seconds = exp + INVITE_EXPIRY_GRACE_SECONDS + 1
                assertTrue(qr.isExpired())
                alice.cleanupExpiredInvites(INVITE_LIFETIME_SECONDS)
                assertEquals(0, alice.listPendingInvites().size)
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    @Test
    fun sharedInvite_isRetainedFromTheTimeItWasShared() =
        runTest {
            val clock = MutableClock(1_800_000_000L)
            TimeSource.setProvider(clock)
            try {
                val alice = testE2eeManager(createTestSqlDriver())
                val qr = alice.createInvite("Alice")
                clock.seconds += 10 * 3600
                alice.markInviteExported(qr.ekPub)

                // Past the advertised expiry, but within lifetime + grace of the share: still kept.
                clock.seconds = assertNotNull(qr.expiresAt) + INVITE_EXPIRY_GRACE_SECONDS + 1
                alice.cleanupExpiredInvites(INVITE_LIFETIME_SECONDS)
                assertEquals(1, alice.listPendingInvites().size)
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    // ---- replacing the shown invite ---------------------------------------------------------------

    @Test
    fun replaceInvite_swapsAnUnsharedInviteForTheNewOne() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val first = alice.createInvite("A")
            val second = alice.replaceInvite(first.ekPub, "Al")
            val keys = alice.listPendingInvites().map { it.qrPayload.ekPub.toHex() }
            assertEquals(listOf(second.ekPub.toHex()), keys)
        }

    @Test
    fun replaceInvite_keepsAnInviteThatWasShared() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val first = alice.createInvite("A")
            alice.markInviteExported(first.ekPub)
            val second = alice.replaceInvite(first.ekPub, "Al")
            val keys = alice.listPendingInvites().map { it.qrPayload.ekPub.toHex() }.toSet()
            assertEquals(setOf(first.ekPub.toHex(), second.ekPub.toHex()), keys)
        }

    @Test
    fun repeatedNameEdits_doNotAccumulateInvites() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            var shown = alice.createInvite("A")
            repeat(15) { shown = alice.replaceInvite(shown.ekPub, "A$it") }
            assertEquals(1, alice.listPendingInvites().size)
        }
}
