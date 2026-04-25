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
    private lateinit var mockFused: com.google.android.gms.location.FusedLocationProviderClient

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

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

    private fun getServiceIsRegistered(service: LocationService): Boolean {
        val field = LocationService::class.java.getDeclaredField("isRegistered")
        field.isAccessible = true
        return field.get(service) as Boolean
    }

    @Test
    fun testRegistrationRemovedWhenSharingPaused() = runTest {
        io.mockk.every { UserPrefs.isSharing(any()) } returns true
        fakeLocationSource.setSharingLocation(true)

        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        service.fusedClientOverride = mockFused
        service.locationClientOverride = mockk(relaxed = true)
        service.e2eeStoreOverride = mockk(relaxed = true)

        controller.create()
        advanceUntilIdle()

        assertTrue(getServiceIsRegistered(service), "Should be registered when sharing is on")
        verify(exactly = 1) { mockFused.requestLocationUpdates(any<com.google.android.gms.location.LocationRequest>(), any<com.google.android.gms.location.LocationCallback>(), any<android.os.Looper>()) }

        // Pause sharing
        fakeLocationSource.setSharingLocation(false)
        advanceUntilIdle()

        assertFalse(getServiceIsRegistered(service), "Should be unregistered when sharing is paused")
        verify(exactly = 1) { mockFused.removeLocationUpdates(any<com.google.android.gms.location.LocationCallback>()) }

        // Resume sharing
        fakeLocationSource.setSharingLocation(true)
        advanceUntilIdle()

        assertTrue(getServiceIsRegistered(service), "Should be registered again when sharing is resumed")
        verify(exactly = 2) { mockFused.requestLocationUpdates(any<com.google.android.gms.location.LocationRequest>(), any<com.google.android.gms.location.LocationCallback>(), any<android.os.Looper>()) }

        controller.destroy()
    }
}
