package net.af0.where

import android.Manifest
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Priority
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
        service.activityRecognitionClientOverride = mockk(relaxed = true)
        controller.create()

        // 1. Initial state
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, service.currentPriority)
        assertEquals(60_000L, service.currentInterval)

        // 2. Simulate WALKING transition
        val walkingEvent = ActivityTransitionEvent(
            DetectedActivity.WALKING,
            ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            System.currentTimeMillis()
        )
        
        val result = mockk<ActivityTransitionResult>()
        io.mockk.every { result.transitionEvents } returns listOf(walkingEvent)
        
        mockkStatic(ActivityTransitionResult::class)
        io.mockk.every { ActivityTransitionResult.extractResult(any()) } returns result

        val intent = Intent(service, LocationService::class.java).apply {
            action = LocationService.ACTION_ACTIVITY_TRANSITION
        }

        controller.withIntent(intent).startCommand(0, 1)

        // 3. Verify priority and interval updated for WALKING
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, service.currentPriority)
        assertEquals(10_000L, service.currentInterval)

        // 4. Simulate STILL transition
        val stillEvent = ActivityTransitionEvent(
            DetectedActivity.STILL,
            ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            System.currentTimeMillis()
        )
        io.mockk.every { result.transitionEvents } returns listOf(stillEvent)

        controller.withIntent(intent).startCommand(0, 2)

        // 5. Verify priority and interval updated for STILL
        assertEquals(Priority.PRIORITY_LOW_POWER, service.currentPriority)
        assertEquals(LocationService.HEARTBEAT_INTERVAL_MS, service.currentInterval)
        
        unmockkStatic(ActivityTransitionResult::class)
    }
}
