package net.af0.where

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Regression test for the API 34+ crash where LocationService.onCreate() called
 * startForeground() on a foregroundServiceType="location" service without the location
 * permission held, which the real OS throws SecurityException for. Robolectric's ShadowService
 * doesn't emulate that platform enforcement (see startForegroundOverride's kdoc), so this
 * simulates it directly via the test-only override hook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceStartForegroundFailureTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        LocationService.clock = { System.currentTimeMillis() }
        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun onCreate_stopsSelfInsteadOfCrashing_whenStartForegroundThrows() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()

        service.startForegroundOverride = { throw SecurityException("permission denied, simulating API 34+") }
        service.locationSourceOverride = ServiceFakeLocationSource()
        service.e2eeManagerOverride = mockk(relaxed = true)
        service.locationClientOverride = mockk(relaxed = true)
        service.uiStateStoreOverride = FakeUiStateStore()
        service.locationProviderOverride = mockk(relaxed = true)
        service.activityHelperOverride = mockk(relaxed = true)

        controller.create()

        assertTrue(shadowOf(service).isStoppedBySelf, "Service should have stopped itself after startForeground() failed")
    }

    @Test
    fun onDestroy_doesNotCrash_afterStartForegroundThrew() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()

        service.startForegroundOverride = { throw SecurityException("permission denied, simulating API 34+") }
        service.locationSourceOverride = ServiceFakeLocationSource()
        service.e2eeManagerOverride = mockk(relaxed = true)
        service.locationClientOverride = mockk(relaxed = true)
        service.uiStateStoreOverride = FakeUiStateStore()
        service.locationProviderOverride = mockk(relaxed = true)
        service.activityHelperOverride = mockk(relaxed = true)

        controller.create()

        // Before the fix, this threw UninitializedPropertyAccessException because onCreate()
        // returned before initializing locationProvider/activityHelper/etc., and onDestroy()
        // unconditionally dereferenced them.
        controller.destroy()
    }
}
