package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.FriendEntry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the full lambda chain: FdroidLocationProvider receives a location fix from the
 * Android LocationManager, and LocationService's onLocation lambda correctly stamps
 * lastLocationCallbackTime and forwards the fix to LocationSource.
 *
 * The prior test suite used a mocked LocationProvider (relaxed = true), so the callback lambda
 * was never fired and these side-effects were untested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class FdroidLocationCallbackTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        // Enable GPS so FdroidLocationProvider selects it in requestActiveUpdates().
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)

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
    fun locationFix_stampsCallbackTimeAndForwardsToLocationSource() = runTest {
        val fakeLocationSource = ServiceFakeLocationSource()
        val mockFriend = mockk<FriendEntry>(relaxed = true)
        // Populate friends before service onCreate so ensureLocationRegistration() doesn't stop the service.
        fakeLocationSource.onFriendsUpdated(listOf(mockFriend))

        val mockE2ee = mockk<E2eeManager>(relaxed = true)
        coEvery { mockE2ee.listFriends() } returns listOf(mockFriend)

        val app = context as TestWhereApplication
        app.userStore.setSharing(true)
        LocationService.clock = { System.currentTimeMillis() }

        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        // Do NOT set locationProviderOverride — use the real FdroidLocationProvider.
        service.locationSourceOverride = fakeLocationSource
        service.locationClientOverride = mockk(relaxed = true)
        service.e2eeManagerOverride = mockE2ee

        controller.create()
        advanceUntilIdle()

        assertTrue(service.isRegistered, "Service should register with GPS when a friend is present")
        assertEquals(0L, service.lastLocationCallbackTime, "No callback fired yet")

        // Simulate a GPS fix arriving from the system LocationManager.
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.7749
            longitude = -122.4194
            bearing = 45f
            accuracy = 10f
            time = System.currentTimeMillis()
        }
        shadowOf(lm).simulateLocation(fix)

        // Flush the main looper so the LocationListener transport delivers the callback.
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertTrue(service.lastLocationCallbackTime > 0L, "lastLocationCallbackTime must be stamped on callback")

        val loc = fakeLocationSource.lastLocation.value
        assertNotNull(loc, "LocationSource must have a location after the fix")
        assertEquals(37.7749, loc.first, 0.0001)
        assertEquals(-122.4194, loc.second, 0.0001)
        assertEquals(45.0, loc.third!!, 0.1)

        // recordRecentFix is called in the same lambda as lastLocationCallbackTime. A single fix
        // produces zero displacement, confirming the call was made without needing a second fix.
        assertEquals(0f, service.maxRecentDisplacementMeters(), "One fix → zero displacement, confirming recordRecentFix was called")

        controller.destroy()
    }
}
