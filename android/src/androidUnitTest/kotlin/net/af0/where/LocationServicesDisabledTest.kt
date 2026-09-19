package net.af0.where

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.af0.where.shared.MR
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Mirrors [LocationServicePermissionTest], but for the analogous "location services (GPS)
 * disabled at the system level" precondition rather than missing app permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class, qualifiers = "en")
class LocationServicesDisabledTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource
    private lateinit var mockLocationProvider: LocationProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        fakeLocationSource = ServiceFakeLocationSource()
        fakeLocationSource.onFriendsUpdated(listOf(mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)))
        LocationService.clock = { System.currentTimeMillis() }

        mockLocationProvider = mockk(relaxed = true)

        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
        io.mockk.mockkObject(UserPrefs)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun setProvidersEnabled(enabled: Boolean) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // hasLocationServicesEnabled() reads LocationManagerCompat.isLocationEnabled(), which the
        // shadow backs with this master switch rather than per-provider isProviderEnabled().
        shadowOf(locationManager).setLocationEnabled(enabled)
    }

    private fun buildService(): LocationService {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        service.locationClientOverride = mockk(relaxed = true)
        service.locationSourceOverride = fakeLocationSource
        service.e2eeManagerOverride = mockk(relaxed = true)
        service.uiStateStoreOverride = FakeUiStateStore()
        service.locationProviderOverride = mockLocationProvider
        controller.create()
        return service
    }

    private fun notificationText(): String? {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)
        val notification = shadowNotificationManager.allNotifications.firstOrNull()
        assertNotNull(notification)
        return shadowOf(notification).contentText?.toString()
    }

    @Test
    fun testServiceStarts_WithLocationServicesDisabled_Sharing() {
        setProvidersEnabled(false)
        (context as TestWhereApplication).userStore.setSharing(true)

        val service = buildService()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(StringDesc.Resource(MR.strings.location_services_disabled).toString(context), notificationText())

        verify(exactly = 0) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }
        verify(exactly = 0) { mockLocationProvider.requestPassiveUpdates() }
    }

    @Test
    fun testServiceStarts_WithLocationServicesDisabled_Paused() {
        setProvidersEnabled(false)
        (context as TestWhereApplication).userStore.setSharing(false)

        buildService()

        assertEquals(
            StringDesc.Resource(MR.strings.location_sharing_paused_no_location_services).toString(context),
            notificationText(),
        )
    }

    @Test
    fun testProviderStateTransition_OnToOff_RemovesRegistration() {
        io.mockk.every { mockLocationProvider.requestActiveUpdates(any(), any(), any()) } returns true
        io.mockk.every { mockLocationProvider.requestPassiveUpdates() } returns true
        setProvidersEnabled(true)
        (context as TestWhereApplication).userStore.setSharing(true)

        buildService()
        verify(exactly = 1) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }

        setProvidersEnabled(false)
        context.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { mockLocationProvider.removeActiveUpdates() }
        verify(exactly = 1) { mockLocationProvider.removePassiveUpdates() }
        assertEquals(StringDesc.Resource(MR.strings.location_services_disabled).toString(context), notificationText())
    }

    @Test
    fun testProviderStateTransition_OffToOn_ResumesRegistration() {
        io.mockk.every { mockLocationProvider.requestActiveUpdates(any(), any(), any()) } returns true
        io.mockk.every { mockLocationProvider.requestPassiveUpdates() } returns true
        setProvidersEnabled(false)
        (context as TestWhereApplication).userStore.setSharing(true)

        buildService()
        verify(exactly = 0) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }

        setProvidersEnabled(true)
        context.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }
        assertEquals(StringDesc.Resource(MR.strings.sharing_your_location).toString(context), notificationText())
    }
}
