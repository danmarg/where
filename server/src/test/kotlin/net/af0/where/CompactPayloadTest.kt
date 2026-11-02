package net.af0.where

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.af0.where.e2ee.DhPublicKey
import net.af0.where.e2ee.EncryptedMessagePayload
import net.af0.where.e2ee.KeyExchange
import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.MessagePlaintext
import net.af0.where.e2ee.Session
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Real location messages, encoded as clients send them (mailboxJson: encodeDefaults = true). */
internal object RealMessages {
    init {
        LibsodiumInitializer.initializeWithCallback {}
    }

    private val wireJson =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun locations(count: Int): List<EncryptedMessagePayload> {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
        var alice = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, DhPublicKey(qr.ekPub.toByteArray()))
        return List(count) { i ->
            val location = MessagePlaintext.Location(lat = 37.7749 + i, lng = -122.4194, acc = 10.0, ts = 1_700_000_000L + i)
            val (next, message) = Session.encryptMessage(alice, location)
            alice = next
            message
        }
    }

    /** [message] as the server sees it: the PUT body parsed with parseToJsonElement. */
    fun wire(message: MailboxPayload): JsonElement = Json.parseToJsonElement(wireJson.encodeToJsonElement(message).toString())
}

class CompactPayloadTest {
    @Test
    fun `real location messages round-trip byte-identically`() {
        for (message in RealMessages.locations(20)) {
            val payload = RealMessages.wire(message)
            val attrs = assertNotNull(CompactPayload.encode(payload, message.msgId), "a real location message is compacted")
            val item = attrs + ("msgId" to AttributeValue.fromS(message.msgId))
            assertEquals(payload.toString(), CompactPayload.decode(item).toString())
        }
    }

    @Test
    fun `edge values of v round-trip`() {
        val message = RealMessages.locations(1).single()
        val good = RealMessages.wire(message).jsonObject
        for (v in listOf(0, Int.MAX_VALUE)) {
            val payload = JsonObject(good + ("v" to JsonPrimitive(v)))
            val attrs = assertNotNull(CompactPayload.encode(payload, message.msgId), "v = $v")
            val item = attrs + ("msgId" to AttributeValue.fromS(message.msgId))
            assertEquals(payload.toString(), CompactPayload.decode(item).toString())
        }
    }

    @Test
    fun `an item holding a payload string is not decoded`() {
        assertNull(CompactPayload.decode(mapOf("payload" to AttributeValue.fromS("{}"), "msgId" to AttributeValue.fromS("m"))))
    }

    @Test
    fun `malformed compact items are rejected, not thrown on`() {
        val message = RealMessages.locations(1).single()
        val good =
            assertNotNull(CompactPayload.encode(RealMessages.wire(message), message.msgId)) +
                ("msgId" to AttributeValue.fromS(message.msgId))
        assertNotNull(CompactPayload.decode(good))
        val bytes = AttributeValue.fromB(SdkBytes.fromByteArray(byteArrayOf(1)))
        val cases =
            mapOf(
                "missing v" to good - "v",
                "missing e" to good - "e",
                "missing c" to good - "c",
                "missing msgId" to good - "msgId",
                "v as a string" to good + ("v" to AttributeValue.fromS("1")),
                "e as a string" to good + ("e" to AttributeValue.fromS("abc")),
                "c as a string" to good + ("c" to AttributeValue.fromS("abc")),
                "msgId as a number" to good + ("msgId" to AttributeValue.fromN("1")),
                "negative v" to good + ("v" to AttributeValue.fromN("-1")),
                "v past Int" to good + ("v" to AttributeValue.fromN("99999999999")),
                "fractional v" to good + ("v" to AttributeValue.fromN("1.5")),
                "empty e" to good + ("e" to AttributeValue.fromB(SdkBytes.fromByteArray(ByteArray(0)))),
                "empty c" to good + ("c" to AttributeValue.fromB(SdkBytes.fromByteArray(ByteArray(0)))),
                "e and c as bytes but v missing" to mapOf("e" to bytes, "c" to bytes, "msgId" to AttributeValue.fromS("m")),
            )
        for ((name, item) in cases) {
            assertNull(CompactPayload.decode(item), name)
            assertEquals(true, CompactPayload.isCompact(item), "$name still counts as compact, never legacy")
        }
        assertEquals(false, CompactPayload.isCompact(mapOf("payload" to AttributeValue.fromS("{}"))))
    }

    @Test
    fun `payloads that are not exactly what clients send are left as JSON`() {
        val message = RealMessages.locations(1).single()
        val good = RealMessages.wire(message).jsonObject
        assertNotNull(CompactPayload.encode(good, message.msgId))

        fun with(
            key: String,
            value: JsonElement,
        ) = JsonObject(good + (key to value))

        fun without(key: String) = JsonObject(good - key)
        val envelope = (good.getValue("envelope") as JsonPrimitive).content
        val cases =
            mapOf(
                "path msgId differs" to (good to "other"),
                "no path msgId" to (good to null),
                "extra key" to (JsonObject(good + ("extra" to JsonPrimitive(1))) to message.msgId),
                "missing msgId" to (without("msgId") to message.msgId),
                "keys reordered" to (JsonObject(good.entries.reversed().associate { it.toPair() }) to message.msgId),
                "other type" to (with("type", JsonPrimitive("KeyExchangeInit")) to message.msgId),
                "v as a string" to (with("v", JsonPrimitive("1")) to message.msgId),
                "v as 1.0" to (with("v", Json.parseToJsonElement("1.0")) to message.msgId),
                "v as 01" to (with("v", Json.parseToJsonElement("01")) to message.msgId),
                "v past Int" to (with("v", Json.parseToJsonElement("99999999999")) to message.msgId),
                "unpadded base64" to (with("envelope", JsonPrimitive(envelope.trimEnd('='))) to message.msgId),
                "URL-safe base64" to (with("envelope", JsonPrimitive("-_" + envelope.drop(2))) to message.msgId),
                "empty envelope" to (with("envelope", JsonPrimitive("")) to message.msgId),
                "envelope not a string" to (with("envelope", JsonPrimitive(1)) to message.msgId),
            )
        for ((name, case) in cases) {
            assertNull(CompactPayload.encode(case.first, case.second), name)
        }
        assertNull(CompactPayload.encode(JsonPrimitive("x"), "m"), "not an object")
    }
}
