package net.af0.where.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

class WsMessageSerializationTest {

    private val sampleLocation = UserLocation(
        userId = "alice",
        lat = 37.7749,
        lng = -122.4194,
        timestamp = 1711180800000L
    )

    @Test
    fun locationUpdateRoundTrip() {
        val original = WsMessage.LocationUpdate(sampleLocation)
        val encoded = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString<WsMessage>(encoded)
        assertIs<WsMessage.LocationUpdate>(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun locationsBroadcastRoundTrip() {
        val original = WsMessage.LocationsBroadcast(listOf(sampleLocation))
        val encoded = json.encodeToString(WsMessage.serializer(), original)
        val decoded = json.decodeFromString<WsMessage>(encoded)
        assertIs<WsMessage.LocationsBroadcast>(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun locationUpdateTypeDiscriminator() {
        val encoded = json.encodeToString(
            WsMessage.serializer(),
            WsMessage.LocationUpdate(sampleLocation)
        )
        assert(encoded.contains("\"type\":\"location\"")) { "Expected type=location in: $encoded" }
    }

    @Test
    fun locationsBroadcastTypeDiscriminator() {
        val encoded = json.encodeToString(
            WsMessage.serializer(),
            WsMessage.LocationsBroadcast(listOf(sampleLocation))
        )
        assert(encoded.contains("\"type\":\"locations\"")) { "Expected type=locations in: $encoded" }
    }

    @Test
    fun ignoresUnknownKeys() {
        val raw = """{"type":"locations","users":[{"userId":"bob","lat":1.0,"lng":2.0,"timestamp":0}],"extra":"ignored"}"""
        val decoded = json.decodeFromString<WsMessage>(raw)
        assertIs<WsMessage.LocationsBroadcast>(decoded)
        assertEquals(1, (decoded as WsMessage.LocationsBroadcast).users.size)
    }

    @Test
    fun locationRemoveRoundTrip() {
        val encoded = json.encodeToString(WsMessage.serializer(), WsMessage.LocationRemove)
        val decoded = json.decodeFromString<WsMessage>(encoded)
        assertIs<WsMessage.LocationRemove>(decoded)
    }

    @Test
    fun locationRemoveTypeDiscriminator() {
        val encoded = json.encodeToString(WsMessage.serializer(), WsMessage.LocationRemove)
        assert(encoded.contains("\"type\":\"location_remove\"")) { "Expected type=location_remove in: $encoded" }
    }
}
