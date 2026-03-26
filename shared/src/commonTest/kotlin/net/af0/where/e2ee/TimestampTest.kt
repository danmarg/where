package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimestampTest {

    @Test
    fun `fresh timestamp is accepted`() {
        val now = currentTimeSeconds()
        assertTrue(isTimestampFresh(now))
    }

    @Test
    fun `timestamp slightly in the future is accepted`() {
        val now = currentTimeSeconds()
        assertTrue(isTimestampFresh(now + 60))       // 1 min future
        assertTrue(isTimestampFresh(now + 5 * 60))   // 5 min future — within grace
    }

    @Test
    fun `timestamp slightly in the past is accepted`() {
        val now = currentTimeSeconds()
        assertTrue(isTimestampFresh(now - 60))        // 1 min ago
        assertTrue(isTimestampFresh(now - 10 * 60))   // 10 min ago — within T + 5 min window
    }

    @Test
    fun `stale timestamp is rejected`() {
        val now = currentTimeSeconds()
        // Default epochPeriodSeconds = 600 (10 min), grace = T + 5 min = 15 min
        assertFalse(isTimestampFresh(now - 16 * 60))  // 16 min ago — outside window
        assertFalse(isTimestampFresh(now - 60 * 60))  // 1 hour ago
    }

    @Test
    fun `far-future timestamp is rejected`() {
        val now = currentTimeSeconds()
        assertFalse(isTimestampFresh(now + 16 * 60))  // 16 min in future — outside window
    }

    @Test
    fun `custom epoch period is respected`() {
        val now = currentTimeSeconds()
        val epochPeriod = 300L  // 5 min; grace = 5 + 5 = 10 min
        assertTrue(isTimestampFresh(now - 9 * 60, epochPeriod))
        assertFalse(isTimestampFresh(now - 11 * 60, epochPeriod))
    }
}
