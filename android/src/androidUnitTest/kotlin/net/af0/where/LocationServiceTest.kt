package net.af0.where

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WhereApplication::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        ShadowLog.stream = System.out
        LocationRepository.reset()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.locationSource = LocationRepository
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
        controller.create()

        assertTrue(getServiceIsRegistered(service))

        // Multiple startCommand calls must not attempt to re-register location updates.
        controller.startCommand(0, 1)
        controller.startCommand(0, 2)

        assertTrue(getServiceIsRegistered(service))
    }

    @Test
    fun testSendLocationThrottle_Movement() =
        runTest {
            var currentTime = 100_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

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
        }

    @Test
    fun testSendLocationThrottle_Heartbeat() =
        runTest {
            var currentTime = 1_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

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
        }

    @Test
    fun testIsRapidPolling() =
        runTest {
            // Use a large initial time so that trigger=0 is not "recent"
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            controller.create()

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
        }

    // ---- shouldPollFriends ----

    @Test
    fun testShouldPollFriends_ForegroundNotRapid_ShouldPoll() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertTrue(service.shouldPollFriends(rapid = false, inForeground = true))
    }

    @Test
    fun testShouldPollFriends_BackgroundNotRapid_NotRecentlySent_ShouldNotPoll() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        service.lastSentTime = 100_000L
        LocationService.clock = { 200_000L } // 100s later
        assertFalse(service.shouldPollFriends(rapid = false, inForeground = false))
    }

    @Test
    fun testShouldPollFriends_BackgroundNotRapid_RecentlySent_ShouldPoll() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        service.lastSentTime = 100_000L
        LocationService.clock = { 110_000L } // 10s later
        assertTrue(service.shouldPollFriends(rapid = false, inForeground = false))
    }

    @Test
    fun testShouldPollFriends_RapidInBackground_ShouldPoll() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertTrue(service.shouldPollFriends(rapid = true, inForeground = false))
    }

    @Test
    fun testShouldPollFriends_RapidInForeground_ShouldPoll() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertTrue(service.shouldPollFriends(rapid = true, inForeground = true))
    }

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
        assertEquals(5 * 60 * 1000L, service.pollInterval(rapid = false, inForeground = false))
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

            // 1. Trigger rapid poll
            LocationRepository.triggerRapidPoll()
            assertTrue(service.isRapidPolling())

            // 2. Mock a location update from a new friend
            val newFriendId = "new_friend"
            val update = net.af0.where.model.UserLocation(newFriendId, 1.0, 2.0, currentTime / 1000L)
            io.mockk.coEvery { mockClient.poll() } returns listOf(update)

            // 3. Fire poll
            service.doPoll()

            // 4. Verify rapid poll is reset
            assertFalse(service.isRapidPolling(), "Rapid poll should be reset after first location update from a new friend")
            assertEquals(0L, LocationRepository.lastRapidPollTrigger.value)
        }

    @Test
    fun testActionForcePublish() =
        runTest {
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            val locationClientField = LocationService::class.java.getDeclaredField("locationClient")
            locationClientField.isAccessible = true
            locationClientField.set(service, mockClient)

            controller.create()

            // 1. Immediate send
            LocationRepository.onLocation(37.4, -122.1)
            val intent1 =
                android.content.Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_FORCE_PUBLISH
                    putExtra(LocationService.EXTRA_FRIEND_ID, "friend1")
                }
            controller.withIntent(intent1).startCommand(0, 1)

            io.mockk.coVerify(timeout = 5000) { mockClient.sendLocationToFriend("friend1", 37.4, -122.1) }

            // 2. Deferred send
            LocationRepository.reset()
            val intent2 =
                android.content.Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_FORCE_PUBLISH
                    putExtra(LocationService.EXTRA_FRIEND_ID, "friend2")
                }
            controller.withIntent(intent2).startCommand(0, 2)

            // Provide location
            LocationRepository.onLocation(37.5, -122.2)
            io.mockk.coVerify(timeout = 5000) { mockClient.sendLocationToFriend("friend2", 37.5, -122.2) }
        }
}
