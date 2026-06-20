package net.af0.where

import android.content.Context
import android.content.Intent

class FdroidActivityHelper : ActivityHelper {
    override fun init(context: Context) {}
    override fun extractTransitionEvents(intent: Intent): List<ActivityTransitionEvent>? = null
    override fun ensureRegistered(hasPermission: Boolean, isSharing: Boolean) {}
    override fun unregister() {}
    override fun onDestroy() {}
}
