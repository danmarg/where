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
}
