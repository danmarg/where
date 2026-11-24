package net.af0.where

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-Droid's stationary mode (#483): after [StationaryPolicy.idleMs] without a credible
 * move the active request goes off (PASSIVE) and the significant-motion trigger brings it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceStationaryTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource
    private lateinit var mockLocationProvider: LocationProvider
    private var offset = 0L
    private val friend = mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
    private val onMotion = slot<() -> Unit>()
    private var activeFix: ((Location) -> Unit)? = null
    private var onLocation: ((Double, Double, Double?) -> Unit)? = null
    private var triggerAvailable = true

    // The side effects stationary mode drives, as the provider sees them.
    private var armed = false
    private var requested: LocationAccuracy? = null
    private var requestCalls = 0

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        fakeLocationSource = ServiceFakeLocationSource()
        fakeLocationSource.onFriendsUpdated(listOf(friend))
        LocationService.clock = { 1_000_000_000L }

        mockLocationProvider = mockk(relaxed = true)
        every { mockLocationProvider.requestPassiveUpdates() } returns true
        every { mockLocationProvider.requestActiveUpdates(any(), any(), any()) } answers {
            requested = firstArg()
            requestCalls++
            true
        }
        every { mockLocationProvider.removeActiveUpdates() } answers { requested = null }
        every { mockLocationProvider.cancelMotionTrigger() } answers { armed = false }
        every { mockLocationProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.UNSUPPORTED
        every { mockLocationProvider.armMotionTrigger(capture(onMotion)) } answers {
            armed = triggerAvailable
            triggerAvailable
        }
        every { mockLocationProvider.setFixListener(any()) } answers { activeFix = firstArg() }
        every { mockLocationProvider.init(any(), any()) } answers { onLocation = secondArg() }
        io.mockk.coEvery { mockLocationProvider.getCurrentLocation(any()) } returns null
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

    private fun TestScope.buildService(
        friendPoller: FriendPoller? = null,
    ): Pair<org.robolectric.android.controller.ServiceController<LocationService>, LocationService> {
        LocationService.elapsedClock = { offset + testScheduler.currentTime }
        (context as TestWhereApplication).userStore.setSharing(true)
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        service.locationProviderOverride = mockLocationProvider
        service.locationClientOverride = mockk(relaxed = true)
        val e2ee = mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
        io.mockk.coEvery { e2ee.listFriends() } returns listOf(friend)
        service.e2eeManagerOverride = e2ee
        service.locationSourceOverride = fakeLocationSource
        service.friendPollerOverride = friendPoller
        controller.create()
        advanceUntilIdle()
        return controller to service
    }

    private fun TestScope.elapsed() = offset + testScheduler.currentTime

    /** Moves the elapsed clock to [target] without running the timers in between. */
    private fun TestScope.setElapsed(target: Long) {
        offset = target - testScheduler.currentTime
    }

    /** A fix [northMeters] north of the origin, taken [ageMs] ago. */
    private fun TestScope.fix(
        northMeters: Double = 0.0,
        accuracy: Float = 10f,
        ageMs: Long = 0L,
        provider: String = "gps",
    ) = Location(provider).apply {
        latitude = 37.0 + northMeters / 111_000.0
        longitude = -122.0
        this.accuracy = accuracy
        elapsedRealtimeNanos = (elapsed() - ageMs) * 1_000_000
        time = elapsed()
    }

    /**
     * Stationary or not, consistently: the policy's mode, the PASSIVE request and the armed trigger
     * all agree. Each used to be set by hand on every transition.
     */
    private fun assertStationary(
        service: LocationService,
        expected: Boolean,
        message: String = "",
    ) {
        assertEquals(expected, service.stationary.mode == StationaryPolicy.Mode.STATIONARY, "mode: $message")
        assertEquals(expected, armed, "trigger armed: $message")
        assertEquals(expected, requested == LocationAccuracy.PASSIVE, "requested $requested: $message")
    }

    /** The trigger fires: one-shot, so it's disarmed first, as the sensor does. */
    private fun fireMotion() {
        armed = false
        onMotion.captured.invoke()
    }

    private fun TestScope.goStationary(service: LocationService) {
        setElapsed(service.stationary.lastMovedAt + IDLE)
        service.enterStationaryIfIdle(elapsed())
        assertStationary(service, true)
    }

    @Test
    fun goesStationaryOneWindowAfterTheLastMove() =
        runTest {
            val (controller, service) = buildService()
            val start = service.stationary.lastMovedAt
            service.enterStationaryIfIdle(start + IDLE - 1)
            assertStationary(service, false)
            verify(exactly = 0) { mockLocationProvider.armMotionTrigger(any()) }

            service.enterStationaryIfIdle(start + IDLE)
            assertStationary(service, true)
            verify { mockLocationProvider.requestActiveUpdates(LocationAccuracy.PASSIVE, any(), any()) }
            controller.destroy()
        }

    @Test
    fun thePollLoopEntersStationary() =
        runTest {
            val (controller, service) = buildService()
            setElapsed(service.stationary.lastMovedAt + IDLE)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertStationary(service, true)
            controller.destroy()
        }

    @Test
    fun motionRestoresBalancedAndWakesThePoll() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)
            setElapsed(elapsed() + 60 * MINUTE)

            fireMotion()
            advanceUntilIdle()
            assertStationary(service, false)
            assertEquals(elapsed(), service.stationary.lastMovedAt, "leaving restarts the countdown")
            verify(exactly = 2) {
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, LocationService.BALANCED_INTERVAL_MS, any())
            }

            // A late duplicate trigger is harmless.
            fireMotion()
            advanceUntilIdle()
            verify(exactly = 2) {
                mockLocationProvider.requestActiveUpdates(LocationAccuracy.BALANCED, LocationService.BALANCED_INTERVAL_MS, any())
            }
            controller.destroy()
        }

    @Test
    fun withoutATriggerItStaysBalancedUntilOneArms() =
        runTest {
            triggerAvailable = false
            val (controller, service) = buildService()
            val start = service.stationary.lastMovedAt
            service.enterStationaryIfIdle(start + IDLE)
            service.enterStationaryIfIdle(start + 2 * IDLE)
            assertStationary(service, false)
            verify(exactly = 0) { mockLocationProvider.requestActiveUpdates(LocationAccuracy.PASSIVE, any(), any()) }

            // A refusal can be transient: a later wake asks again.
            triggerAvailable = true
            service.enterStationaryIfIdle(start + 3 * IDLE)
            assertStationary(service, true)
            controller.destroy()
        }

    @Test
    fun anOutageRestartsTheCountdown() =
        runTest {
            val (controller, service) = buildService()
            val start = service.stationary.lastMovedAt
            // Location services go off for hours, sharing still on.
            shadowOf(context.getSystemService(android.location.LocationManager::class.java))
                .setLocationEnabled(false)
            service.onStartCommand(null, 0, 1)
            advanceUntilIdle()
            setElapsed(start + 5 * IDLE)
            shadowOf(context.getSystemService(android.location.LocationManager::class.java))
                .setLocationEnabled(true)
            service.onStartCommand(null, 0, 2)
            advanceUntilIdle()
            assertEquals(elapsed(), service.stationary.lastMovedAt, "the device may have moved, unobserved")
            service.enterStationaryIfIdle(elapsed())
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun onlyACredibleMoveLeavesStationary() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)

            service.noteFix(fix())
            assertNotNull(service.stationary.anchor, "the first credible fix seeds the anchor")
            // A millisecond apart: the policy ignores a fix no newer than the last one.
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 1_000.0, accuracy = 500f))
            assertStationary(service, true, "a coarse fix isn't movement")
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 1_000.0, ageMs = 10 * MINUTE))
            assertStationary(service, true, "a stale fix isn't movement")
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 1_000.0, provider = "network"))
            assertStationary(service, true, "a network fix isn't movement")
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 100.0))
            assertStationary(service, true, "under the movement radius")
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 280.0, accuracy = 90f))
            assertStationary(service, true, "within 200 m plus both fixes' error")

            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 1_000.0))
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 1_020.0))
            assertStationary(service, false, "the sensor missed a real move")
            controller.destroy()
        }

    @Test
    fun coarseActiveFixesDoNotHoldOffStationary() =
        runTest {
            val (controller, service) = buildService()
            val start = service.stationary.lastMovedAt
            val listener = assertNotNull(activeFix)
            listener(fix())
            // Indoors: network fixes wander hundreds of meters.
            setElapsed(start + 5 * MINUTE)
            listener(fix(northMeters = 400.0, accuracy = 300f))
            setElapsed(start + 10 * MINUTE)
            listener(fix(northMeters = -400.0, accuracy = 300f))
            assertEquals(start, service.stationary.lastMovedAt)

            // A real move does, once confirmed.
            setElapsed(start + 10 * MINUTE + 1)
            listener(fix(northMeters = 500.0))
            setElapsed(start + 10 * MINUTE + 2)
            listener(fix(northMeters = 520.0))
            assertEquals(start + 10 * MINUTE + 2, service.stationary.lastMovedAt)
            service.enterStationaryIfIdle(start + IDLE)
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun reRegisteringDoesNotRestartTheCountdown() =
        runTest {
            val (controller, service) = buildService()
            val start = service.stationary.lastMovedAt
            // As the watchdog does.
            setElapsed(start + 10 * MINUTE)
            service.isRegistered = false
            service.onStartCommand(null, 0, 1)
            advanceUntilIdle()
            assertEquals(start, service.stationary.lastMovedAt)
            controller.destroy()
        }

    @Test
    fun stoppingSharingDisarmsTheTriggerAndTheNextSessionStartsMoving() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)

            val app = context as TestWhereApplication
            app.userStore.setSharing(false)
            advanceUntilIdle()
            assertStationary(service, false)

            setElapsed(elapsed() + 60 * MINUTE)
            app.userStore.setSharing(true)
            advanceUntilIdle()
            assertEquals(elapsed(), service.stationary.lastMovedAt, "a new session starts a fresh countdown")
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun theAnchorSurvivesEnteringStationary() =
        runTest {
            val (controller, service) = buildService()
            service.noteFix(fix())
            goStationary(service)
            // Two credible fixes after a missed departure.
            service.noteFix(fix(northMeters = 1_000.0))
            setElapsed(elapsed() + 1)
            service.noteFix(fix(northMeters = 1_020.0))
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun theForegroundEndsStationaryAndHoldsItOff() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)
            fakeLocationSource.setAppForeground(true)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertStationary(service, false)

            // Still visible a whole window later: the poll loop doesn't go stationary.
            setElapsed(elapsed() + 2 * IDLE)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun aLostTriggerEndsStationary() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)
            service.maintainStationary(inForeground = false)
            assertStationary(service, true, "re-armed")

            triggerAvailable = false
            service.maintainStationary(inForeground = false)
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun anOutageWhileStationaryResumesBalanced() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)
            val lm = context.getSystemService(android.location.LocationManager::class.java)
            shadowOf(lm).setLocationEnabled(false)
            service.onStartCommand(null, 0, 1)
            advanceUntilIdle()
            shadowOf(lm).setLocationEnabled(true)
            service.onStartCommand(null, 0, 2)
            advanceUntilIdle()
            assertStationary(service, false, "the device may have moved, unobserved")
            controller.destroy()
        }

    @Test
    fun approximateLocationNeverGoesStationary() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val (controller, service) = buildService()
            assertEquals(StationaryPolicy.Mode.MOVING, service.stationary.mode, "tracking on approximate location")
            service.enterStationaryIfIdle(service.stationary.lastMovedAt + IDLE)
            assertStationary(service, false)
            verify(exactly = 0) { mockLocationProvider.armMotionTrigger(any()) }
            controller.destroy()
        }

    @Test
    fun theMotionWakeLockCoversTheIterationTheTriggerWakesNotOneAlreadyRunning() =
        runTest {
            // Each poll waits on the next gate, so iterations can be held mid-way.
            val gates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()
            val poller = mockk<FriendPoller>(relaxed = true)
            io.mockk.coEvery { poller.poll(any()) } coAnswers { gates.removeFirstOrNull()?.await() }
            val (controller, service) = buildService(friendPoller = poller)
            val motionLock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
            assertEquals("where:motion", shadowOf(motionLock).tag)
            goStationary(service)

            // An iteration is mid-poll when the trigger fires.
            val running = kotlinx.coroutines.CompletableDeferred<Unit>()
            val woken = kotlinx.coroutines.CompletableDeferred<Unit>()
            gates += running
            gates += woken
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            fireMotion()
            assertTrue(motionLock.isHeld, "taken inside the sensor's own wake lock")

            running.complete(Unit)
            advanceUntilIdle()
            assertTrue(motionLock.isHeld, "the iteration already running must not release it")

            woken.complete(Unit)
            advanceUntilIdle()
            assertFalse(motionLock.isHeld, "the woken iteration releases it when done")
            controller.destroy()
        }

    @Test
    fun aLongFixThatFailsAtOnceIsRetriedEvenWithALastKnownFix() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)
            // The fresh fix fails; the heartbeat falls back to the cached one.
            io.mockk.coEvery { mockLocationProvider.getLastLocation() } answers { fix(ageMs = 60 * MINUTE) }
            val long = service.stationary.longFixTimeoutMs
            setElapsed(elapsed() + service.stationary.longFixEveryMs)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 1) { mockLocationProvider.getCurrentLocation(long) }

            setElapsed(elapsed() + 6 * MINUTE)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 2) { mockLocationProvider.getCurrentLocation(long) }
            controller.destroy()
        }

    @Test
    fun aTriggerAfterMaintenanceLeftIsIgnored() =
        runTest {
            val (controller, service) = buildService()
            val motionLock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
            goStationary(service)
            val staleTrigger = onMotion.captured
            // The foreground ends stationary first; the trigger's event was already queued.
            service.maintainStationary(inForeground = true)
            assertStationary(service, false)
            advanceUntilIdle()
            val registrations = requestCalls
            staleTrigger.invoke()
            advanceUntilIdle()
            assertFalse(motionLock.isHeld, "no second wake")
            assertEquals(registrations, requestCalls, "no re-registration")
            assertStationary(service, false)

            // And the next stationary period arms afresh.
            setElapsed(elapsed() + IDLE)
            service.enterStationaryIfIdle(elapsed())
            assertStationary(service, true)
            controller.destroy()
        }

    @Test
    fun maintenanceAfterATriggerIsANoOp() =
        runTest {
            val (controller, service) = buildService()
            goStationary(service)
            fireMotion()
            val registrations = requestCalls
            service.maintainStationary(inForeground = true)
            service.maintainStationary(inForeground = false)
            assertEquals(registrations, requestCalls)
            assertStationary(service, false)
            controller.destroy()
        }

    @Test
    fun theStaleRegistrationWatchdogIgnoresWallClockJumps() =
        runTest {
            var wall = 1_000_000_000L
            LocationService.clock = { wall }
            val (controller, service) = buildService()
            val registrations = requestCalls
            assertTrue(registrations > 0 && service.isRegistered, "registered at startup")

            // The user (or NTP) moves the wall clock forward past the threshold; no real time passed.
            wall += 2 * LocationService.STALE_REGISTRATION_THRESHOLD_MS
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertEquals(registrations, requestCalls, "a wall-clock jump is not a stale registration")

            // Elapsed time past the threshold with no callback still re-registers.
            setElapsed(elapsed() + 2 * LocationService.STALE_REGISTRATION_THRESHOLD_MS)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertTrue(requestCalls > registrations, "elapsed staleness re-registers")
            controller.destroy()
        }

    @Test
    fun aSilentlyDroppedPassiveListenerIsReRegisteredWhileStationary() =
        runTest {
            val (controller, service) = buildService()
            // Long after the passive registration at startup, but the passive listener heard this
            // app's BALANCED fixes until now.
            setElapsed(service.stationary.lastMovedAt + 2 * LocationService.STALE_REGISTRATION_THRESHOLD_MS)
            onLocation!!(37.0, -122.0, null)
            service.enterStationaryIfIdle(elapsed())
            assertStationary(service, true)
            service.maintainStationary(inForeground = false)
            verify(exactly = 0) { mockLocationProvider.removePassiveUpdates() }

            // No passive callback since, though the trigger stays armed: an OEM dropped it.
            setElapsed(elapsed() + LocationService.STALE_REGISTRATION_THRESHOLD_MS - 1)
            service.maintainStationary(inForeground = false)
            verify(exactly = 0) { mockLocationProvider.removePassiveUpdates() }
            setElapsed(elapsed() + 1)
            service.maintainStationary(inForeground = false)
            verify(exactly = 1) { mockLocationProvider.removePassiveUpdates() }
            verify(exactly = 2) { mockLocationProvider.requestPassiveUpdates() }
            assertStationary(service, true, "re-registered; still stationary")

            // Re-registering fails: active tracking resumes, and passive is retried on later
            // registrations so stationary can come back.
            every { mockLocationProvider.requestPassiveUpdates() } returns false
            setElapsed(elapsed() + LocationService.STALE_REGISTRATION_THRESHOLD_MS)
            service.maintainStationary(inForeground = false)
            assertStationary(service, false)
            assertEquals(LocationAccuracy.BALANCED, requested)
            every { mockLocationProvider.requestPassiveUpdates() } returns true
            // Startup, the refresh that worked, the one that failed; leaving doesn't retry at once.
            val attempts = 3
            verify(exactly = attempts) { mockLocationProvider.requestPassiveUpdates() }
            service.onStartCommand(null, 0, 1)
            advanceUntilIdle()
            verify(exactly = attempts) { mockLocationProvider.requestPassiveUpdates() }
            assertFalse(service.isPassiveRegistered, "not retried on every call")
            setElapsed(elapsed() + LocationService.PASSIVE_RETRY_INTERVAL_MS)
            service.onStartCommand(null, 0, 2)
            advanceUntilIdle()
            assertTrue(service.isPassiveRegistered, "retried though the BALANCED registration is unchanged")
            controller.destroy()
        }

    @Test
    fun aPassiveListenerDroppedBeforeEnteringIsRefreshedOnTheFirstWake() =
        runTest {
            val (controller, service) = buildService()
            // No passive callback since startup: an OEM dropped it during the countdown. Entering
            // re-registers nothing passive, so it's no sign of life.
            setElapsed(service.stationary.lastMovedAt + 2 * LocationService.STALE_REGISTRATION_THRESHOLD_MS)
            service.enterStationaryIfIdle(elapsed())
            assertStationary(service, true)
            service.maintainStationary(inForeground = false)
            verify(exactly = 1) { mockLocationProvider.removePassiveUpdates() }
            assertStationary(service, true)
            controller.destroy()
        }

    private companion object {
        const val MINUTE = 60_000L
        val IDLE = StationaryPolicy().idleMs
    }
}
