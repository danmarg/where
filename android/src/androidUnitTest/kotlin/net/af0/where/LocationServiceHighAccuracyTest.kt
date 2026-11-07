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

/** A geofence exit's HIGH-accuracy request ends [LocationService.HIGH_ACCURACY_IDLE_MS] after the last exit. */
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

    /**
     * An exit triggered at [lat]. Each one replants the fence there, so a later exit must be at
     * least the fence radius away to count as a departure from it: [STEP] (~555 m) per exit.
     */
    private fun geofenceExit(lat: Double = 37.0) =
        android.content.Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_GEOFENCE_EVENT
            putExtra(LocationService.EXTRA_GEOFENCE_LAT, lat)
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

    @Test
    fun highAccuracyEndsOneWindowAfterTheLastGeofenceExit() =
        runTest {
            val (controller, service) = buildService()
            // Startup registered once at BALANCED.
            verify(exactly = 1) { mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, any(), any()) }

            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)
            verify(exactly = 1) {
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.HIGH, LocationService.HIGH_INTERVAL_MS, any())
            }

            // Still moving: a second exit 8 min later restarts the window.
            offset += 8 * MINUTE
            service.onStartCommand(geofenceExit(37.0 + STEP), 0, 2)
            advanceUntilIdle()
            val secondExit = service.lastGeofenceExitAt
            service.endHighAccuracyIfIdle(secondExit + IDLE - 1)
            assertEquals(LocationAccuracy.HIGH, service.currentPriority, "just under the window after the last exit: still HIGH")

            // Arrived: a full window without an exit drops back and re-registers at BALANCED.
            service.endHighAccuracyIfIdle(secondExit + IDLE)
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            assertEquals(LocationService.BALANCED_INTERVAL_MS, service.currentInterval)
            verify(exactly = 2) {
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, LocationService.BALANCED_INTERVAL_MS, any())
            }

            // The next departure switches back to HIGH.
            service.onStartCommand(geofenceExit(37.0 + 2 * STEP), 0, 3)
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

            // A full window of elapsed time does end it, through the same poll-loop path.
            offset += IDLE
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            controller.destroy()
        }

    /** Moves the elapsed clock to [target] without running the timers in between. */
    private fun TestScope.setElapsed(target: Long) {
        offset = target - testScheduler.currentTime
    }

    @Test
    fun anExitJustBeforeTheTimeoutKeepsHighThroughThePollLoop() =
        runTest {
            val (controller, service) = buildService()
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            val firstExit = service.lastGeofenceExitAt

            // A second exit a minute before the first window ends.
            setElapsed(firstExit + IDLE - MINUTE)
            service.onStartCommand(geofenceExit(37.0 + STEP), 0, 2)
            advanceUntilIdle()
            val secondExit = service.lastGeofenceExitAt

            // Past the first exit's window: the real poll loop must not drop HIGH.
            setElapsed(firstExit + IDLE + MINUTE)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority, "the second exit restarted the window")

            // A full window after the second exit, the poll loop drops it.
            setElapsed(secondExit + IDLE)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            controller.destroy()
        }

    @Test
    fun resumingSharingReplantsTheFenceAndIgnoresTheOldFencesExit() =
        runTest {
            val (controller, service) = buildService()
            // An exit at 37.0 plants a fence there; stopping sharing removes it.
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            val app = context as TestWhereApplication
            app.userStore.setSharing(false)
            advanceUntilIdle()
            assertEquals(false, service.geofenceMayBeRegistered)
            val exitBefore = service.lastGeofenceExitAt

            // The device is now just past where the old fence would report its exit. Resuming
            // sharing plants a new fence here.
            val here = 37.0 + STEP
            fakeLocationSource.onLocation(here, -122.0, null)
            advanceUntilIdle()
            app.userStore.setSharing(true)
            advanceUntilIdle()
            verify { mockLocationProvider.setGeofenceAt(here, -122.0, any()) }
            assertEquals(here to -122.0, service.geofenceCenter)

            // The old fence's exit, already pending, arrives now, triggered here: inside the new
            // fence, so not a departure from it.
            setElapsed(exitBefore + MINUTE)
            service.onStartCommand(geofenceExit(here), 0, 2)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority, "a stale exit must not start a HIGH window")
            assertEquals(exitBefore, service.lastGeofenceExitAt)

            // A real departure from the new fence still counts.
            service.onStartCommand(geofenceExit(here + STEP), 0, 3)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)
            controller.destroy()
        }

    @Test
    fun anExitWithNoFenceRegisteredIsIgnored() =
        runTest {
            val (controller, service) = buildService()
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            val app = context as TestWhereApplication
            app.userStore.setSharing(false)
            advanceUntilIdle()
            // No known location, so resuming can't plant a fence.
            app.userStore.setSharing(true)
            advanceUntilIdle()
            assertEquals(false, service.geofenceMayBeRegistered)
            val exitBefore = service.lastGeofenceExitAt

            setElapsed(exitBefore + MINUTE)
            service.onStartCommand(geofenceExit(37.0 + STEP), 0, 2)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
            assertEquals(exitBefore, service.lastGeofenceExitAt)
            controller.destroy()
        }

    @Test
    fun losingTheLastFriendResetsToBalanced() =
        runTest {
            val (controller, service) = buildService()
            service.onStartCommand(geofenceExit(), 0, 1)
            advanceUntilIdle()
            assertEquals(LocationAccuracy.HIGH, service.currentPriority)

            fakeLocationSource.onFriendsUpdated(emptyList())
            advanceUntilIdle()
            assertEquals(LocationAccuracy.BALANCED, service.currentPriority, "nothing to track for: back to BALANCED")
            assertEquals(LocationService.BALANCED_INTERVAL_MS, service.currentInterval)
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
            // And the registration that actually went out on resume was BALANCED, after the HIGH one.
            io.mockk.verifyOrder {
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.HIGH, LocationService.HIGH_INTERVAL_MS, any())
                mockLocationProvider.removeActiveUpdates()
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, LocationService.BALANCED_INTERVAL_MS, any())
            }
            controller.destroy()
        }

    private companion object {
        const val MINUTE = 60_000L
        const val IDLE = LocationService.HIGH_ACCURACY_IDLE_MS

        /** ~555 m of latitude: more than the 400 m fence radius. */
        const val STEP = 0.005
    }
}
