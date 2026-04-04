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
 *
 * Runs on the JVM using the libsodium crypto implementation in :shared.
 *
 * Validates:
 *   - Key exchange produces matching session state (routing token, chain key) on both sides.
 *   - An encrypted location POSTed to the mailbox can be GETted and decrypted by the peer.
 *   - Routing token isolation: messages in one token are not visible via a different token.
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
        // Alice's send chain seeds Bob's receive chain, and vice versa.
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
        assertEquals(0, aliceSession.epoch)
        assertEquals(0, bobSession.epoch)
    }

    // -----------------------------------------------------------------------
    // Full E2EE flow through the Ktor test server
    // -----------------------------------------------------------------------

    @Test
    fun `full flow - key exchange then encrypt location through mailbox then decrypt`() =
        testApplication {
            application { module(ServerState()) }

            // Key exchange
            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val token = aliceSession.sendToken.toHex()

            // Alice encrypts a location update
            val location = LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_000L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceSession.aliceFp, aliceSession.bobFp)

            // Alice posts the ciphertext to Bob's mailbox
            val payload: MailboxPayload =
                EncryptedLocationPayload(
                    v = 1,
                    epoch = newAliceState.epoch,
                    seq = newAliceState.sendSeq.toString(),
                    ct = ct,
                )
            val postResp =
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString<MailboxPayload>(payload))
                }
            assertEquals(HttpStatusCode.NoContent, postResp.status)

            // Bob fetches the mailbox
            val getResp = client.get("/inbox/$token")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val arr = json.decodeFromString<JsonArray>(getResp.bodyAsText())
            assertEquals(1, arr.size, "Expected exactly one message in mailbox")

            // Bob decodes and decrypts
            val received = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedLocationPayload>(received)
            val decryptResult =
                Session.decryptLocation(
                    bobSession,
                    received.ct,
                    received.seqAsLong(),
                    bobSession.aliceFp,
                    bobSession.bobFp,
                )

            assertNotNull(decryptResult, "Decryption must succeed")
            val (_, decrypted) = decryptResult
            assertEquals(location.lat, decrypted.lat, 1e-9)
            assertEquals(location.lng, decrypted.lng, 1e-9)
            assertEquals(location.acc, decrypted.acc, 1e-9)
            assertEquals(location.ts, decrypted.ts)
        }

    @Test
    fun `multiple sequential location updates are decryptable in order`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobState) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceState0 = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            val token = aliceState0.sendToken.toHex()

            val locations =
                listOf(
                    LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_001L),
                    LocationPlaintext(lat = 37.7750, lng = -122.4195, acc = 8.0, ts = 1_700_000_031L),
                    LocationPlaintext(lat = 37.7751, lng = -122.4196, acc = 6.0, ts = 1_700_000_061L),
                )

            // Alice encrypts and posts all three
            var aliceState = aliceState0
            for (loc in locations) {
                val (nextState, ct) = Session.encryptLocation(aliceState, loc, aliceState.aliceFp, aliceState.bobFp)
                aliceState = nextState
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString<MailboxPayload>(
                            EncryptedLocationPayload(1, nextState.epoch, nextState.sendSeq.toString(), ct),
                        ),
                    )
                }
            }

            // Bob retrieves and decrypts all three
            val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(3, arr.size)

            var bobStateNow = bobState
            for ((i, el) in arr.withIndex()) {
                val msg = json.decodeFromJsonElement<MailboxPayload>(el)
                assertIs<EncryptedLocationPayload>(msg)
                val (newBobState, decrypted) =
                    Session.decryptLocation(
                        bobStateNow, msg.ct, msg.seqAsLong(), bobStateNow.aliceFp, bobStateNow.bobFp,
                    ) ?: fail("Decryption failed for message $i")
                bobStateNow = newBobState
                assertEquals(locations[i].lat, decrypted.lat, 1e-9)
                assertEquals(locations[i].ts, decrypted.ts)
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
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceSession.aliceFp, aliceSession.bobFp)

            // Flip a bit in the GCM tag to simulate in-transit corruption
            val tampered = ct.copyOf()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(1, newAliceState.epoch, newAliceState.sendSeq.toString(), tampered),
                    ),
                )
            }

            val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, arr.size)
            val msg = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedLocationPayload>(msg)

            val threw =
                try {
                    Session.decryptLocation(bobSession, msg.ct, msg.seqAsLong(), bobSession.aliceFp, bobSession.bobFp)
                    false
                } catch (_: Exception) {
                    true
                }
            assertTrue(threw, "Decrypting a tampered ciphertext must throw")
        }

    // -----------------------------------------------------------------------
    // Epoch rotation
    // -----------------------------------------------------------------------

    @Test
    fun `epoch rotation - token changes, old and new tokens are independently decryptable`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobState0) = KeyExchange.bobProcessQr(qr, "Bob")
            var aliceState = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            var bobState = bobState0

            val oldToken = aliceState.sendToken.toHex()

            // Alice sends one message in epoch 0 to the old token
            val loc0 = LocationPlaintext(lat = 51.5, lng = -0.1, acc = 5.0, ts = 1000L)
            val (aliceState1, ct0) = Session.encryptLocation(aliceState, loc0, aliceState.aliceFp, aliceState.bobFp)
            aliceState = aliceState1
            client.post("/inbox/$oldToken") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(
                            1,
                            aliceState.epoch,
                            aliceState.sendSeq.toString(),
                            ct0,
                        ),
                    ),
                )
            }

            // Alice rotates epoch (consumes a fresh Bob OPK keypair)
            val aliceNewEk = generateX25519KeyPair()
            val bobOpk = generateX25519KeyPair()
            aliceState =
                Session.aliceEpochRotation(
                    aliceState,
                    aliceNewEk.priv,
                    aliceNewEk.pub,
                    bobOpk.pub,
                    aliceState.aliceFp,
                    aliceState.bobFp,
                )
            val newToken = aliceState.sendToken.toHex()
            assertNotEquals(oldToken, newToken, "Routing token must change after epoch rotation")

            // Alice sends one message in epoch 1 to the new token
            val loc1 = LocationPlaintext(lat = 52.0, lng = -0.2, acc = 3.0, ts = 2000L)
            val (aliceState2, ct1) = Session.encryptLocation(aliceState, loc1, aliceState.aliceFp, aliceState.bobFp)
            client.post("/inbox/$newToken") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(
                            1,
                            aliceState2.epoch,
                            aliceState2.sendSeq.toString(),
                            ct1,
                        ),
                    ),
                )
            }

            // Bob polls old token and decrypts the epoch-0 message with his pre-rotation state
            val oldArr = json.decodeFromString<JsonArray>(client.get("/inbox/$oldToken").bodyAsText())
            assertEquals(1, oldArr.size, "Old token should have one epoch-0 message")
            val msg0 = json.decodeFromJsonElement<MailboxPayload>(oldArr[0])
            assertIs<EncryptedLocationPayload>(msg0)
            val (bobState1, decrypted0) =
                Session.decryptLocation(
                    bobState,
                    msg0.ct,
                    msg0.seqAsLong(),
                    bobState.aliceFp,
                    bobState.bobFp,
                )
            bobState = bobState1
            assertEquals(loc0.lat, decrypted0.lat, 1e-9)
            assertEquals(loc0.ts, decrypted0.ts)

            // Bob applies epoch rotation to advance to epoch 1
            bobState =
                Session.bobProcessAliceRotation(
                    bobState,
                    aliceNewEk.pub,
                    bobOpk.priv,
                    1,
                    bobState.aliceFp,
                    bobState.bobFp,
                )
            assertEquals(1, bobState.epoch)

            // Bob polls new token and decrypts the epoch-1 message
            val newArr = json.decodeFromString<JsonArray>(client.get("/inbox/$newToken").bodyAsText())
            assertEquals(1, newArr.size, "New token should have one epoch-1 message")
            val msg1 = json.decodeFromJsonElement<MailboxPayload>(newArr[0])
            assertIs<EncryptedLocationPayload>(msg1)
            val (_, decrypted1) =
                Session.decryptLocation(
                    bobState,
                    msg1.ct,
                    msg1.seqAsLong(),
                    bobState.aliceFp,
                    bobState.bobFp,
                )
            assertEquals(loc1.lat, decrypted1.lat, 1e-9)
            assertEquals(loc1.ts, decrypted1.ts)
        }

    @Test
    fun `epoch rotation ordering - old-epoch messages must be decrypted with pre-rotation session`() {
        // Demonstrates the ordering requirement in the poll loop: when a poll batch from
        // the old token contains both EncryptedLocationPayloads (epoch N) and an
        // EpochRotation, the location messages must be decrypted BEFORE the session is
        // advanced to epoch N+1. Decrypting an epoch-N message with the epoch-N+1
        // session will fail because the recv chain key has been replaced.
        val (qr, aliceEkPriv) =
            KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, bobState0) =
            KeyExchange.bobProcessQr(qr, "Bob")
        var aliceState =
            KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

        // Alice encrypts a location in epoch 0
        val location = LocationPlaintext(lat = 1.0, lng = 2.0, acc = 1.0, ts = 1000L)
        val (aliceState1, ct) = Session.encryptLocation(aliceState, location, aliceState.aliceFp, aliceState.bobFp)

        // Bob advances to epoch 1 (simulating having processed an EpochRotation)
        val aliceNewEk = generateX25519KeyPair()
        val bobOpk = generateX25519KeyPair()
        val bobStatePostRotation =
            Session.bobProcessAliceRotation(
                bobState0,
                aliceNewEk.pub,
                bobOpk.priv,
                1,
                bobState0.aliceFp,
                bobState0.bobFp,
            )

        // Correct: decrypt epoch-0 message with the epoch-0 (pre-rotation) state
        val (_, decrypted) =
            Session.decryptLocation(
                bobState0,
                ct,
                aliceState1.sendSeq,
                bobState0.aliceFp,
                bobState0.bobFp,
            )
        assertEquals(location.lat, decrypted.lat, 1e-9)
        assertEquals(location.ts, decrypted.ts)

        // Wrong: decrypt epoch-0 message with the epoch-1 (post-rotation) state — must fail
        val threw =
            try {
                Session.decryptLocation(
                    bobStatePostRotation,
                    ct,
                    aliceState1.sendSeq,
                    bobStatePostRotation.aliceFp,
                    bobStatePostRotation.bobFp,
                )
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(
            threw,
            "Decrypting an epoch-0 message with post-rotation state must throw",
        )
    }

    @Test
    fun `routing token isolation - wrong token mailbox is empty`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val location = LocationPlaintext(lat = 48.8566, lng = 2.3522, acc = 15.0, ts = 1_700_000_003L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceSession.aliceFp, aliceSession.bobFp)

            val correctToken = aliceSession.sendToken.toHex()
            val wrongToken = "ffffffffffffffffffffffffffffffff"

            client.post("/inbox/$correctToken") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(1, newAliceState.epoch, newAliceState.sendSeq.toString(), ct),
                    ),
                )
            }

            val wrongArr = json.decodeFromString<JsonArray>(client.get("/inbox/$wrongToken").bodyAsText())
            assertTrue(wrongArr.isEmpty(), "Wrong token must see no messages")

            val correctArr = json.decodeFromString<JsonArray>(client.get("/inbox/$correctToken").bodyAsText())
            assertEquals(1, correctArr.size, "Correct token must see the message")
        }
}
