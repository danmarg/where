package net.af0.where

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class GmsLocationProviderTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var provider: GmsLocationProvider
    private lateinit var mockFusedClient: FusedLocationProviderClient
    private lateinit var mockGeofencingClient: GeofencingClient

    @Before
    fun setup() {
        mockFusedClient = mockk(relaxed = true)
        mockGeofencingClient = mockk(relaxed = true)
        provider = GmsLocationProvider()
        provider.fusedClientOverride = mockFusedClient
        provider.geofencingClientOverride = mockGeofencingClient
        provider.init(context) { _, _, _ -> }
    }

    // --- requestActiveUpdates return values ---

    @Test
    fun requestActiveUpdates_success_returnsTrue() {
        assertTrue(provider.requestActiveUpdates(LocationAccuracy.BALANCED, 30_000L, 60_000L))
    }

    @Test
    fun requestActiveUpdates_securityException_returnsFalse() {
        every {
            mockFusedClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        } throws SecurityException("denied")
        assertFalse(provider.requestActiveUpdates(LocationAccuracy.BALANCED, 30_000L, 60_000L))
    }

    // --- Priority mapping: each LocationAccuracy value must map to the correct GMS priority ---

    @Test
    fun requestActiveUpdates_highAccuracy_mapsToPriorityHighAccuracy() {
        val slot = slot<LocationRequest>()
        every {
            mockFusedClient.requestLocationUpdates(capture(slot), any<LocationCallback>(), any())
        } returns mockk(relaxed = true)
        provider.requestActiveUpdates(LocationAccuracy.HIGH, 10_000L, 10_000L)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, slot.captured.priority)
    }

    @Test
    fun requestActiveUpdates_balancedAccuracy_mapsToPriorityBalanced() {
        val slot = slot<LocationRequest>()
        every {
            mockFusedClient.requestLocationUpdates(capture(slot), any<LocationCallback>(), any())
        } returns mockk(relaxed = true)
        provider.requestActiveUpdates(LocationAccuracy.BALANCED, 30_000L, 60_000L)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, slot.captured.priority)
    }

    @Test
    fun requestActiveUpdates_lowPower_mapsToPriorityLowPower() {
        val slot = slot<LocationRequest>()
        every {
            mockFusedClient.requestLocationUpdates(capture(slot), any<LocationCallback>(), any())
        } returns mockk(relaxed = true)
        provider.requestActiveUpdates(LocationAccuracy.LOW_POWER, 60_000L, 60_000L)
        assertEquals(Priority.PRIORITY_LOW_POWER, slot.captured.priority)
    }

    @Test
    fun requestActiveUpdates_passive_mapsToPriorityPassive() {
        val slot = slot<LocationRequest>()
        every {
            mockFusedClient.requestLocationUpdates(capture(slot), any<LocationCallback>(), any())
        } returns mockk(relaxed = true)
        provider.requestActiveUpdates(LocationAccuracy.PASSIVE, 60_000L, 60_000L)
        assertEquals(Priority.PRIORITY_PASSIVE, slot.captured.priority)
    }

    // --- requestPassiveUpdates ---

    @Test
    fun requestPassiveUpdates_success_returnsTrue() {
        assertTrue(provider.requestPassiveUpdates())
    }

    @Test
    fun requestPassiveUpdates_securityException_returnsFalse() {
        every {
            mockFusedClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any())
        } throws SecurityException("denied")
        assertFalse(provider.requestPassiveUpdates())
    }

    // --- getLastLocationAsync ---

    @Test
    fun getLastLocationAsync_invokesCallbackWithLocation() {
        val mockLoc = mockk<Location>(relaxed = true)
        val taskMock = mockk<Task<Location>>(relaxed = true)
        every { mockFusedClient.lastLocation } returns taskMock
        every { taskMock.addOnSuccessListener(any()) } answers {
            firstArg<OnSuccessListener<Location?>>().onSuccess(mockLoc)
            taskMock
        }
        var received: Location? = null
        provider.getLastLocationAsync { received = it }
        assertEquals(mockLoc, received)
    }

    @Test
    fun getLastLocationAsync_securityException_invokesCallbackWithNull() {
        // The callback must always be invoked — a hung callback would stall the initial
        // geofence-plant in LocationService.onCreate().
        every { mockFusedClient.lastLocation } throws SecurityException("denied")
        var called = false
        var received: Location? = Location("sentinel")
        provider.getLastLocationAsync { loc ->
            called = true
            received = loc
        }
        assertTrue(called, "callback must always be invoked even on SecurityException")
        assertNull(received)
    }

    // --- setGeofenceAt ---

    @Test
    fun setGeofenceAt_returnsTrueImmediately() {
        // GMS geofencing is asynchronous; setGeofenceAt must return true as soon as the
        // request is submitted, before the Task result is known.
        assertTrue(provider.setGeofenceAt(37.0, -122.0))
    }

    @Test
    fun setGeofenceAt_securityException_returnsFalse() {
        every { mockGeofencingClient.addGeofences(any(), any()) } throws SecurityException("denied")
        assertFalse(provider.setGeofenceAt(37.0, -122.0))
    }

    // --- onDestroy ---

    @Test
    fun onDestroy_removesActiveAndPassiveListeners() {
        // Both removes must fire unconditionally — skipping one would leak an FLP subscription.
        provider.onDestroy()
        verify(exactly = 2) { mockFusedClient.removeLocationUpdates(any<LocationCallback>()) }
    }

    // --- callbackLooper init ---

    @Test
    fun init_fromNonLooperThread_doesNotThrow() {
        // Regression test for the lateinit callbackLooper bug: init() previously used
        // `Looper.myLooper() ?: callbackLooper` (the uninitialised var itself), throwing
        // UninitializedPropertyAccessException on threads without a Looper. It now falls
        // back to Looper.getMainLooper() correctly.
        var caught: Throwable? = null
        val anotherProvider = GmsLocationProvider()
        anotherProvider.fusedClientOverride = mockFusedClient
        anotherProvider.geofencingClientOverride = mockGeofencingClient
        val thread = Thread {
            try {
                anotherProvider.init(context) { _, _, _ -> }
            } catch (e: Throwable) {
                caught = e
            }
        }
        thread.start()
        thread.join()
        // thread.join() provides the happens-before edge; caught is safely readable here.
        assertNull(caught, "init() must not throw on a Looper-less thread; got: $caught")
    }
}
