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
    fun noLocationPermission_pollsDirectlyInsteadOfStartingService() =
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
            coVerify(exactly = 0) { poller.poll(any()) }
        }
}
