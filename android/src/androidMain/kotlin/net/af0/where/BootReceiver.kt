package net.af0.where

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        // MY_PACKAGE_REPLACED: an app update kills the process and START_STICKY doesn't survive
        // it; without this, sharing silently stopped until the user next opened the app.
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            if (UserPrefs.isSharing(context)) {
                Log.d(TAG, "Starting LocationService after $action")
                try {
                    context.startForegroundService(Intent(context, LocationService::class.java))
                } catch (e: IllegalStateException) {
                    // Boot and package-replaced broadcasts are normally exempt from background
                    // FGS-start limits; don't crash the receiver if a device disagrees. Sharing
                    // then resumes when the app is next opened.
                    if (!isBackgroundStartNotAllowed(e)) throw e
                    Log.w(TAG, "Could not start LocationService after $action: ${e.message}")
                }
            } else {
                Log.d(TAG, "Skipping LocationService after boot because sharing is disabled.")
            }
        }
    }
}
