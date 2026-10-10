package net.af0.where

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
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
    fun setGeofenceAt_returnsSubmittedImmediately() {
        // GMS geofencing is asynchronous; setGeofenceAt must report SUBMITTED as soon as the
        // request is submitted, before the Task result is known.
        assertEquals(GeofenceRequestResult.SUBMITTED, provider.setGeofenceAt(37.0, -122.0, 200f))
    }

    @Test
    fun setGeofenceAt_securityException_returnsFailed() {
        every { mockGeofencingClient.addGeofences(any(), any()) } throws SecurityException("denied")
        assertEquals(GeofenceRequestResult.FAILED, provider.setGeofenceAt(37.0, -122.0, 200f))
    }

    @Test
    fun setGeofenceAt_secondCallWhileInFlight_isQueuedThenDrainedOnCompletion() {
        // Regression test: addGeofences() is asynchronous. A call arriving before the prior
        // one resolves must not fire an overlapping request (whose completion order vs. the
        // first is not guaranteed) - it should replace the pending target and only submit it
        // once the in-flight call actually settles.
        val successListeners = mutableListOf<OnSuccessListener<Void>>()
        val taskMock = mockk<Task<Void>>(relaxed = true)
        every { mockGeofencingClient.addGeofences(any(), any()) } returns taskMock
        every { taskMock.addOnSuccessListener(any()) } answers {
            successListeners.add(firstArg())
            taskMock
        }

        assertEquals(GeofenceRequestResult.SUBMITTED, provider.setGeofenceAt(1.0, 2.0, 200f))
        verify(exactly = 1) { mockGeofencingClient.addGeofences(any(), any()) }

        // A second target arriving mid-flight must report QUEUED, not SUBMITTED - it hasn't
        // actually been sent to GMS yet.
        assertEquals(GeofenceRequestResult.QUEUED, provider.setGeofenceAt(3.0, 4.0, 400f))
        verify(exactly = 1) { mockGeofencingClient.addGeofences(any(), any()) }

        successListeners.first().onSuccess(null)
        verify(exactly = 2) { mockGeofencingClient.addGeofences(any(), any()) }
    }

    @Test
    fun setGeofenceAt_queuedTargetIsDrainedAfterInFlightRequestFails() {
        // The failure listener must drain a queued target too, not just the success
        // listener - otherwise a target queued behind a request that ultimately fails
        // would be silently dropped forever.
        val failureListeners = mutableListOf<OnFailureListener>()
        val taskMock = mockk<Task<Void>>(relaxed = true)
        every { mockGeofencingClient.addGeofences(any(), any()) } returns taskMock
        // addOnSuccessListener() is called first in the chain (submitGeofence()) - it must
        // return taskMock itself, or the subsequent addOnFailureListener() call below lands
        // on a different (relaxed-generated) mock instance and this stub never fires.
        every { taskMock.addOnSuccessListener(any()) } returns taskMock
        every { taskMock.addOnFailureListener(any()) } answers {
            failureListeners.add(firstArg())
            taskMock
        }

        assertEquals(GeofenceRequestResult.SUBMITTED, provider.setGeofenceAt(1.0, 2.0, 200f))
        assertEquals(GeofenceRequestResult.QUEUED, provider.setGeofenceAt(3.0, 4.0, 400f))
        verify(exactly = 1) { mockGeofencingClient.addGeofences(any(), any()) }

        failureListeners.first().onFailure(SecurityException("denied"))
        verify(exactly = 2) { mockGeofencingClient.addGeofences(any(), any()) }
    }

    // --- removeGeofence serialization ---

    /** Captures add listeners and remove completion listeners so the test controls settle order. */
    private inner class GeofenceTasks {
        val addSuccess = mutableListOf<OnSuccessListener<Void>>()
        val removeComplete = mutableListOf<OnCompleteListener<Void>>()
        val calls = mutableListOf<String>()

        init {
            val addTask = mockk<Task<Void>>(relaxed = true)
            every { mockGeofencingClient.addGeofences(any(), any()) } answers {
                calls += "add"
                addTask
            }
            every { addTask.addOnSuccessListener(any()) } answers {
                addSuccess.add(firstArg())
                addTask
            }
            every { addTask.addOnFailureListener(any()) } returns addTask
            val removeTask = mockk<Task<Void>>(relaxed = true)
            every { removeTask.isSuccessful } returns true
            every { mockGeofencingClient.removeGeofences(any<List<String>>()) } answers {
                calls += "remove"
                removeTask
            }
            every { removeTask.addOnCompleteListener(any()) } answers {
                removeComplete.add(firstArg())
                removeTask
            }
        }
    }

    @Test
    fun removeGeofence_whileAddInFlight_runsAfterTheAddSettles() {
        // Regression test: removing while an add was outstanding used to fire the remove
        // immediately; if GMS then applied the add last, a NEVER_EXPIRE fence outlived sharing.
        val tasks = GeofenceTasks()
        provider.setGeofenceAt(1.0, 2.0, 200f)
        provider.removeGeofence()
        assertEquals(listOf("add"), tasks.calls, "remove must wait for the in-flight add")

        tasks.addSuccess.single().onSuccess(null)
        assertEquals(listOf("add", "remove"), tasks.calls, "remove must be the final operation")
    }

    @Test
    fun removeGeofence_replacesQueuedAdd() {
        val tasks = GeofenceTasks()
        provider.setGeofenceAt(1.0, 2.0, 200f)
        assertEquals(GeofenceRequestResult.QUEUED, provider.setGeofenceAt(3.0, 4.0, 200f))
        provider.removeGeofence()

        tasks.addSuccess.single().onSuccess(null)
        assertEquals(listOf("add", "remove"), tasks.calls, "the queued add must not be submitted")
    }

    @Test
    fun setGeofenceAt_whileRemoveInFlight_isQueuedUntilRemoveSettles() {
        // The reverse order: re-enabling sharing during a remove must not race it either.
        val tasks = GeofenceTasks()
        provider.removeGeofence()
        assertEquals(GeofenceRequestResult.QUEUED, provider.setGeofenceAt(1.0, 2.0, 200f))
        assertEquals(listOf("remove"), tasks.calls)

        tasks.removeComplete.single().onComplete(mockk(relaxed = true))
        assertEquals(listOf("remove", "add"), tasks.calls)
    }

    @Test
    fun removeGeofence_isIdempotentWhenNothingIsRegistered() {
        // Callers that don't know whether a fence exists (fresh process) can always remove.
        val tasks = GeofenceTasks()
        provider.removeGeofence()
        tasks.removeComplete.single().onComplete(mockk(relaxed = true))
        provider.removeGeofence()
        assertEquals(listOf("remove", "remove"), tasks.calls)
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
        val thread =
            Thread {
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
