package net.af0.where

import android.app.Application
import android.content.Intent
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
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
@Config(sdk = [33], application = WhereApplication::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowLog.stream = System.out
        LocationRepository.reset()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.locationSource = LocationRepository
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun getServiceIsRegistered(service: LocationService): Boolean {
        val field = LocationService::class.java.getDeclaredField("isRegistered")
        field.isAccessible = true
        return field.get(service) as Boolean
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
            io.mockk.verify(exactly = 1) { mockFusedClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<android.os.Looper>()) }

            // Multiple startCommand calls must not attempt to re-register location updates.
            controller.startCommand(0, 1)
            controller.startCommand(0, 2)

            assertTrue(getServiceIsRegistered(service))
            io.mockk.verify(exactly = 1) { mockFusedClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<android.os.Looper>()) }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun testSendLocationThrottle_Movement() =
        runTest {
            var currentTime = 100_000L
            LocationService.clock = { currentTime }

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationClientOverride = mockClient
            controller.create()

            try {
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

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationClientOverride = mockClient
            controller.create()

            try {
                // 1. Initial send
                service.sendLocationIfNeeded(37.7, -122.4, true, false)
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }

                // 2. Immediate heartbeat send (throttled)
                service.sendLocationIfNeeded(37.7, -122.4, true, false)
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }

                // 3. Heartbeat after 4 mins (throttled)
                currentTime += 4 * 60 * 1000L
                service.sendLocationIfNeeded(37.7, -122.4, true, false)
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }

                // 4. Heartbeat after 6 mins (allowed)
                currentTime += 2 * 60 * 1000L
                service.sendLocationIfNeeded(37.7, -122.4, true, false)
                io.mockk.coVerify(exactly = 2) { mockClient.sendLocation(any(), any(), any()) }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testIsRapidPolling() =
        runTest(StandardTestDispatcher()) {
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
                // Default
                assertFalse(service.isRapidPolling())

                // Triggered recently
                LocationRepository.triggerRapidPoll()
                assertTrue(service.isRapidPolling())

                // Invite pending
                LocationRepository.resetRapidPoll()
                assertFalse(service.isRapidPolling())
                LocationRepository.onPendingInit(io.mockk.mockk())
                assertTrue(service.isRapidPolling())

                // Pending QR
                LocationRepository.onPendingInit(null)
                assertFalse(service.isRapidPolling())
                io.mockk.coEvery { mockStore.pendingQrPayload() } returns io.mockk.mockk()
                assertTrue(service.isRapidPolling())
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testShouldPollFriends() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        controller.create()

        try {
            // Rapid -> Yes
            assertTrue(service.shouldPollFriends(rapid = true, inForeground = false))

            // Foreground -> Yes
            assertTrue(service.shouldPollFriends(rapid = false, inForeground = true))

            // Background + Recently updated -> Yes
            service.lastSentTime = System.currentTimeMillis() - 10_000
            assertTrue(service.shouldPollFriends(rapid = false, inForeground = false))

            // Background + Not recently updated -> No
            service.lastSentTime = System.currentTimeMillis() - 60_000
            assertFalse(service.shouldPollFriends(rapid = false, inForeground = false))
        } finally {
            controller.destroy()
        }
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

                io.mockk.coVerify { mockClient.sendLocationToFriend("friend1", 37.4, -122.1) }

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
                io.mockk.coVerify { mockClient.sendLocationToFriend("friend2", 37.5, -122.2) }
            } finally {
                controller.destroy()
            }
        }
}
