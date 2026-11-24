package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import net.af0.where.e2ee.E2eeManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceRestartWorkerTest {
    private val app: TestWhereApplication = ApplicationProvider.getApplicationContext<Application>() as TestWhereApplication
    private val poller = mockk<FriendPoller>(relaxed = true)

    @Before
    fun setup() {
        net.af0.where.initializeLibsodium()
        app.friendPollerOverride = poller
    }

    @After
    fun teardown() {
        app.friendPollerOverride = null
        unmockkAll()
    }

    private fun worker() = LocationServiceRestartWorker(app as Context, mockk<WorkerParameters>(relaxed = true))

    private suspend fun addPendingInvite() {
        val manager: E2eeManager = app.e2eeManager
        manager.createInvite("Me")
    }

    @Test
    fun noRelationships_doesNothing() =
        runTest {
            assertEquals(ListenableWorker.Result.success(), worker().doWork())

            assertNull(shadowOf(app).nextStartedService)
            coVerify(exactly = 0) { poller.poll(any()) }
        }

    @Test
    @Config(sdk = [34])
    fun noLocationPermission_api34_pollsDirectlyInsteadOfStartingService() =
        runTest {
            addPendingInvite()

            assertEquals(ListenableWorker.Result.success(), worker().doWork())

            coVerify(exactly = 1) { poller.poll(WakeSource.HEARTBEAT) }
            assertNull(shadowOf(app).nextStartedService, "service cannot run without permission; must not be started")
        }

    @Test
    fun withLocationPermission_startsServiceWithHeartbeatTick() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            addPendingInvite()

            assertEquals(ListenableWorker.Result.success(), worker().doWork())

            val started = shadowOf(app).nextStartedService
            assertNotNull(started)
            assertEquals(LocationService::class.java.name, started.component?.className)
            assertEquals(LocationService.ACTION_HEARTBEAT_TICK, started.action)
            coVerify(exactly = 0) { poller.poll(any()) } // the nudged service polls; don't double it
        }

    @Test
    fun serviceJustPolled_workerSkipsTheTick() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            addPendingInvite()
            val now = 5_000_000_000L
            LocationService.lastPollAtMs = now - 60_000
            try {
                val w = worker().also { it.clock = { now } }
                assertEquals(ListenableWorker.Result.success(), w.doWork())
                assertNull(shadowOf(app).nextStartedService, "polled a minute ago; no tick")

                LocationService.lastPollAtMs = now - PollPolicy.BACKGROUND_INTERVAL_MS
                w.doWork()
                assertNotNull(shadowOf(app).nextStartedService, "a poll a cadence ago is due a tick")
            } finally {
                LocationService.lastPollAtMs = 0L
            }
        }

    @Test
    @Config(sdk = [34])
    fun withLocationPermission_serviceStartRefused_stillPollsAndSucceeds() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            addPendingInvite()
            val w = worker()
            w.startService = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }

            assertEquals(ListenableWorker.Result.success(), w.doWork())

            coVerify(exactly = 1) { poller.poll(WakeSource.HEARTBEAT) }
        }

    @Test
    @Config(sdk = [33])
    fun noLocationPermission_beforeApi34_stillNudgesService() =
        runTest {
            addPendingInvite()

            assertEquals(ListenableWorker.Result.success(), worker().doWork())

            val started = shadowOf(app).nextStartedService
            assertNotNull(started, "before API 34 the service can run without permission")
            assertEquals(LocationService.ACTION_HEARTBEAT_TICK, started.action)
            coVerify(exactly = 0) { poller.poll(any()) }
        }

    @Test
    fun notSharing_withPermission_pollsDirectlyWithoutStartingService() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            app.userStore.setSharing(false)
            addPendingInvite()

            assertEquals(ListenableWorker.Result.success(), worker().doWork())

            coVerify(exactly = 1) { poller.poll(WakeSource.HEARTBEAT) }
            assertNull(shadowOf(app).nextStartedService, "no foreground service (and notification) while not sharing")
        }

    @Test
    fun notSharing_directPollsAreThrottledToTheMaintenanceInterval() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            app.userStore.setSharing(false)
            addPendingInvite()
            var now = 1_000_000_000L

            fun run() = worker().also { it.clock = { now } }

            run().doWork()
            now += 15 * 60 * 1000L // the next periodic run
            run().doWork()
            coVerify(exactly = 1) { poller.poll(any()) }

            now += 15 * 60 * 1000L
            run().doWork()
            coVerify(exactly = 2) { poller.poll(any()) }

            now -= 60 * 60 * 1000L // clock set back: doesn't hold polling off
            run().doWork()
            coVerify(exactly = 3) { poller.poll(any()) }
        }

    @Test
    fun sharing_serviceStartRefused_pollIsNotThrottled() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            addPendingInvite()

            repeat(2) {
                val w = worker()
                w.clock = { 1_000_000_000L }
                w.startService = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }
                w.doWork()
            }

            coVerify(exactly = 2) { poller.poll(WakeSource.HEARTBEAT) }
        }
}
