package net.af0.where

import android.content.Context
import android.content.Intent

// Activity recognition is GMS-only and only enabled in the `full` flavor
// (ACTIVITY_RECOGNITION_ENABLED=true). The F-Droid build ships `standard` only,
// so this is intentionally a no-op — power management falls back to the fixed
// heartbeat interval in LocationService.
class FdroidActivityHelper : ActivityHelper {
    override fun init(context: Context) {}
    override fun extractTransitionEvents(intent: Intent): List<ActivityTransitionEvent>? = null
    override fun ensureRegistered(hasPermission: Boolean, isSharing: Boolean) {}
    override fun unregister() {}
    override fun onDestroy() {}
}
