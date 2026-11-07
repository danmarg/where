package net.af0.where

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighAccuracyWindowTest {
    // Distance in "meters" is just the latitude difference, so tests read directly.
    private fun window() =
        HighAccuracyWindow(
            idleMs = 10 * MINUTE,
            moveThresholdMeters = 200f,
            distanceMeters = { lat1, _, lat2, _ -> kotlin.math.abs(lat2 - lat1).toFloat() },
        )

    @Test
    fun `idle once the window passes with no fix`() {
        val w = window()
        w.start(0L)
        assertFalse(w.isIdle(10 * MINUTE - 1))
        assertTrue(w.isIdle(10 * MINUTE))
    }

    @Test
    fun `first fix only sets the anchor and does not extend the window`() {
        val w = window()
        w.start(0L)
        w.onFix(9 * MINUTE, 0.0, 0.0)
        assertTrue(w.isIdle(10 * MINUTE))
    }

    @Test
    fun `a fix past the threshold extends the window`() {
        val w = window()
        w.start(0L)
        w.onFix(1 * MINUTE, 0.0, 0.0)
        w.onFix(8 * MINUTE, 200.0, 0.0)
        assertFalse(w.isIdle(10 * MINUTE))
        assertFalse(w.isIdle(18 * MINUTE - 1))
        assertTrue(w.isIdle(18 * MINUTE))
    }

    @Test
    fun `jitter under the threshold does not extend the window or move the anchor`() {
        val w = window()
        w.start(0L)
        w.onFix(1 * MINUTE, 0.0, 0.0)
        // Many small steps adding up past the threshold still don't count: they're measured
        // from the anchor, which only moves on a fix that counts as movement.
        for (i in 1..9) w.onFix(i * MINUTE, i * 50.0 % 150, 0.0)
        assertTrue(w.isIdle(10 * MINUTE))
    }

    @Test
    fun `start resets the anchor`() {
        val w = window()
        w.start(0L)
        w.onFix(1 * MINUTE, 0.0, 0.0)
        w.start(20 * MINUTE)
        // Far from the old anchor, but it's the new window's first fix, so it only anchors.
        w.onFix(21 * MINUTE, 5_000.0, 0.0)
        assertFalse(w.isIdle(29 * MINUTE))
        assertTrue(w.isIdle(30 * MINUTE))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
