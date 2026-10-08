package net.af0.where

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LocationServiceRestartWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @VisibleForTesting
    internal var startService: (Intent) -> Unit = { applicationContext.startForegroundService(it) }

    override suspend fun doWork(): Result {
        val app = applicationContext as? WhereApplication ?: return Result.success()
        val friends = app.e2eeManager.listFriends()
        val invites = app.e2eeManager.listPendingInvites()
        if (friends.isEmpty() && invites.isEmpty()) {
            Log.i(TAG, "No friends or pending invites; skipping service restart")
            return Result.success()
        }
        // Acks, keepalives and pairing handshakes are what keep a session from expiring
        // (ACK_TIMEOUT_SECONDS), so they must keep happening when LocationService can't do them:
        // no location permission on API 34+, or Android refusing to start a location foreground
        // service from the background (e.g. only "while in use" permission). When the service is
        // startable, nudging it is enough - it polls itself, and polling here too would double
        // the background request rate.
        if (!applicationContext.canRunLocationService()) {
            Log.i(TAG, "LocationService cannot run (no location permission); polling directly")
            app.friendPoller.poll(WakeSource.HEARTBEAT)
            return Result.success()
        }
        Log.i(TAG, "WorkManager heartbeat: ensuring LocationService is running + forcing tick")
        // ACTION_HEARTBEAT_TICK nudges an already-running but Doze-stalled service (a plain
        // startForegroundService is a no-op when it is up) and restarts a killed one.
        try {
            startService(
                Intent(applicationContext, LocationService::class.java).apply {
                    action = LocationService.ACTION_HEARTBEAT_TICK
                },
            )
        } catch (e: Exception) {
            // Typically ForegroundServiceStartNotAllowedException; must not fail the work, and the
            // service isn't going to poll, so do it here.
            Log.w(TAG, "Could not start LocationService from background (${e.message}); polling directly")
            app.friendPoller.poll(WakeSource.HEARTBEAT)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "LocationServiceRestartWorker"
        const val WORK_NAME = "location_service_keepalive"
    }
}
