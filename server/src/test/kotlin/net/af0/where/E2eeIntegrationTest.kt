package net.af0.where

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.af0.where.e2ee.*
import kotlin.test.*

class E2eeIntegrationTest {

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `key exchange produces identical routing token and chain key on both sides`() {
        LibsodiumInitializer.ensureInitialized()
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

        assertContentEquals(aliceSession.prevSendToken, bobSession.recvToken)
        assertContentEquals(aliceSession.recvToken, bobSession.sendToken)
        assertContentEquals(aliceSession.recvChainKey, bobSession.sendChainKey)
    }

    @Test
    fun `full flow - key exchange then encrypt location through mailbox then decrypt`() =
        testApplication {
            LibsodiumInitializer.ensureInitialized()
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val token = aliceSession.sendToken.toHex()
            val location = MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_000L)
            val (_, message) = Session.encryptMessage(aliceSession, location)

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(message))
            }

            val response = client.get("/inbox/$token")
            val msgs = json.decodeFromString<List<MailboxMessage>>(response.bodyAsText())
            assertEquals(1, msgs.size)

            val received = json.decodeFromJsonElement<MailboxPayload>(msgs[0].payload)
            assertIs<EncryptedMessagePayload>(received)
            val decryptResult = Session.decryptMessage(bobSession, received)

            assertNotNull(decryptResult)
            val (_, decrypted) = decryptResult
            assertIs<MessagePlaintext.Location>(decrypted)
            assertEquals(location.lat, decrypted.lat, 1e-9)
        }

    @Test
    fun `multiple sequential location updates are decryptable in order`() =
        testApplication {
            LibsodiumInitializer.ensureInitialized()
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobState) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceState0 = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            val token = aliceState0.sendToken.toHex()

            val locations = listOf(
                MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 10.0, ts = 1_700_000_001L),
                MessagePlaintext.Location(lat = 37.7750, lng = -122.4195, acc = 8.0, ts = 1_700_000_031L),
                MessagePlaintext.Location(lat = 37.7751, lng = -122.4196, acc = 6.0, ts = 1_700_000_061L),
            )

            var aliceState = aliceState0
            for (loc in locations) {
                val (nextState, message) = Session.encryptMessage(aliceState, loc)
                aliceState = nextState
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString<MailboxPayload>(message))
                }
            }

            val response = client.get("/inbox/$token")
            val msgs = json.decodeFromString<List<MailboxMessage>>(response.bodyAsText())
            assertEquals(3, msgs.size)

            var bobStateNow = bobState
            for ((i, m) in msgs.withIndex()) {
                val msg = json.decodeFromJsonElement<MailboxPayload>(m.payload)
                assertIs<EncryptedMessagePayload>(msg)
                val (newBobState, decrypted) = Session.decryptMessage(bobStateNow, msg) ?: fail("fail $i")
                bobStateNow = newBobState
                assertIs<MessagePlaintext.Location>(decrypted)
                assertEquals(locations[i].lat, decrypted.lat, 1e-9)
            }
        }

    @Test
    fun `ciphertext tampered in transit fails decryption`() =
        testApplication {
            LibsodiumInitializer.ensureInitialized()
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            val token = aliceSession.sendToken.toHex()

            val location = MessagePlaintext.Location(lat = 51.5, lng = -0.12, acc = 5.0, ts = 1_700_000_002L)
            val (_, message) = Session.encryptMessage(aliceSession, location)

            val tamperedCt = message.ct.copyOf()
            tamperedCt[tamperedCt.size - 1] = (tamperedCt[tamperedCt.size - 1].toInt() xor 1).toByte()
            val tamperedMsg = message.copy(ct = tamperedCt)

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(tamperedMsg))
            }

            val response = client.get("/inbox/$token")
            val msgs = json.decodeFromString<List<MailboxMessage>>(response.bodyAsText())
            val received = json.decodeFromJsonElement<MailboxPayload>(msgs[0].payload)
            assertIs<EncryptedMessagePayload>(received)

            assertFails {
                Session.decryptMessage(bobSession, received)
            }
        }

    @Test
    fun `ratchet rotation - token changes, old and new tokens are independently decryptable`() =
        testApplication {
            LibsodiumInitializer.ensureInitialized()
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobState0) = KeyExchange.bobProcessQr(qr, "Bob")
            var aliceState = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
            var bobState = bobState0

            val oldToken = aliceState.sendToken.toHex()
            val locA1 = MessagePlaintext.Location(lat = 51.5, lng = -0.1, acc = 5.0, ts = 1000L)

            // Alice -> Bob (Epoch 0)
            val (aliceS1, msgA1) = Session.encryptMessage(aliceState, locA1)
            val (bobS1, _) = Session.decryptMessage(bobState, msgA1)

            // Bob -> Alice (triggers Alice ratchet to Epoch 1)
            val (bobS2, msgB2) = Session.encryptMessage(bobS1, MessagePlaintext.Location(1.0, 2.0, 3.0, 4L))
            val (aliceS2, _) = Session.decryptMessage(aliceS1, msgB2)

            // Alice (Epoch 1) -> Bob
            val (aliceS3, msgA3) = Session.encryptMessage(aliceS2, locA1)
            val newToken = aliceS3.sendToken.toHex()
            assertNotEquals(oldToken, newToken)

            client.post("/inbox/$oldToken") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(msgA3))
            }

            val (bobS3, _) = Session.decryptMessage(bobS2, msgA3)
            assertEquals(newToken, bobS3.recvToken.toHex())

            val (_, msgA4) = Session.encryptMessage(aliceS3, locA1)
            client.post("/inbox/$newToken") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(msgA4))
            }

            val response = client.get("/inbox/$newToken")
            val msgs = json.decodeFromString<List<MailboxMessage>>(response.bodyAsText())
            val msg4 = json.decodeFromJsonElement<EncryptedMessagePayload>(msgs.last().payload)
            val (_, decA4) = Session.decryptMessage(bobS3, msg4)
            assertIs<MessagePlaintext.Location>(decA4)

            val (_, decA1) = Session.decryptMessage(bobState, msgA1)
            assertIs<MessagePlaintext.Location>(decA1)
        }

    @Test
    fun `routing token isolation - wrong token mailbox is empty`() =
        testApplication {
            LibsodiumInitializer.ensureInitialized()
            application { module(ServerState()) }

            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
            val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)

            val location = MessagePlaintext.Location(lat = 48.8566, lng = 2.3522, acc = 15.0, ts = 1_700_000_003L)
            val (_, message) = Session.encryptMessage(aliceSession, location)

            val correctToken = aliceSession.sendToken.toHex()
            val wrongToken = "ffffffffffffffffffffffffffffffff"

            client.post("/inbox/$correctToken") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString<MailboxPayload>(message))
            }

            val wrongArr = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$wrongToken").bodyAsText())
            assertTrue(wrongArr.isEmpty())

            val correctArr = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$correctToken").bodyAsText())
            assertEquals(1, correctArr.size)
        }
}
