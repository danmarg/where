package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagePlaintextCodecTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun roundTripLocationStationaryTrue() {
        val msg = MessagePlaintext.Location(1.5, -2.5, 10.0, 1234L, LocationPrecision.FINE, stationary = true)
        val decoded = Session.decodeMessage(Session.encodeMessage(msg)) as MessagePlaintext.Location
        assertEquals(msg, decoded)
        assertTrue(decoded.stationary)
    }

    @Test
    fun roundTripLocationStationaryFalseOmitsFieldOnWire() {
        val msg = MessagePlaintext.Location(1.5, -2.5, 10.0, 1234L)
        val wire = Session.encodeMessage(msg).decodeToString()
        assertTrue("stationary" !in wire, "expected stationary key omitted, got: $wire")
        val decoded = Session.decodeMessage(wire.encodeToByteArray()) as MessagePlaintext.Location
        assertEquals(msg, decoded)
    }

    @Test
    fun roundTripStoppedSharing() {
        val msg = MessagePlaintext.StoppedSharing(ts = 9999L)
        val decoded = Session.decodeMessage(Session.encodeMessage(msg)) as MessagePlaintext.StoppedSharing
        assertEquals(msg, decoded)
    }

    @Test
    fun roundTripKeepaliveUsesExplicitType() {
        val msg = MessagePlaintext.Keepalive()
        val wire = Session.encodeMessage(msg).decodeToString()
        assertTrue("\"type\":\"ka\"" in wire, "expected explicit type=ka, got: $wire")
        val decoded = Session.decodeMessage(wire.encodeToByteArray())
        assertTrue(decoded is MessagePlaintext.Keepalive)
    }

    @Test
    fun backwardsCompatLegacyLocationWithoutTypeFieldDecodes() {
        val legacy = """{"lat":1.0,"lng":2.0,"acc":3.0,"ts":4,"precision":"FINE"}"""
        val decoded = Session.decodeMessage(legacy.encodeToByteArray()) as MessagePlaintext.Location
        assertEquals(1.0, decoded.lat)
        assertEquals(2.0, decoded.lng)
        assertEquals(4L, decoded.ts)
        assertEquals(LocationPrecision.FINE, decoded.precision)
        assertEquals(false, decoded.stationary)
    }

    @Test
    fun backwardsCompatLegacyEmptyObjectKeepaliveDecodes() {
        val legacy = "{}"
        val decoded = Session.decodeMessage(legacy.encodeToByteArray())
        assertTrue(decoded is MessagePlaintext.Keepalive)
    }

    @Test
    fun forwardCompatUnknownTypeDecodesToKeepalive() {
        val future = """{"type":"future-variant","data":"whatever"}"""
        val decoded = Session.decodeMessage(future.encodeToByteArray())
        assertTrue(decoded is MessagePlaintext.Keepalive)
    }

    @Test
    fun malformedStopMissingTsDegradesToKeepalive() {
        val malformed = """{"type":"stop"}"""
        val decoded = Session.decodeMessage(malformed.encodeToByteArray())
        assertTrue(decoded is MessagePlaintext.Keepalive)
    }

    @Test
    fun malformedStopWithNonNumericTsDegradesToKeepalive() {
        val malformed = """{"type":"stop","ts":"not-a-number"}"""
        val decoded = Session.decodeMessage(malformed.encodeToByteArray())
        assertTrue(decoded is MessagePlaintext.Keepalive)
    }
}
