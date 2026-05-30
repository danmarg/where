package net.af0.where

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LocationServiceRestartWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? WhereApplication ?: return Result.success()
        val friends = app.e2eeManager.listFriends()
        val invites = app.e2eeManager.listPendingInvites()
        if (friends.isEmpty() && invites.isEmpty()) {
            Log.i(TAG, "No friends or pending invites; skipping service restart")
            return Result.success()
        }
        Log.i(TAG, "WorkManager heartbeat: ensuring LocationService is running + forcing tick")
        // Use ACTION_HEARTBEAT_TICK so an already-running but Doze-stalled service
        // gets nudged into running its poll/heartbeat path — startForegroundService
        // alone is a no-op when the service is already up.
        applicationContext.startForegroundService(
            Intent(applicationContext, LocationService::class.java).apply {
                action = LocationService.ACTION_HEARTBEAT_TICK
            },
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "LocationServiceRestartWorker"
        const val WORK_NAME = "location_service_keepalive"
    }
}
