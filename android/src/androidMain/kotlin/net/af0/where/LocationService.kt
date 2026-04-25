package net.af0.where

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient
import net.af0.where.shared.MR

private const val TAG = "LocationService"

/**
 * Foreground service that keeps the process alive and handles both GPS tracking
 * and the E2EE protocol work (polling, sending location).
 */
class LocationService : Service() {
    @VisibleForTesting
    internal var fusedClientOverride: com.google.android.gms.location.FusedLocationProviderClient? = null

    @VisibleForTesting
    internal var e2eeStoreOverride: E2eeStore? = null

    @VisibleForTesting
    internal var locationClientOverride: LocationClient? = null

    private lateinit var alarmManager: AlarmManager
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isRegistered = false

    private val pendingFriendSends = Channel<String>(Channel.UNLIMITED)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var e2eeStore: E2eeStore
    private lateinit var locationClient: LocationClient

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()

        // Initialise repository sharing state from prefs before starting any collection.
        val sharing = UserPrefs.isSharing(this)
        locationSource.setSharingLocation(sharing)

        // Always call startForeground immediately to avoid ForegroundServiceDidNotStartInTimeException.
        // We pass sharing explicitly here because the LocationSource state flow might not have
        // propagated the update yet.
        startForeground(NOTIFICATION_ID, buildNotification(sharing))

        alarmManager = getSystemService(AlarmManager::class.java)

