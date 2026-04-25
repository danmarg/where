package net.af0.where

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
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
import dev.icerock.moko.resources.desc.StringDesc
import dev.icerock.moko.resources.desc.Resource
import net.af0.where.shared.MR
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class, qualifiers = "en")
class LocationServicePermissionTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource
    private lateinit var mockFused: com.google.android.gms.location.FusedLocationProviderClient

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Explicitly DENY permissions (though they should be denied by default)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        fakeLocationSource = ServiceFakeLocationSource()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.locationSource = fakeLocationSource

        mockFused = mockk(relaxed = true)

        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
        io.mockk.mockkObject(UserPrefs)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testServiceStarts_WithoutLocationPermission_Sharing() {
        io.mockk.every { UserPrefs.isSharing(any()) } returns true
        fakeLocationSource.setSharingLocation(true)

        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()

        val mockClient = io.mockk.mockk<net.af0.where.e2ee.LocationClient>(relaxed = true)
        service.locationClientOverride = mockClient
        val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
        service.e2eeStoreOverride = mockStore
        service.fusedClientOverride = mockFused

        controller.create()

        val shadowService = shadowOf(service)
        assertFalse(shadowService.isStoppedBySelf)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)
        val notification = shadowNotificationManager.allNotifications.firstOrNull()

        assertNotNull(notification)
        val shadowNotification = shadowOf(notification)
        assertEquals(StringDesc.Resource(MR.strings.location_permission_missing).toString(context), shadowNotification.contentText)

        // Verify fusedClient was NOT used for updates due to lack of permission
        verify(exactly = 0) { mockFused.requestLocationUpdates(any<com.google.android.gms.location.LocationRequest>(), any<com.google.android.gms.location.LocationCallback>(), any<android.os.Looper>()) }
    }

    @Test
    fun testServiceStarts_WithoutLocationPermission_Paused() {
        io.mockk.every { UserPrefs.isSharing(any()) } returns false
        fakeLocationSource.setSharingLocation(false)

        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()

        val mockClient = io.mockk.mockk<net.af0.where.e2ee.LocationClient>(relaxed = true)
        service.locationClientOverride = mockClient
        val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
        service.e2eeStoreOverride = mockStore
        service.fusedClientOverride = mockFused

        controller.create()

        val shadowService = shadowOf(service)
        assertFalse(shadowService.isStoppedBySelf)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)
        val notification = shadowNotificationManager.allNotifications.firstOrNull()

        assertNotNull(notification)
        val shadowNotification = shadowOf(notification)
        assertEquals(StringDesc.Resource(MR.strings.location_sharing_paused_no_permission).toString(context), shadowNotification.contentText)
    }

    @Test
    fun testServiceStarts_WithCoarseLocationPermissionOnly() {
        io.mockk.every { UserPrefs.isSharing(any()) } returns true
        // Android 12+ "Approximate" location grants Coarse but not Fine.
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()

        val mockClient = io.mockk.mockk<net.af0.where.e2ee.LocationClient>(relaxed = true)
        service.locationClientOverride = mockClient
        val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
        service.e2eeStoreOverride = mockStore
        service.fusedClientOverride = mockFused

        controller.create()

        val shadowService = shadowOf(service)
        assertFalse(shadowService.isStoppedBySelf, "Service should NOT have stopped itself with Coarse permission")
        assertEquals(1, shadowService.lastForegroundNotificationId)

        // Verify fusedClient WAS used for updates because Coarse permission is sufficient
        verify(exactly = 1) { mockFused.requestLocationUpdates(any<com.google.android.gms.location.LocationRequest>(), any<com.google.android.gms.location.LocationCallback>(), any<android.os.Looper>()) }
        
        // Notification should NOT say "missing" because Coarse is enough
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)
        val notification = shadowNotificationManager.allNotifications.firstOrNull()
        val shadowNotification = shadowOf(notification)
        assertFalse(shadowNotification.contentText == StringDesc.Resource(MR.strings.location_permission_missing).toString(context))
    }
}
