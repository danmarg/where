package net.af0.where

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

private const val TAG = "GeofenceReceiver"

/**
 * Receiver that handles geofence exit events.
 * This is a critical fallback (§4.1) that allows the app to wake up even if the
 * main LocationService has been killed by the system.
 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            Log.e(TAG, "Geofence error: ${event?.errorCode}")
            return
        }

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d(TAG, "Geofence exit detected, restarting/waking LocationService")
            val serviceIntent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_GEOFENCE_EVENT
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
