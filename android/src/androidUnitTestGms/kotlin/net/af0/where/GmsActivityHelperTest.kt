package net.af0.where

import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class GmsActivityHelperTest {

    @Before
    fun setup() {
        mockkStatic(ActivityTransitionResult::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(ActivityTransitionResult::class)
    }

    private fun makeGmsEvent(activityType: Int, transitionType: Int) =
        ActivityTransitionEvent(activityType, transitionType, 0L)

    @Test
    fun extractTransitionEvents_returnsNullWhenNoResult() {
        every { ActivityTransitionResult.extractResult(any()) } returns null
        val helper = GmsActivityHelper()
        assertNull(helper.extractTransitionEvents(Intent()))
    }

    @Test
    fun extractTransitionEvents_mapsAllKnownActivities() {
        val gmsEvents = listOf(
            makeGmsEvent(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            makeGmsEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            makeGmsEvent(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            makeGmsEvent(DetectedActivity.ON_BICYCLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            makeGmsEvent(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
        )
        every { ActivityTransitionResult.extractResult(any()) } returns ActivityTransitionResult(gmsEvents)

        val helper = GmsActivityHelper()
        val events = helper.extractTransitionEvents(Intent())

        assertNotNull(events)
        assertEquals(5, events.size)
        assertEquals(ActivityType.STILL, events[0].type)
        assertEquals(TransitionType.ENTER, events[0].transition)
        assertEquals(ActivityType.WALKING, events[1].type)
        assertEquals(ActivityType.RUNNING, events[2].type)
        assertEquals(ActivityType.ON_BICYCLE, events[3].type)
        assertEquals(ActivityType.IN_VEHICLE, events[4].type)
        assertEquals(TransitionType.EXIT, events[4].transition)
    }

    @Test
    fun extractTransitionEvents_skipsUnknownActivityTypes() {
        val gmsEvents = listOf(
            makeGmsEvent(DetectedActivity.TILTING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            makeGmsEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        )
        every { ActivityTransitionResult.extractResult(any()) } returns ActivityTransitionResult(gmsEvents)

        val helper = GmsActivityHelper()
        val events = helper.extractTransitionEvents(Intent())

        assertNotNull(events)
        assertEquals(1, events.size)
        assertEquals(ActivityType.WALKING, events[0].type)
    }
}
