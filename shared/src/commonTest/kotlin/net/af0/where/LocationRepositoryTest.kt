package net.af0.where

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit test for LocationRepository thread safety and state management.
 *
 * Verifies that concurrent onLocation() calls from multiple coroutines
 * do not lose updates or produce torn reads due to race conditions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepositoryTest {
    private val mutex = Mutex()

    @BeforeTest
    fun resetRepository() {
        // Reset the repository to a known state before each test
        LocationRepository.reset()
        LocationRepository.onLocation(0.0, 0.0, null)
    }

    @Test
    fun testDefaultSharingStateIsFalse() {
        LocationRepository.reset()
        assertTrue(!LocationRepository.isSharingLocation.value, "Default sharing state should be false")
    }

    /**
     * Test concurrent onLocation() calls from multiple coroutines.
     *
     * Submits 100 location updates from background coroutines and verifies
     * that the final StateFlow value is one of the submitted locations
     * (not null and not a torn/corrupted value).
     */
    @Test
    fun testConcurrentLocationUpdates_FinalValueIsValid() =
        runTest {
            val locationCount = 100
            val submittedLocations = mutableSetOf<Triple<Double, Double, Double?>>()

            // Run on Dispatchers.Default to ensure actual multi-threaded execution on JVM/Native
            withContext(Dispatchers.Default) {
                val jobs =
                    List(locationCount) { i ->
                        val lat = (i * 0.001).toDouble()
                        val lng = (i * 0.002).toDouble()
                        val location = Triple(lat, lng, null)
                        launch {
                            mutex.withLock {
                                submittedLocations.add(location)
                            }
                            LocationRepository.onLocation(lat, lng, null)
                        }
                    }
                jobs.joinAll()
            }

            // Verify final state
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
     * on a separate coroutine. Verifies that every observed value is either null
     * or one of the submitted locations (never a corrupted intermediate state).
     */
    @Test
    fun testConcurrentLocationUpdates_NoTornReads() =
        runTest {
            val locationCount = 100
            val submittedLocations = mutableSetOf<Triple<Double, Double, Double?>>()
            val observedLocations = mutableSetOf<Triple<Double, Double, Double?>?>()

            withContext(Dispatchers.Default) {
                // Continuously sample the StateFlow while updates are in flight
                val sampler =
                    launch {
                        while (true) {
                            val current = LocationRepository.lastLocation.value
                            mutex.withLock {
                                observedLocations.add(current)
                            }
                            // Yield to give writers a chance
                            delay(1)
                        }
                    }

                // Submit 100 location updates concurrently
                val writers =
                    List(locationCount) { i ->
                        val lat = (i * 0.001).toDouble()
                        val lng = (i * 0.002).toDouble()
                        val location = Triple(lat, lng, null)
                        launch {
                            mutex.withLock {
                                submittedLocations.add(location)
                            }
                            LocationRepository.onLocation(lat, lng, null)
                        }
                    }

                writers.joinAll()
                sampler.cancel()
            }

            // Verify all observed values are valid
            for (observed in observedLocations) {
                if (observed != null) {
                    assertTrue(
                        observed in submittedLocations,
                        "Observed location $observed should be one of the submitted locations, not a torn read",
                    )
                }
            }

            // Verify we observed at least some updates
            val nonNullObservations = observedLocations.filterNotNull()
            assertTrue(
                nonNullObservations.isNotEmpty(),
                "Should have observed at least some non-null location updates",
            )
        }
}
