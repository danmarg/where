package net.af0.where

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A geofence exit's HIGH-accuracy request ends once the device stops moving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceHighAccuracyTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource
    private lateinit var mockLocationProvider: LocationProvider
    private var now = 1_000_000_000L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        fakeLocationSource = ServiceFakeLocationSource()
        fakeLocationSource.onFriendsUpdated(listOf(mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)))
        LocationService.clock = { now }

        mockLocationProvider = mockk(relaxed = true)
        io.mockk.every { mockLocationProvider.requestPassiveUpdates() } returns true
        io.mockk.every { mockLocationProvider.requestActiveUpdates(any(), any(), any()) } returns true
        io.mockk.every { mockLocationProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.SUBMITTED
        // The exit's forced fix finds nothing, so only the fixes the test sends count.
        io.mockk.coEvery { mockLocationProvider.getCurrentLocation() } returns null
        io.mockk.coEvery { mockLocationProvider.getLastLocation() } returns null

        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
        io.mockk.mockkObject(UserPrefs)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.elapsedClock = { android.os.SystemClock.elapsedRealtime() }
    }

    private fun geofenceExit() =
        android.content.Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_GEOFENCE_EVENT
            putExtra(LocationService.EXTRA_GEOFENCE_LAT, 37.0)
            putExtra(LocationService.EXTRA_GEOFENCE_LNG, -122.0)
        }

    @Test
    fun highAccuracyEndsAfterTheDeviceStopsMoving() =
        runTest {
            // The send scheduler waits on virtual time and re-checks this clock; a frozen clock
            // would make advanceUntilIdle() spin forever once a send is due after the interval.
            LocationService.elapsedClock = { testScheduler.currentTime }
            (context as TestWhereApplication).userStore.setSharing(true)
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationProviderOverride = mockLocationProvider
            service.locationClientOverride = mockk(relaxed = true)
            service.e2eeManagerOverride = mockk(relaxed = true)
            service.locationSourceOverride = fakeLocationSource
            controller.create()
            advanceUntilIdle()

            val start = now
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)

            // Moving: fixes 1 km apart keep it on HIGH past the idle timeout.
            fakeLocationSource.onLocation(37.0, -122.0, null)
            advanceUntilIdle()
            now = start + 6 * MINUTE
            fakeLocationSource.onLocation(37.01, -122.0, null)
            advanceUntilIdle()
            now = start + 12 * MINUTE
            service.endHighAccuracyIfIdle(now)
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)

            // Arrived: jitter only. Ten minutes after the last real movement it drops back.
            fakeLocationSource.onLocation(37.0101, -122.0, null)
            advanceUntilIdle()
            now = start + 16 * MINUTE - 1
            service.endHighAccuracyIfIdle(now)
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)
            now = start + 16 * MINUTE
            service.endHighAccuracyIfIdle(now)
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            assertEquals(LocationService.BALANCED_INTERVAL_MS, service.currentInterval)
            verify { mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, LocationService.BALANCED_INTERVAL_MS, any()) }

            // The next departure switches back to HIGH.
            service.onStartCommand(geofenceExit(), 0, 2)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)
            controller.destroy()
        }

    private companion object {
        const val MINUTE = 60_000L
    }
}
