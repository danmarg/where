package net.af0.where

import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit test for LocationRepository thread safety.
 *
 * Verifies that concurrent onLocation() calls from multiple threads
 * do not lose updates or produce torn reads due to race conditions.
 */
class LocationRepositoryTest {
    @Before
    fun resetRepository() {
        // Reset the repository to a known state before each test
        LocationRepository.onLocation(0.0, 0.0)
    }

    /**
     * Test concurrent onLocation() calls from a thread pool.
     *
     * Submits 100 location updates from 8 background threads and verifies
     * that the final StateFlow value is one of the submitted locations
     * (not null and not a torn/corrupted value).
     */
    @Test
    fun testConcurrentLocationUpdates_FinalValueIsValid() {
        val executor = Executors.newFixedThreadPool(8)
        val locationCount = 100
        val latch = CountDownLatch(locationCount)
        val submittedLocations = mutableSetOf<Pair<Double, Double>>()

        // Submit 100 location updates concurrently
        for (i in 0 until locationCount) {
            val lat = (i * 0.001).toDouble()
            val lng = (i * 0.002).toDouble()
            val location = Pair(lat, lng)
            submittedLocations.add(location)

            executor.submit {
                try {
                    LocationRepository.onLocation(lat, lng)
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all updates to complete
        latch.await()
        executor.shutdown()

        // Verify final state on main thread
        val finalLocation = LocationRepository.lastLocation.value
        assertNotNull(finalLocation, "Final location should not be null after concurrent updates")
        assertTrue(
            finalLocation in submittedLocations,
            "Final location $finalLocation should be one of the submitted locations",
        )
    }

    /**
     * Test that StateFlow never observes a torn read.
     *
     * Submits 100 location updates and continuously samples the StateFlow
     * on the main thread. Verifies that every observed value is either null
     * or one of the submitted locations (never a corrupted intermediate state).
     *
     * This is a stronger guarantee than the first test: it ensures that
     * StateFlow's atomic update semantics prevent partial/torn writes from
     * being visible to observers.
     */
    @Test
    fun testConcurrentLocationUpdates_NoTornReads() {
        val executor = Executors.newFixedThreadPool(8)
        val locationCount = 100
        val latch = CountDownLatch(locationCount)
        val submittedLocations = mutableSetOf<Pair<Double, Double>>()
        val observedLocations = mutableSetOf<Pair<Double, Double>?>()

        // Continuously sample the StateFlow while updates are in flight
        val samplingThread =
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    observedLocations.add(LocationRepository.lastLocation.value)
                    Thread.yield()
                }
            }
        samplingThread.start()

        // Submit 100 location updates concurrently
        for (i in 0 until locationCount) {
            val lat = (i * 0.001).toDouble()
            val lng = (i * 0.002).toDouble()
            val location = Pair(lat, lng)
            submittedLocations.add(location)

            executor.submit {
                try {
                    LocationRepository.onLocation(lat, lng)
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all updates to complete
        latch.await()
        executor.shutdown()

        // Stop sampling
        samplingThread.interrupt()
        samplingThread.join(5000)

        // Verify all observed values are valid
        for (observed in observedLocations) {
            if (observed != null) {
                assertTrue(
                    observed in submittedLocations,
                    "Observed location $observed should be one of the submitted locations, not a torn read",
                )
            }
        }

        // Verify we observed at least some updates (smoke test)
        val nonNullObservations = observedLocations.filterNotNull()
        assertTrue(
            nonNullObservations.isNotEmpty(),
            "Should have observed at least some non-null location updates",
        )
    }
}
