package net.af0.where

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.af0.where.e2ee.*
import java.security.MessageDigest
import kotlin.test.*

/**
 * Integration tests for the E2EE key exchange and location-send flow, exercised against
 * the Ktor test server.
 *
 * Runs on the JVM using the BouncyCastle crypto implementation in :shared.
 *
 * Validates:
 *   - Key exchange produces matching session state (routing token, chain key) on both sides.
 *   - An encrypted location POSTed to the mailbox can be GETted and decrypted by the peer.
 *   - Routing token isolation: messages in one token are not visible via a different token.
 */
class E2eeIntegrationTest {
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

    private fun fp(
        ikPub: ByteArray,
        sigIkPub: ByteArray,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(ikPub + sigIkPub)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun generateIdentity() =
        IdentityKeys(
            ik = generateX25519KeyPair(),
            sigIk = generateEd25519KeyPair(),
        )

    // -----------------------------------------------------------------------
    // Key exchange correctness
    // -----------------------------------------------------------------------

    @Test
    fun `key exchange produces identical routing token and chain key on both sides`() {
        val alice = generateIdentity()
        val bob = generateIdentity()
        val aliceFp = fp(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fp(bob.ik.pub, bob.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(initMsg, alice, aliceEkPriv, qr.ekPub, aliceFp, bobFp)

        assertContentEquals(
            aliceSession.routingToken,
            bobSession.routingToken,
            "routing tokens must match",
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

    @Test
    fun `key exchange QR signature verification rejects tampered QR`() {
        val alice = generateIdentity()
        val bob = generateIdentity()
        val aliceFp = fp(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fp(bob.ik.pub, bob.sigIk.pub)

        val (qr, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        // Flip one byte in the signed public key to simulate tampering.
        val tamperedSigPub = qr.sigPub.copyOf()
        tamperedSigPub[0] = (tamperedSigPub[0].toInt() xor 0xFF).toByte()
        val tampered =
            QrPayload(
                ikPub = qr.ikPub,
                ekPub = qr.ekPub,
                sigPub = tamperedSigPub,
                suggestedName = qr.suggestedName,
                fingerprint = qr.fingerprint,
                sig = qr.sig,
            )

        val threw =
            try {
                KeyExchange.bobProcessQr(tampered, bob, aliceFp, bobFp)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        assertTrue(threw, "bobProcessQr must reject a tampered QR payload")
    }

    // -----------------------------------------------------------------------
    // Full E2EE flow through the Ktor test server
    // -----------------------------------------------------------------------

    @Test
    fun `full flow - key exchange then encrypt location through mailbox then decrypt`() =
        testApplication {
            application { module(ServerState()) }

            // Key exchange
            val alice = generateIdentity()
            val bob = generateIdentity()
            val aliceFp = fp(alice.ik.pub, alice.sigIk.pub)
            val bobFp = fp(bob.ik.pub, bob.sigIk.pub)

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, alice, aliceEkPriv, qr.ekPub, aliceFp, bobFp)

            val token = aliceSession.routingToken.toHex()

            // Alice encrypts a location update
            val location = LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_000L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceFp, bobFp)

            // Alice posts the ciphertext to Bob's mailbox
            val payload: MailboxPayload =
                EncryptedLocationPayload(
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
                    aliceFp,
                    bobFp,
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

            val alice = generateIdentity()
            val bob = generateIdentity()
            val aliceFp = fp(alice.ik.pub, alice.sigIk.pub)
            val bobFp = fp(bob.ik.pub, bob.sigIk.pub)

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
            val (initMsg, bobState) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
            val aliceState0 = KeyExchange.aliceProcessInit(initMsg, alice, aliceEkPriv, qr.ekPub, aliceFp, bobFp)
            val token = aliceState0.routingToken.toHex()

            val locations =
                listOf(
                    LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_001L),
                    LocationPlaintext(lat = 37.7750, lng = -122.4195, acc = 8.0, ts = 1_700_000_031L),
                    LocationPlaintext(lat = 37.7751, lng = -122.4196, acc = 6.0, ts = 1_700_000_061L),
                )

            // Alice encrypts and posts all three
            var aliceState = aliceState0
            for (loc in locations) {
                val (nextState, ct) = Session.encryptLocation(aliceState, loc, aliceFp, bobFp)
                aliceState = nextState
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString<MailboxPayload>(
                            EncryptedLocationPayload(nextState.epoch, nextState.sendSeq.toString(), ct),
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
                        bobStateNow, msg.ct, msg.seqAsLong(), aliceFp, bobFp,
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

            val alice = generateIdentity()
            val bob = generateIdentity()
            val aliceFp = fp(alice.ik.pub, alice.sigIk.pub)
            val bobFp = fp(bob.ik.pub, bob.sigIk.pub)

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, alice, aliceEkPriv, qr.ekPub, aliceFp, bobFp)
            val token = aliceSession.routingToken.toHex()

            val location = LocationPlaintext(lat = 51.5, lng = -0.12, acc = 5.0, ts = 1_700_000_002L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceFp, bobFp)

            // Flip a bit in the GCM tag to simulate in-transit corruption
            val tampered = ct.copyOf()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(newAliceState.epoch, newAliceState.sendSeq.toString(), tampered),
                    ),
                )
            }

            val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, arr.size)
            val msg = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedLocationPayload>(msg)

            val threw =
                try {
                    Session.decryptLocation(bobSession, msg.ct, msg.seqAsLong(), aliceFp, bobFp)
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

            val alice = generateIdentity()
            val bob = generateIdentity()
            val aliceFp = fp(alice.ik.pub, alice.sigIk.pub)
            val bobFp = fp(bob.ik.pub, bob.sigIk.pub)

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
            val (initMsg, _) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, alice, aliceEkPriv, qr.ekPub, aliceFp, bobFp)

            val location = LocationPlaintext(lat = 48.8566, lng = 2.3522, acc = 15.0, ts = 1_700_000_003L)
            val (newAliceState, ct) = Session.encryptLocation(aliceSession, location, aliceFp, bobFp)

            val correctToken = aliceSession.routingToken.toHex()
            val wrongToken = "ffffffffffffffffffffffffffffffff"

            client.post("/inbox/$correctToken") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString<MailboxPayload>(
                        EncryptedLocationPayload(newAliceState.epoch, newAliceState.sendSeq.toString(), ct),
                    ),
                )
            }

            val wrongArr = json.decodeFromString<JsonArray>(client.get("/inbox/$wrongToken").bodyAsText())
            assertTrue(wrongArr.isEmpty(), "Wrong token must see no messages")

            val correctArr = json.decodeFromString<JsonArray>(client.get("/inbox/$correctToken").bodyAsText())
            assertEquals(1, correctArr.size, "Correct token must see the message")
        }
}
