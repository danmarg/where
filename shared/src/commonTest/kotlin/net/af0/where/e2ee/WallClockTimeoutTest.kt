package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WallClockTimeoutTest {

    @Test
    fun testTimeoutTriggersOnClockJump() = runTest {
        val mockTime = MockTimeProvider(1000L)
        TimeSource.setProvider(mockTime)

        assertFailsWith<WallClockTimeoutCancellationException> {
            withWallClockTimeout(5000L) {
                // Advance the wall-clock time by 6000ms to simulate app suspension
                mockTime.advanceMillis(6000L)
                // The watcher loop checks every 100ms
                delay(200)
            }
        }
        
        TimeSource.setProvider(DefaultTimeProvider)
    }

    @Test
    fun testTimeoutOrNullReturnsNullOnClockJump() = runTest {
        val mockTime = MockTimeProvider(1000L)
        TimeSource.setProvider(mockTime)

        val result = withWallClockTimeoutOrNull(5000L) {
            mockTime.advanceMillis(6000L)
            delay(200)
            "Success"
        }
        assertNull(result)
        
        TimeSource.setProvider(DefaultTimeProvider)
    }

    @Test
    fun testTimeoutCompletesSuccessfully() = runTest {
        val mockTime = MockTimeProvider(1000L)
        TimeSource.setProvider(mockTime)

        val result = withWallClockTimeout(5000L) {
            delay(100)
            "Success"
        }
        assertEquals("Success", result)
        
        TimeSource.setProvider(DefaultTimeProvider)
    }
}
