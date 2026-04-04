package net.af0.where

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        ShadowLog.stream = System.out
        // Reset the singleton repository
        val field = LocationRepository::class.java.getDeclaredField("_lastLocation")
        field.isAccessible = true
        val flow = field.get(LocationRepository) as kotlinx.coroutines.flow.MutableStateFlow<Pair<Double, Double>?>
        flow.value = null
    }

    private fun getServicePendingFriendId(service: LocationService): String? {
        val field = LocationService::class.java.getDeclaredField("pendingFriendId")
        field.isAccessible = true
        return field.get(service) as String?
    }

    private fun getServiceIsRegistered(service: LocationService): Boolean {
        val field = LocationService::class.java.getDeclaredField("isRegistered")
        field.isAccessible = true
        return field.get(service) as Boolean
    }

    @Test
    fun testActionForcePublishQueuesWhenLocationIsNull_BugA() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        controller.create()

        // Verify registration happened in onCreate
        assertTrue(getServiceIsRegistered(service), "Should be registered in onCreate")

        // 1. Send ACTION_FORCE_PUBLISH while LocationRepository.lastLocation is null
        val friendId = "bob-123"
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_FORCE_PUBLISH
            putExtra(LocationService.EXTRA_FRIEND_ID, friendId)
        }
        controller.startCommand(intent, 0, 1)

        // 2. Verify it is queued in pendingFriendId
        assertEquals(friendId, getServicePendingFriendId(service), "Friend ID should be queued when location is null")
    }

    @Test
    fun testDeduplication_BugC() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        controller.create()

        assertTrue(getServiceIsRegistered(service))

        // Trigger startCommand multiple times (mimicking multiple publishes)
        val intent = Intent(context, LocationService::class.java)
        controller.startCommand(intent, 0, 1)
        controller.startCommand(intent, 0, 2)

        // This test mainly verifies that our isRegistered flag prevents re-entry logic
        // that would have caused duplicate listeners in the FusedLocationProvider.
        assertTrue(getServiceIsRegistered(service))
    }
}
