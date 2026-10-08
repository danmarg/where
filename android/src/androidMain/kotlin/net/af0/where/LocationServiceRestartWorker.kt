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
        // Always poll here rather than relying on LocationService: acks, keepalives and pairing
        // handshakes are what keep a session from expiring (ACK_TIMEOUT_SECONDS), and they must
        // keep happening when the service can't run - no location permission on API 34+, or only
        // "while in use" permission so Android refuses to start a location foreground service
        // from the background - or has simply been killed. FriendPoller serializes with the
        // service's own polling, so doing this while the service is healthy is just a cheap no-op.
        app.friendPoller.poll(WakeSource.HEARTBEAT)

        if (!applicationContext.canRunLocationService()) {
            Log.i(TAG, "LocationService cannot run (no location permission); polled directly")
            return Result.success()
        }
        Log.i(TAG, "WorkManager heartbeat: ensuring LocationService is running + forcing tick")
        // Best effort: ACTION_HEARTBEAT_TICK nudges an already-running but Doze-stalled service
        // (startForegroundService alone is a no-op when it is up) and restarts a killed one for
        // location sharing. Starting a foreground service from the background can be refused
        // (ForegroundServiceStartNotAllowedException), which must not fail the work.
        try {
            startService(
                Intent(applicationContext, LocationService::class.java).apply {
                    action = LocationService.ACTION_HEARTBEAT_TICK
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start LocationService from background: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "LocationServiceRestartWorker"
        const val WORK_NAME = "location_service_keepalive"
    }
}
