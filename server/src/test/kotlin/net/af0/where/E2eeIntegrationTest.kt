package net.af0.where

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.af0.where.e2ee.*
import kotlin.test.*

/**
 * Integration tests for the E2EE key exchange and location-send flow, exercised against
 * the Ktor test server.
 */
class E2eeIntegrationTest {
    init {
        LibsodiumInitializer.initializeWithCallback {
            // Libsodium initialized
        }
    }

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val nextToken = ByteArray(16) { 0x11.toByte() }
    private val emptyOpkPrivGetter: (Int) -> ByteArray? = { null }

    // -----------------------------------------------------------------------
    // Key exchange correctness
    // -----------------------------------------------------------------------

    @Test
    fun `key exchange produces identical routing token and chain key on both sides`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

        assertContentEquals(
            aliceSession.sendToken,
            bobSession.recvToken,
            "Alice's send token must equal Bob's recv token",
        )
        assertContentEquals(
            aliceSession.recvToken,
            bobSession.sendToken,
            "Alice's recv token must equal Bob's send token",
        )
        assertContentEquals(
            aliceSession.sendChainKey,
            bobSession.recvChainKey,
            "Alice's send chain must equal Bob's recv chain",
        )
        assertContentEquals(
            aliceSession.recvChainKey,
            bobSession.sendChainKey,
            "Alice's recv chain must equal Bob's send chain",
        )
    }

    // -----------------------------------------------------------------------
    // Full E2EE flow through the Ktor test server
    // -----------------------------------------------------------------------

    @Test
    fun `full flow - key exchange then encrypt location through mailbox then decrypt`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val token = aliceSession.sendToken.toHex()

            val location = LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_000L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceSession.aliceFp, aliceSession.bobFp, nextToken)

            val payload: MailboxPayload =
                EncryptedLocationPayload(
                    v = 1,
                    seq = newAliceState.sendSeq.toString(),
                    ct = ct,
                )
            val postResp =
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString<MailboxPayload>(payload))
                }
            assertEquals(HttpStatusCode.NoContent, postResp.status)

            val getResp = client.get("/inbox/$token")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val arr = json.decodeFromString<JsonArray>(getResp.bodyAsText())
            assertEquals(1, arr.size, "Expected exactly one message in mailbox")

            val received = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedLocationPayload>(received)
            val decryptResult =
                Session.decryptLocation(
                    bobSession,
                    received.ct,
                    received.seqAsLong(),
                    bobSession.aliceFp,
                    bobSession.bobFp,
                    emptyOpkPrivGetter
                )

            assertNotNull(decryptResult, "Decryption must succeed")
            val (newBobSession, decrypted) = decryptResult
            assertEquals(location.lat, decrypted.lat, 1e-9)
            assertContentEquals(nextToken, newBobSession.recvToken)
        }

    @Test
    fun `multiple sequential location updates are decryptable in order`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobState0) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceState0 = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val locations =
                listOf(
                    LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_001L),
                    LocationPlaintext(lat = 37.7750, lng = -122.4195, acc = 8.0, ts = 1_700_000_031L),
                    LocationPlaintext(lat = 37.7751, lng = -122.4196, acc = 6.0, ts = 1_700_000_061L),
                )

            var aliceState = aliceState0
            var bobState = bobState0
            for (loc in locations) {
                val currentToken = aliceState.sendToken.toHex()
                val nextT = randomBytes(16)
                val (nextAlice, ct) = Session.encryptLocation(aliceState, loc, aliceState.aliceFp, aliceState.bobFp, nextT)
                aliceState = nextAlice
                client.post("/inbox/$currentToken") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString<MailboxPayload>(EncryptedLocationPayload(1, nextAlice.sendSeq.toString(), ct)))
                }

                val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$currentToken").bodyAsText())
                assertEquals(1, arr.size)
                val msg = json.decodeFromJsonElement<MailboxPayload>(arr[0])
                assertIs<EncryptedLocationPayload>(msg)
                val (newBob, decrypted) = Session.decryptLocation(bobState, msg.ct, msg.seqAsLong(), bobState.aliceFp, bobState.bobFp, emptyOpkPrivGetter)
                bobState = newBob
                assertEquals(loc.lat, decrypted.lat, 1e-9)
                assertContentEquals(nextT, bobState.recvToken)
            }
        }

    @Test
    fun `ciphertext tampered in transit fails decryption`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            val token = aliceSession.sendToken.toHex()

            val location = LocationPlaintext(lat = 51.5, lng = -0.12, acc = 5.0, ts = 1_700_000_002L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceSession.aliceFp, aliceSession.bobFp, nextToken)

            val tampered = ct.copyOf()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(1, newAliceState.sendSeq.toString(), tampered),
                    ),
                )
            }

            val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, arr.size)
            val msg = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedLocationPayload>(msg)

            val threw =
                try {
                    Session.decryptLocation(bobSession, msg.ct, msg.seqAsLong(), bobSession.aliceFp, bobSession.bobFp, emptyOpkPrivGetter)
                    false
                } catch (_: Exception) {
                    true
                }
            assertTrue(threw, "Decrypting a tampered ciphertext must throw")
        }

    @Test
    fun `routing token isolation - wrong token mailbox is empty`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val location = LocationPlaintext(lat = 48.8566, lng = 2.3522, acc = 15.0, ts = 1_700_000_003L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceSession.aliceFp, aliceSession.bobFp, nextToken)

            val correctToken = aliceSession.sendToken.toHex()
            val wrongToken = "ffffffffffffffffffffffffffffffff"

            client.post("/inbox/$correctToken") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(1, newAliceState.sendSeq.toString(), ct),
                    ),
                )
            }

            val wrongArr = json.decodeFromString<JsonArray>(client.get("/inbox/$wrongToken").bodyAsText())
            assertTrue(wrongArr.isEmpty(), "Wrong token must see no messages")

            val correctArr = json.decodeFromString<JsonArray>(client.get("/inbox/$correctToken").bodyAsText())
            assertEquals(1, correctArr.size, "Correct token must see the message")
        }
}
