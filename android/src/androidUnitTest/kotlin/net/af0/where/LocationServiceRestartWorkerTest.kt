package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.af0.where.db.WhereDatabase
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

    /** A friend in [app]'s store that some poll last reached at [ts] (epoch seconds). */
    private suspend fun addFriendPolledAt(ts: Long) {
        val qr = E2eeManager(AndroidSqliteDriver(WhereDatabase.Schema, app, null), UnconfinedTestDispatcher()).createInvite("Alice")
        val (_, friend) = app.e2eeManager.processScannedQr(qr, "Alice")
        app.e2eeManager.updateLastPollTs(friend.id, ts)
    }

    @Test
    fun notSharing_skipsThePollWhenAnyPollReachedAFriendRecently() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            app.userStore.setSharing(false)
            val now = 1_000_000_000L
            // E.g. the service, polling every 10 s until the app left the screen.
            addFriendPolledAt(now - LocationServiceRestartWorker.MAINTENANCE_POLL_INTERVAL_S + 1)

            worker().also { it.nowSeconds = { now } }.doWork()
            coVerify(exactly = 0) { poller.poll(any()) }

            worker().also { it.nowSeconds = { now + 1 } }.doWork()
            coVerify(exactly = 1) { poller.poll(WakeSource.HEARTBEAT) }

            // A clock set back doesn't hold polling off.
            worker().also { it.nowSeconds = { now - 3600 } }.doWork()
            coVerify(exactly = 2) { poller.poll(any()) }
            assertNull(shadowOf(app).nextStartedService)
        }

    @Test
    fun sharing_serviceStartRefused_fallbackPollIsThrottledToo() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val now = 1_000_000_000L
            addFriendPolledAt(now - 60)
            val w = worker()
            w.nowSeconds = { now }
            w.startService = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }

            w.doWork()

            coVerify(exactly = 0) { poller.poll(any()) }
        }
}
