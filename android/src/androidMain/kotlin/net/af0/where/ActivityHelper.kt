package net.af0.where

import android.content.Context
import android.content.Intent

enum class ActivityType { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE }
enum class TransitionType { ENTER, EXIT }

data class ActivityTransitionEvent(val type: ActivityType, val transition: TransitionType)

interface ActivityHelper {
    fun init(context: Context)
    fun extractTransitionEvents(intent: Intent): List<ActivityTransitionEvent>?
    fun ensureRegistered(hasPermission: Boolean, isSharing: Boolean)
    fun unregister()
    fun onDestroy()
}
