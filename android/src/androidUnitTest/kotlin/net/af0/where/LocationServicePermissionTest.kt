package net.af0.where

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
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
import org.robolectric.shadows.ShadowNotificationManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServicePermissionTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Explicitly DENY permissions (though they should be denied by default)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        fakeLocationSource = ServiceFakeLocationSource()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.locationSource = fakeLocationSource

        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testServiceStarts_WithoutLocationPermission() {
        // This test specifically checks that startForeground is called and the service 
        // doesn't call stopSelf() immediately when permissions are missing.
        
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        
        val mockClient = io.mockk.mockk<net.af0.where.e2ee.LocationClient>(relaxed = true)
        service.locationClientOverride = mockClient
        val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
        service.e2eeStoreOverride = mockStore
        
        // Before the fix, onCreate() would call stopSelf() and return early, 
        // often before startForeground() was called.
        controller.create()
        
        val shadowService = shadowOf(service)
        assertFalse(shadowService.isStoppedBySelf, "Service should NOT have stopped itself even without permissions")
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)
        val notification = shadowNotificationManager.allNotifications.firstOrNull()
        
        assertNotNull(notification, "Service should have posted a notification (startForeground)")
        
        // Verify startForeground was called (NOTIFICATION_ID is 1)
        assertEquals(1, shadowService.lastForegroundNotificationId)
    }

    @Test
    fun testServiceStarts_WithCoarseLocationPermissionOnly() {
        // Android 12+ "Approximate" location grants Coarse but not Fine.
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        
        val mockClient = io.mockk.mockk<net.af0.where.e2ee.LocationClient>(relaxed = true)
        service.locationClientOverride = mockClient
        val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
        service.e2eeStoreOverride = mockStore
        
        controller.create()
        
        val shadowService = shadowOf(service)
        assertFalse(shadowService.isStoppedBySelf, "Service should NOT have stopped itself with Coarse permission")
        assertEquals(1, shadowService.lastForegroundNotificationId)
    }

    private fun assertFalse(actual: Boolean, message: String) {
        assertEquals(false, actual, message)
    }
}
