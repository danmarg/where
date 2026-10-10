package net.af0.where

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceSharingPauseTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource
    private lateinit var mockLocationProvider: LocationProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        fakeLocationSource = ServiceFakeLocationSource()
        fakeLocationSource.onFriendsUpdated(listOf(io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)))
        LocationService.clock = { System.currentTimeMillis() }

        mockLocationProvider = mockk(relaxed = true)
        io.mockk.every { mockLocationProvider.requestPassiveUpdates() } returns true
        io.mockk.every { mockLocationProvider.requestActiveUpdates(any(), any(), any()) } returns true

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
    fun testRegistrationRemovedWhenSharingPaused() =
        runTest {
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            val mockFriend = io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
            val mockE2ee = mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
            io.mockk.coEvery { mockE2ee.listFriends() } returns listOf(mockFriend)
            service.locationProviderOverride = mockLocationProvider
            service.locationClientOverride = mockk(relaxed = true)
            service.e2eeManagerOverride = mockE2ee
            service.locationSourceOverride = fakeLocationSource

            controller.create()
            advanceUntilIdle()

            assertTrue(service.isRegistered, "Should be registered when sharing is on")
            verify(exactly = 1) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }
            verify(exactly = 1) { mockLocationProvider.requestPassiveUpdates() }

            // Pause sharing
            app.userStore.setSharing(false)
            advanceUntilIdle()

            assertFalse(service.isRegistered, "Should be unregistered when sharing is paused")
            verify(exactly = 1) { mockLocationProvider.removeActiveUpdates() }
            verify(exactly = 1) { mockLocationProvider.removePassiveUpdates() }

            // Resume sharing
            app.userStore.setSharing(true)
            advanceUntilIdle()

            assertTrue(service.isRegistered, "Should be registered again when sharing is resumed")
            verify(exactly = 2) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }
            verify(exactly = 2) { mockLocationProvider.requestPassiveUpdates() }

            controller.destroy()
        }

    private fun geofenceEvent() =
        android.content.Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_GEOFENCE_EVENT
            putExtra(LocationService.EXTRA_GEOFENCE_LAT, 37.0)
            putExtra(LocationService.EXTRA_GEOFENCE_LNG, -122.0)
        }

    @Test
    fun geofencePlantedWhileSharingIsRemovedWhenSharingPauses() =
        runTest {
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            io.mockk.every { mockLocationProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.SUBMITTED

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationProviderOverride = mockLocationProvider
            service.locationClientOverride = mockk(relaxed = true)
            service.e2eeManagerOverride = mockk(relaxed = true)
            service.locationSourceOverride = fakeLocationSource
            controller.create()
            service.onStartCommand(geofenceEvent(), 0, 1)
            advanceUntilIdle()
            verify(atLeast = 1) { mockLocationProvider.setGeofenceAt(37.0, -122.0, any()) }

            app.userStore.setSharing(false)
            advanceUntilIdle()

            verify(exactly = 1) { mockLocationProvider.removeGeofence() }
            assertFalse(service.geofenceMayBeRegistered)
            controller.destroy()
        }

    @Test
    fun geofenceEventWhileNotSharingRemovesFenceWithoutForcingGps() =
        runTest {
            val app = context as TestWhereApplication
            app.userStore.setSharing(false)

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationProviderOverride = mockLocationProvider
            service.locationClientOverride = mockk(relaxed = true)
            service.e2eeManagerOverride = mockk(relaxed = true)
            service.locationSourceOverride = fakeLocationSource
            controller.create()
            service.onStartCommand(geofenceEvent(), 0, 1)
            advanceUntilIdle()

            // At least once: startup reconciliation may already have removed it (see below).
            verify(atLeast = 1) { mockLocationProvider.removeGeofence() }
            verify(exactly = 0) { mockLocationProvider.setGeofenceAt(any(), any(), any()) }
            verify(exactly = 0) { mockLocationProvider.requestActiveUpdates(any(), any(), any()) }
            controller.destroy()
        }

    @Test
    fun serviceStartedWhileNotSharingRemovesFenceLeftByPreviousProcessOnce() =
        runTest {
            // A NEVER_EXPIRE fence outlives the process that planted it, and a fresh process has
            // no record of it - so with sharing off, startup must remove it without waiting for
            // its exit event, and later reconciliations must not keep re-issuing the remove.
            val app = context as TestWhereApplication
            app.userStore.setSharing(false)

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationProviderOverride = mockLocationProvider
            service.locationClientOverride = mockk(relaxed = true)
            service.e2eeManagerOverride = mockk(relaxed = true)
            service.locationSourceOverride = fakeLocationSource
            controller.create()
            advanceUntilIdle()

            verify(exactly = 1) { mockLocationProvider.removeGeofence() }
            assertFalse(service.geofenceMayBeRegistered)

            service.onStartCommand(android.content.Intent(context, LocationService::class.java), 0, 1)
            advanceUntilIdle()
            verify(exactly = 1) { mockLocationProvider.removeGeofence() }
            controller.destroy()
        }

    @Test
    fun failedGeofenceAddIsNotTreatedAsRegistered() =
        runTest {
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            io.mockk.every { mockLocationProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.FAILED

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationProviderOverride = mockLocationProvider
            service.locationClientOverride = mockk(relaxed = true)
            service.e2eeManagerOverride = mockk(relaxed = true)
            service.locationSourceOverride = fakeLocationSource
            controller.create()
            advanceUntilIdle()
            // Pause once so the startup "may be registered" state is resolved by a remove.
            app.userStore.setSharing(false)
            advanceUntilIdle()
            app.userStore.setSharing(true)
            advanceUntilIdle()
            service.onStartCommand(geofenceEvent(), 0, 1)
            advanceUntilIdle()
            verify(atLeast = 1) { mockLocationProvider.setGeofenceAt(any(), any(), any()) }
            assertFalse(service.geofenceMayBeRegistered, "a FAILED add must not mark a fence as possibly registered")
            controller.destroy()
        }
}
