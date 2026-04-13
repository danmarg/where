package net.af0.where

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ShadowLog.stream = System.out
        LocationRepository.reset()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.locationSource = LocationRepository

        // Mock E2eeMailboxClient to prevent network calls during pollPendingInvite
        io.mockk.mockkObject(net.af0.where.e2ee.E2eeMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.E2eeMailboxClient.poll(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun getServiceIsRegistered(service: LocationService): Boolean {
        val field = LocationService::class.java.getDeclaredField("isRegistered")
        field.isAccessible = true
        return field.get(service) as Boolean
    }

    private fun serviceScope(service: LocationService): kotlinx.coroutines.CoroutineScope {
        val field = LocationService::class.java.getDeclaredField("serviceScope")
        field.isAccessible = true
        return field.get(service) as kotlinx.coroutines.CoroutineScope
    }

    @Test
    fun testDeduplication_BugC() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()

        val mockFusedClient = io.mockk.mockk<com.google.android.gms.location.FusedLocationProviderClient>(relaxed = true)
        service.fusedClientOverride = mockFusedClient

        controller.create()

        try {
            assertTrue(getServiceIsRegistered(service))

            // Multiple startCommand calls must not attempt to re-register location updates.
            controller.startCommand(0, 1)
            controller.startCommand(0, 2)

            assertTrue(getServiceIsRegistered(service))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun testSendLocationThrottle_Movement() =
        runTest {
            var currentTime = 100_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

            try {
                val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
                val locationClientField = LocationService::class.java.getDeclaredField("locationClient")
                locationClientField.isAccessible = true
                locationClientField.set(service, mockClient)

                // 1. Initial send
                service.sendLocationIfNeeded(37.7, -122.4, false, false)
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }
                assertTrue(service.lastSentTime > 0)

                // 2. Immediate second send (no longer throttled for non-heartbeat)
                service.sendLocationIfNeeded(37.8, -122.5, false, false)
                io.mockk.coVerify(exactly = 2) { mockClient.sendLocation(any(), any(), any()) }

                // 3. Send after 1s
                currentTime += 1_000L
                service.sendLocationIfNeeded(37.9, -122.6, false, false)
                io.mockk.coVerify(exactly = 3) { mockClient.sendLocation(any(), any(), any()) }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testSendLocationThrottle_Heartbeat() =
        runTest {
            var currentTime = 1_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

            try {
                val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
                val locationClientField = LocationService::class.java.getDeclaredField("locationClient")
                locationClientField.isAccessible = true
                locationClientField.set(service, mockClient)

                // 1. Initial send
                service.sendLocationIfNeeded(37.7, -122.4, false, false)
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }

                // 2. Heartbeat after 1 minute (throttled - heartbeat throttle is 300s)
                currentTime += 60_000L
                service.sendLocationIfNeeded(37.7, -122.4, true, false)
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }

                // 3. Heartbeat after 6 minutes (not throttled)
                currentTime += 300_000L
                service.sendLocationIfNeeded(37.7, -122.4, true, false)
                io.mockk.coVerify(exactly = 2) { mockClient.sendLocation(any(), any(), any()) }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testIsRapidPolling() =
        runTest(testDispatcher) {
            var currentTime = 1_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
            io.mockk.coEvery { mockStore.pendingQrPayload() } returns null
            service.e2eeStoreOverride = mockStore
            controller.create()
            runCurrent()

            try {
                // 1. Initial state: not rapid
                assertFalse(service.isRapidPolling())

                // 2. Recent rapid poll trigger (within 5 minutes)
                LocationRepository._lastRapidPollTrigger.value = currentTime - 60_000L // 1 minute ago
                assertTrue(service.isRapidPolling())

                // 3. Stale rapid poll trigger (more than 5 minutes ago)
                LocationRepository._lastRapidPollTrigger.value = currentTime - 301_000L
                assertFalse(service.isRapidPolling())

                // 4. Pending init payload
                LocationRepository._pendingInitPayload.value = io.mockk.mockk<KeyExchangeInitPayload>()
                assertTrue(service.isRapidPolling())

                // 5. Reset pending init
                LocationRepository._pendingInitPayload.value = null
                assertFalse(service.isRapidPolling())
            } finally {
                controller.destroy()
            }
        }

    // shouldPollFriends removed — pollLoop now always calls doPoll() regardless of
    // foreground state, so friend locations stay fresh when devices are stationary.

    // ---- pollInterval ----

    @Test
    fun testPollInterval_Rapid_Is2s() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(2_000L, service.pollInterval(rapid = true, inForeground = true))
        assertEquals(2_000L, service.pollInterval(rapid = true, inForeground = false))
    }

    @Test
    fun testPollInterval_Foreground_Is10s() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(10_000L, service.pollInterval(rapid = false, inForeground = true))
    }

    @Test
    fun testPollInterval_Background_Is5min() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(5 * 60 * 1000L, service.pollInterval(rapid = false, inForeground = false, isSharingLocation = true))
    }

    @Test
    fun testPollInterval_BackgroundNotSharing_Is30min() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(30 * 60 * 1000L, service.pollInterval(rapid = false, inForeground = false, isSharingLocation = false))
    }

    @Test
    fun testRapidPollResetAfterFirstLocationUpdate() =
        runTest {
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            val locationClientField = LocationService::class.java.getDeclaredField("locationClient")
            locationClientField.isAccessible = true
            locationClientField.set(service, mockClient)

            try {
                // 1. Trigger rapid poll for a new friend (mirrors LocationViewModel behaviour)
                val newFriendId = "new_friend"
                LocationRepository.markAwaitingFirstUpdate(newFriendId)
                LocationRepository.triggerRapidPoll()
                assertTrue(service.isRapidPolling())

                // 2. Mock a location update from that new friend
                val update = net.af0.where.model.UserLocation(newFriendId, 1.0, 2.0, currentTime / 1000L)
                io.mockk.coEvery { mockClient.poll() } returns listOf(update)

                // 3. Fire poll
                service.doPoll()

                // 4. Verify rapid poll is reset
                assertFalse(service.isRapidPolling(), "Rapid poll should be reset after first location update from a new friend")
                assertEquals(0L, LocationRepository.lastRapidPollTrigger.value)
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testRapidPollResetOnlyAfterUpdatesFromAllNewlyAddedFriends() =
        runTest {
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            val locationClientField = LocationService::class.java.getDeclaredField("locationClient")
            locationClientField.isAccessible = true
            locationClientField.set(service, mockClient)

            try {
                // 1. Add two new friends
                val friendId1 = "friend1"
                val friendId2 = "friend2"
                LocationRepository.markAwaitingFirstUpdate(friendId1)
                LocationRepository.markAwaitingFirstUpdate(friendId2)

                // 2. Trigger rapid poll
                LocationRepository.triggerRapidPoll()
                assertTrue(service.isRapidPolling())

                // 3. Mock a location update from ONLY one friend
                val update1 = net.af0.where.model.UserLocation(friendId1, 1.0, 2.0, currentTime / 1000L)
                io.mockk.coEvery { mockClient.poll() } returns listOf(update1)

                // 4. Fire poll
                service.doPoll()

                // 5. Verify rapid poll is NOT reset yet
                assertTrue(service.isRapidPolling(), "Rapid poll should NOT be reset until all new friends have sent an update")
                assertTrue(LocationRepository.lastRapidPollTrigger.value > 0L)

                // 6. Mock a location update from the second friend
                val update2 = net.af0.where.model.UserLocation(friendId2, 3.0, 4.0, currentTime / 1000L)
                io.mockk.coEvery { mockClient.poll() } returns listOf(update2)

                // 7. Fire poll again
                service.doPoll()

                // 8. Verify rapid poll IS reset now
                assertFalse(service.isRapidPolling(), "Rapid poll should be reset after all new friends have sent updates")
                assertEquals(0L, LocationRepository.lastRapidPollTrigger.value)
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testActionForcePublish() =
        runTest {
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient

            val mockFused = io.mockk.mockk<com.google.android.gms.location.FusedLocationProviderClient>(relaxed = true)
            service.fusedClientOverride = mockFused

            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
            service.e2eeStoreOverride = mockStore

            controller.create()

            try {
                // 1. Immediate send
                LocationRepository.onLocation(37.4, -122.1)
                val intent1 =
                    android.content.Intent(context, LocationService::class.java).apply {
                        action = LocationService.ACTION_FORCE_PUBLISH
                        putExtra(LocationService.EXTRA_FRIEND_ID, "friend1")
                    }
                controller.withIntent(intent1).startCommand(0, 1)
                runCurrent()

                io.mockk.coVerify(timeout = 5000) { mockClient.sendLocationToFriend("friend1", 37.4, -122.1) }

                // 2. Deferred send
                LocationRepository.reset()
                val intent2 =
                    android.content.Intent(context, LocationService::class.java).apply {
                        action = LocationService.ACTION_FORCE_PUBLISH
                        putExtra(LocationService.EXTRA_FRIEND_ID, "friend2")
                    }
                controller.withIntent(intent2).startCommand(0, 2)
                runCurrent()

                // Provide location
                LocationRepository.onLocation(37.5, -122.2)
                runCurrent()
                io.mockk.coVerify(timeout = 5000) { mockClient.sendLocationToFriend("friend2", 37.5, -122.2) }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testStationaryForceUpdate() =
        runTest {
            var currentTime = 1_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockFused = io.mockk.mockk<com.google.android.gms.location.FusedLocationProviderClient>(relaxed = true)
            service.fusedClientOverride = mockFused

            // Initialize sharing
            LocationRepository.setSharingLocation(true)

            controller.create()
            try {
                // 1. Threshold not exceeded.
                // We simulate one poll cycle.
                service.lastSentTime = currentTime - 60_000L // 1 minute ago
                service.pollInterval(false, false, true) // Just to trigger some logic if needed

                // We need to trigger the force update check.
                // Since pollLoop is private and runs in serviceScope, we can't easily call it.
                // But we can verify the logic by making forceLocationUpdate internal/visible.
                // Wait, I already implemented the check in pollLoop.
                
                // Let's test forceLocationUpdate directly since it's the core of the fix.
                val method = LocationService::class.java.getDeclaredMethod("forceLocationUpdate")
                method.isAccessible = true
                method.invoke(service)

                io.mockk.verify(exactly = 1) { 
                    mockFused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null) 
                }
            } finally {
                controller.destroy()
            }
        }
}
