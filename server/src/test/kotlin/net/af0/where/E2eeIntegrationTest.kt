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

        // In the new Sealed Envelope protocol, Alice performs an initial DH ratchet
        // rotation (Epoch 1) immediately after receiving the KeyExchangeInit
        // to break the bootstrap deadlock. Bob remains in Epoch 0 until he
        // receives Alice's first message.

        // Alice's PREVIOUS send token (Epoch 0) must match Bob's current recv token (Epoch 0).
        assertContentEquals(
            aliceSession.prevSendToken,
            bobSession.recvToken,
            "Alice's prevSendToken must equal Bob's recv token",
        )
        // Alice's current recv token (Epoch 0 receiver) must match Bob's current send token (Epoch 0 sender).
        assertContentEquals(
            aliceSession.recvToken,
            bobSession.sendToken,
            "Alice's recv token must equal Bob's send token",
        )

        // Chains: Alice's send chain is now Epoch 1. Bob's recv chain is Epoch 0.
        // They will only match AFTER Bob decrypts Alice's first message and ratchets.
        // But Alice's RECV chain (Epoch 0) should still match Bob's SEND chain (Epoch 0).
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

            // Key exchange
            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val token = aliceSession.sendToken.toHex()

            // Alice encrypts a location update
            val location = MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_000L)
            val (newAliceState, message) = Session.encryptMessage(aliceSession, location)

            // Alice posts the ciphertext to Bob's mailbox
            val postResp =
                client.put("/inbox/$token/${message.msgId}") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString<MailboxPayload>(message))
                }
            assertEquals(HttpStatusCode.NoContent, postResp.status)

            // Bob fetches the mailbox
            val getResp = client.get("/inbox/$token")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val arr = json.decodeFromString<JsonArray>(getResp.bodyAsText())
            assertEquals(1, arr.size, "Expected exactly one message in mailbox")

            // Bob decodes and decrypts
            val received = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedMessagePayload>(received)
            val decryptResult = Session.decryptMessage(bobSession, received)

            assertNotNull(decryptResult, "Decryption must succeed")
            val (_, decrypted) = decryptResult
            assertIs<MessagePlaintext.Location>(decrypted)
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
                    MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_001L),
                    MessagePlaintext.Location(lat = 37.7750, lng = -122.4195, acc = 8.0, ts = 1_700_000_031L),
                    MessagePlaintext.Location(lat = 37.7751, lng = -122.4196, acc = 6.0, ts = 1_700_000_061L),
                )

            // Alice encrypts and posts all three
            var aliceState = aliceState0
            for (loc in locations) {
                val (nextState, message) = Session.encryptMessage(aliceState, loc)
                aliceState = nextState
                client.put("/inbox/$token/${message.msgId}") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString<MailboxPayload>(message))
                }
            }

            // Bob retrieves and decrypts all three
            val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(3, arr.size)

            var bobStateNow = bobState
            for ((i, el) in arr.withIndex()) {
                val msg = json.decodeFromJsonElement<MailboxPayload>(el)
                assertIs<EncryptedMessagePayload>(msg)
                val (newBobState, decrypted) =
                    Session.decryptMessage(bobStateNow, msg) ?: fail("Decryption failed for message $i")
                bobStateNow = newBobState
                assertIs<MessagePlaintext.Location>(decrypted)
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

            val location = MessagePlaintext.Location(lat = 51.5, lng = -0.12, acc = 5.0, ts = 1_700_000_002L)
            val (newAliceState, message) = Session.encryptMessage(aliceSession, location)

            // Flip a bit in the ciphertext to simulate in-transit corruption
            val tamperedCt = message.ct.copyOf()
            tamperedCt[tamperedCt.size - 1] = (tamperedCt[tamperedCt.size - 1].toInt() xor 1).toByte()
            val tamperedMsg = message.copy(ct = tamperedCt)

            client.put("/inbox/$token/${tamperedMsg.msgId}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(tamperedMsg))
            }

            val arr = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, arr.size)
            val msg = json.decodeFromJsonElement<MailboxPayload>(arr[0])
            assertIs<EncryptedMessagePayload>(msg)

            val threw =
                try {
                    Session.decryptMessage(bobSession, msg)
                    false
                } catch (_: Exception) {
                    true
                }
            assertTrue(threw, "Decrypting a tampered ciphertext must throw")
        }

    // -----------------------------------------------------------------------
    // Ratchet rotation
    // -----------------------------------------------------------------------

    @Test
    fun `ratchet rotation - token changes, old and new tokens are independently decryptable`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobState0) = KeyExchange.bobProcessQr(qr, "Bob")
            var aliceState = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            var bobState = bobState0

            val oldToken = aliceState.sendToken.toHex()

            // 1. Alice sends message 1 (A0).
            val locA1 = MessagePlaintext.Location(lat = 51.5, lng = -0.1, acc = 5.0, ts = 1000L)
            val (aliceState1, messageA1) = Session.encryptMessage(aliceState, locA1)
            aliceState = aliceState1
            client.put("/inbox/$oldToken/${messageA1.msgId}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(messageA1))
            }

            // 2. Bob decrypts Alice's message 1.
            val (bobState1, _) = Session.decryptMessage(bobState, messageA1)
            bobState = bobState1

            // Alice (A0) -> Bob (B0)
            val (aliceS1, msgA1) = Session.encryptMessage(aliceState, locA1)
            val (bobS1, _) = Session.decryptMessage(bobState, msgA1)

            // Bob (B0) -> Alice (A0)
            val locB1 = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)
            val (bobS2, msgB2) = Session.encryptMessage(bobS1, locB1)
            val (aliceS2, _) = Session.decryptMessage(aliceS1, msgB2)

            // Alice (A1) -> Bob (B0)
            val (aliceS3, msgA3) = Session.encryptMessage(aliceS2, locA1)

            val newToken = aliceS3.sendToken.toHex()
            assertNotEquals(oldToken, newToken, "Routing token must change after rotation")

            // Alice posts msgA3 to the OLD token because it's the FIRST message in a new epoch.
            client.put("/inbox/$oldToken/${msgA3.msgId}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(msgA3))
            }

            // Bob decrypts msgA3. This triggers Bob to ratchet to A1 and generate B1.
            val (bobS3, decA3) = Session.decryptMessage(bobS2, msgA3)
            assertIs<MessagePlaintext.Location>(decA3)
            assertEquals(newToken, bobS3.recvToken.toHex(), "Bob's recvToken should now match Alice's new sendToken")

            // Alice now sends M4 (A1) to the NEW token.
            val (aliceS4, msgA4) = Session.encryptMessage(aliceS3, locA1)
            client.put("/inbox/$newToken/${msgA4.msgId}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(msgA4))
            }

            // Bob polls new token and decrypts msgA4.
            val newArr = json.decodeFromString<JsonArray>(client.get("/inbox/$newToken").bodyAsText())
            val msg4 = json.decodeFromJsonElement<EncryptedMessagePayload>(newArr.last())
            val (_, decA4) = Session.decryptMessage(bobS3, msg4)
            assertIs<MessagePlaintext.Location>(decA4)

            // Verify independence: Bob can STILL decrypt the very first message using his INITIAL state.
            val (_, decA1) = Session.decryptMessage(bobState, msgA1)
            assertIs<MessagePlaintext.Location>(decA1)
        }

    @Test
    fun `skipped messages within same epoch are decryptable`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, bobState0) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceState = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

        // Alice encrypts two locations in the same epoch (Epoch 1)
        val location = MessagePlaintext.Location(lat = 1.0, lng = 2.0, acc = 1.0, ts = 1000L)
        val (aliceState1, message1) = Session.encryptMessage(aliceState, location)

        // Bob receives message 1 (seq 1). This triggers Bob to ratchet to Epoch 1.
        val (bobState1, decrypted1) = Session.decryptMessage(bobState0, message1)
        assertIs<MessagePlaintext.Location>(decrypted1)
        assertEquals(2.0, decrypted1.lng)

        // Now Alice sends message 2 (seq 2) and message 3 (seq 3) in same epoch
        val (aliceState2, message2) = Session.encryptMessage(aliceState1, location)
        val (_, message3) = Session.encryptMessage(aliceState2, location)

        // Bob receives message 3 (seq 3) first.
        // This triggers Bob to skip seq 2.
        val (bobState2, decrypted3) = Session.decryptMessage(bobState1, message3)
        assertIs<MessagePlaintext.Location>(decrypted3)

        // Bob can still decrypt message 2 if it arrives later (seq 2 was skipped)
        val (_, decrypted2) = Session.decryptMessage(bobState2, message2)
        assertIs<MessagePlaintext.Location>(decrypted2)
    }

    @Test
    fun `routing token isolation - wrong token mailbox is empty`() =
        testApplication {
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val location = MessagePlaintext.Location(lat = 48.8566, lng = 2.3522, acc = 15.0, ts = 1_700_000_003L)
            val (newAliceState, message) = Session.encryptMessage(aliceSession, location)

            val correctToken = aliceSession.sendToken.toHex()
            val wrongToken = "ffffffffffffffffffffffffffffffff"

            client.put("/inbox/$correctToken/${message.msgId}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(message))
            }

            val wrongArr = json.decodeFromString<JsonArray>(client.get("/inbox/$wrongToken").bodyAsText())
            assertTrue(wrongArr.isEmpty(), "Wrong token must see no messages")

            val correctArr = json.decodeFromString<JsonArray>(client.get("/inbox/$correctToken").bodyAsText())
            assertEquals(1, correctArr.size, "Correct token must see the message")
        }
}
