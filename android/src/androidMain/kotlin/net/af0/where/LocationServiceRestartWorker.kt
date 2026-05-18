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
        Log.i(TAG, "WorkManager heartbeat: ensuring LocationService is running")
        applicationContext.startForegroundService(
            Intent(applicationContext, LocationService::class.java)
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "LocationServiceRestartWorker"
        const val WORK_NAME = "location_service_keepalive"
    }
}
