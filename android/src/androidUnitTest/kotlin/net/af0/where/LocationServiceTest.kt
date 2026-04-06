package net.af0.where

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.test.runTest
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WhereApplication::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        ShadowLog.stream = System.out
        val field = LocationRepository::class.java.getDeclaredField("_lastLocation")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(LocationRepository) as kotlinx.coroutines.flow.MutableStateFlow<Pair<Double, Double>?>
        flow.value = null
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
        controller.create()

        assertTrue(getServiceIsRegistered(service))

        // Multiple startCommand calls must not attempt to re-register location updates.
        controller.startCommand(0, 1)
        controller.startCommand(0, 2)

        assertTrue(getServiceIsRegistered(service))
    }

    @Test
    fun testSendLocationThrottle_Movement() = runTest {
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

        // 2. Immediate second send (throttled)
        service.sendLocationIfNeeded(37.8, -122.5, false, false)
        io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any()) }

        // 3. Send after 16s (not throttled - movement throttle is 15s)
        currentTime += 16_000L
        service.sendLocationIfNeeded(37.9, -122.6, false, false)
        io.mockk.coVerify(exactly = 2) { mockClient.sendLocation(any(), any(), any()) }
    }

    @Test
    fun testSendLocationThrottle_Heartbeat() = runTest {
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
}
