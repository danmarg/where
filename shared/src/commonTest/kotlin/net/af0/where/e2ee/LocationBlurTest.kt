package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationBlurTest {
    @Test
    fun testFinePrecisionDoesNoBlur() {
        val loc = LocationPlaintext(
            lat = 37.77491234,
            lng = -122.41945678,
            acc = 10.0,
            ts = 123456789,
            precision = LocationPrecision.FINE
        )
        val blurred = loc.blur()
        assertEquals(loc.lat, blurred.lat)
        assertEquals(loc.lng, blurred.lng)
        assertEquals(loc.acc, blurred.acc)
    }

    @Test
    fun testCoarsePrecisionBlurs() {
        val loc = LocationPlaintext(
            lat = 37.77491234,
            lng = -122.41945678,
            acc = 10.0,
            ts = 123456789,
            precision = LocationPrecision.COARSE
        )
        val blurred = loc.blur()
        
        // 37.77491234 -> 37.77
        assertEquals(37.77, blurred.lat)
        // -122.41945678 -> -122.42
        assertEquals(-122.42, blurred.lng)
        // Accuracy should be at least 1100m
        assertEquals(1100.0, blurred.acc)
    }

    @Test
    fun testCoarsePrecisionRespectsHighAcc() {
        val loc = LocationPlaintext(
            lat = 37.77,
            lng = -122.42,
            acc = 5000.0,
            ts = 123456789,
            precision = LocationPrecision.COARSE
        )
        val blurred = loc.blur()
        assertEquals(5000.0, blurred.acc)
    }
}
