package net.af0.where

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class LocationServiceRestartWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @VisibleForTesting
    internal var startService: (Intent) -> Unit = { applicationContext.startForegroundService(it) }

    @VisibleForTesting
    internal var clock: () -> Long = { System.currentTimeMillis() }

    override suspend fun doWork(): Result {
        val app = applicationContext as? WhereApplication ?: return Result.success()
        val friends = app.e2eeManager.listFriends()
        val invites = app.e2eeManager.listPendingInvites()
        if (friends.isEmpty() && invites.isEmpty()) {
            // The service stops with no one left, so a farewell to the last removed friend that
            // failed to send is only retried from here.
            try {
                app.locationClient.processOrphanedOutboxes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "processOrphanedOutboxes failed: ${e.message}")
            }
            Log.i(TAG, "No friends or pending invites; skipping service restart")
            return Result.success()
        }
        // Acks, keepalives and pairing handshakes keep friends from pausing us
        // (INACTIVITY_PAUSE_SECONDS), so they must keep happening when LocationService isn't running.
        // It only runs in the background while we share (#481): when we don't (or can't, without
        // location permission), poll from here. When it does run, nudging it is enough - it polls
        // itself, and polling here too would double the background request rate.
        if (!applicationContext.needsBackgroundService(app.userStore)) {
            Log.i(TAG, "LocationService not needed in background; polling directly")
            pollThrottled(app)
            return Result.success()
        }
        if (!ServicePolicy.shouldNudgeFromWorker(clock(), app.servicePolledAtMs.takeIf { it != 0L })) {
            Log.i(TAG, "LocationService polled within its cadence; skipping heartbeat tick")
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
            // Unthrottled: we're sharing and nothing else polls.
            app.friendPoller.poll(WakeSource.HEARTBEAT)
        }
        return Result.success()
    }

    /**
     * Polls unless this worker polled less than [MAINTENANCE_POLL_INTERVAL_MS] ago: the 15-min
     * work period mustn't double the ~30-min maintenance rate. Its own attempts only: a
     * friend's lastPollTs also advances on failed polls, e.g. offline in the foreground.
     */
    private suspend fun pollThrottled(app: WhereApplication) {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = clock()
        val age = now - prefs.getLong(KEY_LAST_POLL, 0L)
        // A clock set back (negative age) doesn't hold polling off.
        if (age in 0 until MAINTENANCE_POLL_INTERVAL_MS) {
            Log.i(TAG, "Polled ${age / 60_000} min ago; skipping")
            return
        }
        prefs.edit().putLong(KEY_LAST_POLL, now).apply()
        app.friendPoller.poll(WakeSource.HEARTBEAT)
    }

    companion object {
        private const val TAG = "LocationServiceRestartWorker"

        private const val PREFS = "where_prefs"
        private const val KEY_LAST_POLL = "worker_last_poll_ms"

        /**
         * The ~30-min maintenance rate with sharing off. Under 30 min because the 15-min work
         * period jitters, and a run landing just short of 30 would otherwise wait for 45.
         */
        internal const val MAINTENANCE_POLL_INTERVAL_MS = 25 * 60 * 1000L
        const val WORK_NAME = "location_service_keepalive"
    }
}
