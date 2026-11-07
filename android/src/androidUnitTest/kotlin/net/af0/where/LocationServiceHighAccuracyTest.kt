package net.af0.where

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** A geofence exit's HIGH-accuracy request ends 10 minutes after the last exit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceHighAccuracyTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource
    private lateinit var mockLocationProvider: LocationProvider
    private var now = 1_000_000_000L
    private var offset = 0L
    private val friend = mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        fakeLocationSource = ServiceFakeLocationSource()
        fakeLocationSource.onFriendsUpdated(listOf(friend))
        LocationService.clock = { now }

        mockLocationProvider = mockk(relaxed = true)
        io.mockk.every { mockLocationProvider.requestPassiveUpdates() } returns true
        io.mockk.every { mockLocationProvider.requestActiveUpdates(any(), any(), any()) } returns true
        io.mockk.every { mockLocationProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.SUBMITTED
        // The exit's forced fix finds nothing.
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

    private fun TestScope.buildService(): Pair<org.robolectric.android.controller.ServiceController<LocationService>, LocationService> {
        // The send scheduler waits on virtual time and re-checks elapsedClock; a frozen clock
        // would make advanceUntilIdle() spin forever once a send is due after the interval.
        // `offset` lets the test jump the clock forward without running every timer.
        LocationService.elapsedClock = { offset + testScheduler.currentTime }
        (context as TestWhereApplication).userStore.setSharing(true)
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        service.locationProviderOverride = mockLocationProvider
        service.locationClientOverride = mockk(relaxed = true)
        // The poll loop refreshes the friend list from here; with none it stops registering
        // location (and the service stops itself), so the exit's HIGH request would never go out.
        val e2ee = mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
        io.mockk.coEvery { e2ee.listFriends() } returns listOf(friend)
        service.e2eeManagerOverride = e2ee
        service.locationSourceOverride = fakeLocationSource
        controller.create()
        advanceUntilIdle()
        return controller to service
    }

    private fun highRegistrations() =
        io.mockk.verify { mockLocationProvider.requestActiveUpdates(LocationAccuracy.HIGH, LocationService.HIGH_INTERVAL_MS, any()) }

    @Test
    fun highAccuracyEndsTenMinutesAfterTheLastGeofenceExit() =
        runTest {
            val (controller, service) = buildService()
            // Startup registered once at BALANCED.
            verify(exactly = 1) { mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, any(), any()) }

            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)
            highRegistrations()

            // Still moving: a second exit 8 min later restarts the 10 min.
            offset += 8 * MINUTE
            service.onStartCommand(geofenceExit(), 0, 2)
            advanceUntilIdle()
            val secondExit = service.lastGeofenceExitAt
            service.endHighAccuracyIfIdle(secondExit + 10 * MINUTE - 1)
            assertEquals(LocationAccuracy.HIGH, service.currentPriority, "just under 10 min after the last exit: still HIGH")

            // Arrived: no exit for 10 min drops back and re-registers at BALANCED.
            service.endHighAccuracyIfIdle(secondExit + 10 * MINUTE)
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            assertEquals(LocationService.BALANCED_INTERVAL_MS, service.currentInterval)
            verify(exactly = 2) {
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, LocationService.BALANCED_INTERVAL_MS, any())
            }

            // The next departure switches back to HIGH.
            service.onStartCommand(geofenceExit(), 0, 3)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)
            controller.destroy()
        }

    @Test
    fun wallClockChangesDoNotMoveTheTimeout() =
        runTest {
            val (controller, service) = buildService()
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            // The wall clock jumps a day forward; elapsed time hasn't moved. The poll loop's
            // check must not see a timeout.
            now += 24 * 60 * MINUTE
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)

            // Ten minutes of elapsed time do end it, through the same poll-loop path.
            offset += 10 * MINUTE
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            controller.destroy()
        }

    @Test
    fun stoppingSharingResetsToBalanced() =
        runTest {
            val (controller, service) = buildService()
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)

            val app = context as TestWhereApplication
            app.userStore.setSharing(false)
            advanceUntilIdle()
            app.userStore.setSharing(true)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority, "a new sharing session starts at BALANCED")
            assertEquals(LocationService.BALANCED_INTERVAL_MS, service.currentInterval)
            controller.destroy()
        }

    private companion object {
        const val MINUTE = 60_000L
    }
}
