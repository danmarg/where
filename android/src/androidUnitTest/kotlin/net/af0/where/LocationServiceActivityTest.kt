package net.af0.where

import android.Manifest
import android.content.Context
import android.content.Intent
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceActivityTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testActivityTransition_UpdatesPriorityAndInterval() {
        assumeTrue("Activity recognition only enabled in full flavor", BuildConfig.ACTIVITY_RECOGNITION_ENABLED)
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        service.locationSourceOverride = ServiceFakeLocationSource()

        var nextEvents: List<ActivityTransitionEvent>? = null
        service.activityHelperOverride = object : ActivityHelper {
            override fun init(context: Context) {}
            override fun extractTransitionEvents(intent: Intent) = nextEvents
            override fun ensureRegistered(hasPermission: Boolean, isSharing: Boolean) {}
            override fun unregister() {}
            override fun onDestroy() {}
        }
        controller.create()

        // 1. Initial state
        assertEquals(LocationAccuracy.BALANCED, service.currentPriority)
        assertEquals(60_000L, service.currentInterval)

        // 2. Simulate WALKING transition
        nextEvents = listOf(ActivityTransitionEvent(ActivityType.WALKING, TransitionType.ENTER))
        val intent = Intent(service, LocationService::class.java).apply {
            action = LocationService.ACTION_ACTIVITY_TRANSITION
        }
        controller.withIntent(intent).startCommand(0, 1)

        // 3. Verify priority and interval updated for WALKING
        assertEquals(LocationAccuracy.HIGH, service.currentPriority)
        assertEquals(10_000L, service.currentInterval)

        // 4. Simulate STILL transition
        nextEvents = listOf(ActivityTransitionEvent(ActivityType.STILL, TransitionType.ENTER))
        controller.withIntent(intent).startCommand(0, 2)

        // 5. Verify priority and interval updated for STILL
        assertEquals(LocationAccuracy.LOW_POWER, service.currentPriority)
        assertEquals(LocationService.HEARTBEAT_INTERVAL_MS, service.currentInterval)
    }
}
