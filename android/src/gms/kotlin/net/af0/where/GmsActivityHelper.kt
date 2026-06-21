package net.af0.where

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

private const val TAG = "GmsActivityHelper"

class GmsActivityHelper : ActivityHelper {
    private lateinit var context: Context
    private lateinit var client: ActivityRecognitionClient
    private var isRegistered = false

    override fun init(context: Context) {
        this.context = context.applicationContext
        client = ActivityRecognition.getClient(context)
    }

    override fun extractTransitionEvents(intent: Intent): List<ActivityTransitionEvent>? {
        val result = ActivityTransitionResult.extractResult(intent) ?: return null
        return result.transitionEvents.mapNotNull { event ->
            val type = when (event.activityType) {
                DetectedActivity.STILL -> ActivityType.STILL
                DetectedActivity.WALKING -> ActivityType.WALKING
                DetectedActivity.RUNNING -> ActivityType.RUNNING
                DetectedActivity.ON_BICYCLE -> ActivityType.ON_BICYCLE
                DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
                else -> return@mapNotNull null
            }
            val transition = when (event.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> TransitionType.ENTER
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> TransitionType.EXIT
                else -> return@mapNotNull null
            }
            ActivityTransitionEvent(type, transition)
        }
    }

    override fun ensureRegistered(hasPermission: Boolean, isSharing: Boolean) {
        if (!hasPermission || !isSharing) {
            if (isRegistered) {
                Log.i(TAG, "Activity recognition no longer needed; removing updates.")
                client.removeActivityTransitionUpdates(getPendingIntent())
                isRegistered = false
            }
            return
        }
        if (isRegistered) return

        val activities = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )
        val transitions = activities.flatMap { activity ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            )
        }
        val request = ActivityTransitionRequest(transitions)
        try {
            client.requestActivityTransitionUpdates(request, getPendingIntent())
            isRegistered = true
            Log.i(TAG, "Activity transition updates registered.")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering activity transitions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register activity transitions: ${e.message}")
        }
    }

    override fun unregister() {
        if (isRegistered) {
            try {
                client.removeActivityTransitionUpdates(getPendingIntent())
            } catch (_: Exception) {}
            isRegistered = false
        }
    }

    override fun onDestroy() {
        unregister()
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_ACTIVITY_TRANSITION
        }
        return PendingIntent.getService(
            context,
            LocationService.PENDING_INTENT_REQUEST_CODE_ACTIVITY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