        val app = application as WhereApplication
        e2eeStore = e2eeStoreOverride ?: app.e2eeStore
        locationClient = locationClientOverride ?: app.locationClient
        fusedClient = fusedClientOverride ?: LocationServices.getFusedLocationProviderClient(this)

        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                }
            }

        try {
            if (hasLocationPermission()) {
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        locationSource.onLocation(
                            loc.latitude,
                            loc.longitude,
                            if (loc.hasBearing()) loc.bearing.toDouble() else null,
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
        }

        ensureLocationRegistration()

        serviceScope.launch {
            locationSource.isSharingLocation.collect {
                updateNotification()
            }
        }

        serviceScope.launch { pollLoop() }
        serviceScope.launch {
            locationSource.lastLocation.collect { loc ->
                if (loc != null) {
                    if (locationSource.isSharingLocation.value) {
                        sendLocationIfNeeded(loc.first, loc.second, isHeartbeat = false)
                    }

                    while (true) {
                        val friendId = pendingFriendSends.tryReceive().getOrNull() ?: break
                        launch {
                            try {
                                locationClient.sendLocationToFriend(friendId, loc.first, loc.second)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send deferred location to $friendId: ${e.message}")
                            }
                        }
                    }

                    // When our own location changes, trigger a poll for friend updates.
                    // This ensures that whenever we share, we also receive.
                    locationSource.wakePoll()
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "onStartCommand: isRegistered=$isRegistered")
        ensureLocationRegistration()
        if (intent?.action == ACTION_POLL_ALARM) {
            locationSource.wakePoll()
        }
        if (intent?.action == ACTION_FORCE_PUBLISH) {
            val friendId = intent.getStringExtra(EXTRA_FRIEND_ID)
            if (friendId != null) {
                val loc = locationSource.lastLocation.value
                if (loc != null) {
                    serviceScope.launch {
                        try {
                            locationClient.sendLocationToFriend(friendId, loc.first, loc.second)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send forced location to $friendId: ${e.message}")
                        }
                    }
                } else {
                    pendingFriendSends.trySend(friendId)
                }
            }
        }

        ensureLocationRegistration()
        return START_STICKY
    }

    private fun ensureLocationRegistration() {
        if (isRegistered) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission; skipping GPS updates registration.")
            return
        }

        val request =
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
                .setMinUpdateIntervalMillis(15_000L)
                .setMinUpdateDistanceMeters(10f)
                .setMaxUpdateDelayMillis(60_000L)
                .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isRegistered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while requesting location updates: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove location updates in onDestroy", e)
        }
        isRegistered = false
        pendingFriendSends.close()
        cancelDozeAlarm()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @VisibleForTesting
    internal var lastSentTime: Long = 0L
    private val sendLock = Mutex()

    private suspend fun pollLoop() {
        // The loop continues until serviceScope is cancelled in onDestroy().
        while (true) {
            val rapid = isRapidPolling()
            val inForeground = locationSource.isAppInForeground.value
            val isSharing = locationSource.isSharingLocation.value
            // Always poll — even when sharing is off we need to process incoming
            // EpochRotations and post Ratchet Acks so Alice's location doesn't get
            // stuck.  The interval is 30 min in that case (maintenance-only).
            doPoll()
            // Heartbeat: ensure we send at least once every 5 minutes when stationary.
            // Runs regardless of foreground state so background location stays alive.
            if (isSharing) {
                val now = clock()
                if (now - lastSentTime > STATIONARY_FORCE_UPDATE_THRESHOLD_MS) {
                    Log.d(TAG, "Stationary threshold exceeded; forcing fresh location fix.")
                    forceLocationUpdate()
                }

                val lastLoc = locationSource.lastLocation.value
                if (lastLoc != null) {
                    // Force the heartbeat send. The pollLoop timing (5 mins) is already
                    // what we want for stationary updates, and 'force' ensures we bypass
                    // the internal 5-min de-duplication check which might be too tight.
                    sendLocationIfNeeded(lastLoc.first, lastLoc.second, isHeartbeat = true, force = true)
                } else {
                    // RECOVERY (§5.3): If we have no GPS fix but are sharing, send a
                    // keepalive message to all active friends to keep the session alive
                    // and let them know we're still there.
                    try {
                        val activeFriends =
                            e2eeStore.listFriends().filter {
                                it.id !in locationSource.pausedFriendIds.value && !it.isStale
                            }
                        for (friend in activeFriends) {
                            locationClient.sendKeepalive(friend.id)
                        }
                        lastSentTime = now
                    } catch (_: Exception) {
                    }
                }
            }
            val interval = pollInterval(rapid, inForeground, isSharing)
            if (interval >= 5 * 60 * 1000L) scheduleDozeAlarm(interval)
            locationSource.awaitPollWake(interval)
            cancelDozeAlarm()
        }
    }

    private fun scheduleDozeAlarm(delayMs: Long) {
        val intent = Intent(this, LocationService::class.java).apply { action = ACTION_POLL_ALARM }
        val pi = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    }

    private fun cancelDozeAlarm() {
        val intent = Intent(this, LocationService::class.java).apply { action = ACTION_POLL_ALARM }
        val pi = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) alarmManager.cancel(pi)
    }

    @VisibleForTesting
    internal fun pollInterval(
        rapid: Boolean,
        inForeground: Boolean,
        isSharingLocation: Boolean = true,
    ): Long =
        when {
            rapid -> 2_000L
            inForeground -> 10_000L
            isSharingLocation -> 5 * 60 * 1000L // heartbeat + friend poll
            else -> 30 * 60 * 1000L // maintenance-only (Ratchet Acks)
        }

    @VisibleForTesting
    internal suspend fun isRapidPolling(): Boolean {
        // We consider it rapid if an invite is pending, or we're in key exchange.
        // The ViewModel triggers this via LocationRepository.triggerRapidPoll().
        // For simplicity, we also rapid-poll if there's a pending init payload.
        val now = clock()
        val recentlyTriggered = now - locationSource.lastRapidPollTrigger.value < 5 * 60_000L
        val hasPendingQr = e2eeStore.pendingQrPayload() != null
        // Also check if Bob is on the naming screen.
        val isNaming = locationSource.pendingQrForNaming.value != null
        return hasPendingQr || locationSource.pendingInitPayload.value != null || recentlyTriggered || isNaming
    }

    internal suspend fun doPoll() {
        try {
            Log.d(TAG, "Polling for location updates")
            val updates = locationClient.poll(isForeground = locationSource.isAppInForeground.value)
            Log.d(TAG, "Got ${updates.size} location updates")
            withContext(Dispatchers.Main) {
                val now = System.currentTimeMillis()
                for (update in updates) {
                    locationSource.onFriendUpdate(update, now)
                    locationSource.onFriendLocationReceived(update.userId)
                    // Persistence: use the timestamp from the update payload.
                    e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, update.timestamp)
                }
                pollPendingInvite()
                locationSource.onFriendsUpdated(e2eeStore.listFriends())
                updateStatus(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    private suspend fun pollPendingInvite() {
        if (locationSource.pendingInitPayload.value != null) return
        try {
            val result = locationClient.pollPendingInvite() ?: return
            val initPayload = result.payload
            Log.d(
                TAG,
                "pollPendingInvite: received KeyExchangeInit from ${initPayload.suggestedName} " +
                    "(multipleScans=${result.multipleScansDetected})",
            )
            withContext(Dispatchers.Main) {
                locationSource.onPendingInit(initPayload, result.multipleScansDetected)
                updateStatus(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateStatus(e)
        }
    }

    internal suspend fun sendLocationIfNeeded(
        lat: Double,
        lng: Double,
        isHeartbeat: Boolean,
        force: Boolean = false,
    ) {
        if (!locationSource.isSharingLocation.value) return
        val now = clock()
        val shouldSend =
            sendLock.withLock {
                val canSend =
                    force || lastSentTime == 0L ||
                        !isHeartbeat ||
                        (isHeartbeat && now - lastSentTime > 300_000L)
                if (canSend) {
                    lastSentTime = now
                    true
                } else {
                    false
                }
            }
        if (!shouldSend) return
        try {
            locationClient.sendLocation(lat, lng, locationSource.pausedFriendIds.value)
            updateStatus(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Restore lastSentTime on failure so the next update can retry immediately.
            sendLock.withLock {
                lastSentTime = 0L
            }
            Log.e(TAG, "Failed to send location: ${e.message}")
            updateStatus(e)
        }
    }

    private fun forceLocationUpdate() {
        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.d(TAG, "Forced location fix successful: ${loc.latitude}, ${loc.longitude}")
                        locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Forced location fix failed: ${e.message}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during forced location fix: ${e.message}")
        }
    }

    private fun updateStatus(e: Throwable?) {
        if (e == null) {
            locationSource.onConnectionStatus(ConnectionStatus.Ok)
        } else {
            locationSource.onConnectionError(e)
        }
    }

    private fun stringResource(resource: dev.icerock.moko.resources.StringResource): String {
        return resource.getString(this)
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                stringResource(MR.strings.where_location),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(sharingOverride: Boolean? = null): Notification {
        val sharing = sharingOverride ?: LocationRepository.isSharingLocation.value
        val hasPermission = hasLocationPermission()
        val text = when {
            sharing && !hasPermission -> stringResource(MR.strings.location_permission_missing)
            !sharing && !hasPermission -> stringResource(MR.strings.location_sharing_paused_no_permission)
            sharing -> stringResource(MR.strings.sharing_your_location)
            else -> stringResource(MR.strings.location_sharing_paused)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(stringResource(MR.strings.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_POLL_ALARM = "net.af0.where.ACTION_POLL_ALARM"
        const val ACTION_FORCE_PUBLISH = "net.af0.where.ACTION_FORCE_PUBLISH"
        const val EXTRA_FRIEND_ID = "friend_id"

        /**
         * Interval to wait before forcing a fresh GPS fix if stationary (§5.3).
         * This "pokes" the fused location provider to ensure background updates
         * flow even when the OS throttles streaming updates in sleep mode.
         */
        const val STATIONARY_FORCE_UPDATE_THRESHOLD_MS = 5 * 60 * 1000L

        /** Overridable in tests; defaults to the production singleton. */
        var locationSource: LocationSource = LocationRepository

        /** Overridable in tests. */
        var clock: () -> Long = { System.currentTimeMillis() }

        private const val CHANNEL_ID = "where_location"
        private const val NOTIFICATION_ID = 1
    }
}
