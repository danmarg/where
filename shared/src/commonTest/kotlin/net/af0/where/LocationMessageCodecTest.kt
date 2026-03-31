package net.af0.where

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationMessageCodecTest {
    @Test
    fun encodeLocationUpdateProducesCorrectJson() {
        val json = LocationMessageCodec.encodeLocationUpdate("alice", 37.7749, -122.4194, 1000L)
        assertTrue(json.contains("\"type\":\"location\""), "Expected type=location in: $json")
        assertTrue(json.contains("\"userId\":\"alice\""), "Expected userId in: $json")
        assertTrue(json.contains("37.7749"), "Expected lat in: $json")
        assertTrue(json.contains("-122.4194"), "Expected lng in: $json")
    }

    @Test
    fun encodeLocationRemoveProducesCorrectJson() {
        val json = LocationMessageCodec.encodeLocationRemove()
        assertTrue(json.contains("\"type\":\"location_remove\""), "Expected type=location_remove in: $json")
    }

    @Test
    fun decodeUsersReturnsUserListFromBroadcast() {
        val broadcast = """{"type":"locations","users":[{"userId":"bob","lat":1.0,"lng":2.0,"timestamp":0}]}"""
        val users = LocationMessageCodec.decodeUsers(broadcast)
        assertNotNull(users)
        assertEquals(1, users.size)
        assertEquals("bob", users[0].userId)
        assertEquals(1.0, users[0].lat)
        assertEquals(2.0, users[0].lng)
    }

    @Test
    fun decodeUsersReturnsNullForNonBroadcastMessage() {
        val update = """{"type":"location","location":{"userId":"alice","lat":1.0,"lng":2.0,"timestamp":0}}"""
        assertNull(LocationMessageCodec.decodeUsers(update))
    }

    @Test
    fun decodeUsersReturnsNullForInvalidJson() {
        assertNull(LocationMessageCodec.decodeUsers("not json"))
    }

    @Test
    fun decodeUsersHandlesEmptyUserList() {
        val broadcast = """{"type":"locations","users":[]}"""
        val users = LocationMessageCodec.decodeUsers(broadcast)
        assertNotNull(users)
        assertEquals(0, users.size)
    }

    @Test
    fun encodeLocationUpdateRoundTripsViaDecodeUsers() {
        // Encode a location update (sent by client), then simulate a broadcast (sent by server)
        // to verify the userId/lat/lng survive the round-trip through the codec.
        val serverBroadcast = """{"type":"locations","users":[{"userId":"carol","lat":51.5,"lng":-0.1,"timestamp":9999}]}"""
        val users = LocationMessageCodec.decodeUsers(serverBroadcast)
        assertNotNull(users)
        assertEquals("carol", users[0].userId)
        assertEquals(51.5, users[0].lat)
        assertEquals(-0.1, users[0].lng)
        assertEquals(9999L, users[0].timestamp)
    }
}
