package net.af0.where

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.UserStore
import net.af0.where.shared.MR

private const val TAG = "LocationService"

enum class WakeSource(val value: String) {
    TIMER("Timer"),
    ALARM("Alarm"),
    LOCATION_UPDATE("GPS"),
    ACTIVITY_TRANSITION("Activity"),
    HEARTBEAT("Heartbeat"),
    NETWORK("Network"),
    MANUAL("Manual"),
}

/**
 * Foreground service that keeps the process alive and handles both GPS tracking
 * and the E2EE protocol work (polling, sending location).
 */
class LocationService : Service() {
    @VisibleForTesting
    internal var fusedClientOverride: com.google.android.gms.location.FusedLocationProviderClient? = null

    @VisibleForTesting
    internal var activityRecognitionClientOverride: ActivityRecognitionClient? = null

    @VisibleForTesting
    internal var e2eeManagerOverride: E2eeManager? = null

    @VisibleForTesting
    internal var locationClientOverride: LocationClient? = null

    @VisibleForTesting
    internal var locationSourceOverride: LocationSource? = null

    @VisibleForTesting
    internal var uiStateStoreOverride: UiStateSource? = null

    private lateinit var alarmManager: AlarmManager
    private lateinit var pollWakeLock: PowerManager.WakeLock
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var geofencingClient: com.google.android.gms.location.GeofencingClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var passiveLocationCallback: LocationCallback

    @VisibleForTesting
    internal var isRegistered = false

    @VisibleForTesting
    internal var isPassiveRegistered = false

    @VisibleForTesting
    internal var isActivityRegistered = false

    @VisibleForTesting
    internal var currentPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY

    @VisibleForTesting
    internal var currentInterval = 60_000L

    @VisibleForTesting
    internal var isStill = false

    private var lastSuccessfulSendTime: Long? = null

    private val pendingFriendSends = Channel<String>(Channel.UNLIMITED)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var e2eeManager: E2eeManager

    private fun logReliability(
        source: WakeSource,
        success: Boolean,
        intervalMs: Long? = null,
    ) {
        val status = if (success) "OK" else "ERR"
        var message = "Wake: ${source.value} -> $status"
        if (intervalMs != null) {
            val mins = intervalMs / 60000
            val secs = (intervalMs % 60000) / 1000
            message += if (mins > 0) " (Interval: ${mins}m ${secs}s)" else " (Interval: ${secs}s)"
        }
        e2eeManager.addDiagnosticEvent(message)
    }
    private lateinit var userStore: UserStore
    private lateinit var locationClient: LocationClient
    private lateinit var locationSource: LocationSource
    private lateinit var uiStateStore: UiStateSource

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasActivityPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()

        val app = application as WhereApplication
        locationSource = locationSourceOverride ?: app.locationSource
        uiStateStore = uiStateStoreOverride ?: app.uiStateStore
        userStore = app.userStore

        // Always call startForeground immediately to avoid ForegroundServiceDidNotStartInTimeException.
        startForeground(NOTIFICATION_ID, buildNotification())

        alarmManager = getSystemService(AlarmManager::class.java)
        pollWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "where:poll_alarm").also {
                it.setReferenceCounted(false)
            }

        e2eeManager = e2eeManagerOverride ?: app.e2eeManager
        locationClient = locationClientOverride ?: app.locationClient
        fusedClient = fusedClientOverride ?: LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = activityRecognitionClientOverride ?: ActivityRecognition.getClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)

        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                }
            }

        passiveLocationCallback =
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

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available, triggering syncNow()")
                    serviceScope.launch {
                        try {
                            locationClient.syncNow()
                            logReliability(WakeSource.NETWORK, true)
                        } catch (_: Exception) {
                            logReliability(WakeSource.NETWORK, false)
                        }
                    }
                }
            }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)

        serviceScope.launch {
            userStore.isSharingLocation.collect {
                updateNotification()
                ensureLocationRegistration()
            }
        }
        serviceScope.launch {
            locationSource.friends.collect { ensureLocationRegistration() }
        }
        serviceScope.launch {
            locationSource.allPendingInvites.collect { ensureLocationRegistration() }
        }

        serviceScope.launch { pollLoop() }
        serviceScope.launch {
            locationSource.lastLocation.collect { loc ->
                if (loc != null) {
                    if (userStore.isSharingLocation.value) {
                        sendLocationIfNeeded(loc.first, loc.second, isHeartbeat = false, source = WakeSource.LOCATION_UPDATE)
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

    private var isAlarmWakePending = false

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "onStartCommand: isRegistered=$isRegistered")
        ensureLocationRegistration()
        if (BuildConfig.ACTIVITY_RECOGNITION_ENABLED) {
            ensureActivityRecognitionRegistration()
        }

        if (BuildConfig.ACTIVITY_RECOGNITION_ENABLED && intent?.action == ACTION_ACTIVITY_TRANSITION) {
            val result = com.google.android.gms.location.ActivityTransitionResult.extractResult(intent)
            if (result != null) {
                for (event in result.transitionEvents) {
                    Log.d(TAG, "Activity Transition: ${event.activityType} (${event.transitionType})")
                    val newPriority =
                        when {
                            event.activityType == DetectedActivity.STILL && deepSleepWhenStationary ->
                                Priority.PRIORITY_PASSIVE
                            event.activityType == DetectedActivity.STILL ->
                                Priority.PRIORITY_LOW_POWER
                            else -> Priority.PRIORITY_HIGH_ACCURACY
                        }
                    val newInterval =
                        when {
                            event.activityType == DetectedActivity.STILL && deepSleepWhenStationary ->
                                60_000L
                            event.activityType == DetectedActivity.STILL ->
                                HEARTBEAT_INTERVAL_MS
                            else -> 10_000L
                        }

                    if (newPriority != currentPriority || newInterval != currentInterval) {
                        currentPriority = newPriority
                        currentInterval = newInterval
                        Log.i(TAG, "Updating location request: priority=$currentPriority, interval=$currentInterval")
                        logReliability(WakeSource.ACTIVITY_TRANSITION, true)

                        if (event.activityType == DetectedActivity.STILL) {
                            isStill = true
                            val loc = locationSource.lastLocation.value
                            if (loc != null) {
                                setGeofenceAt(loc.first, loc.second)
                            }
                        } else {
                            isStill = false
                            removeGeofence()
                        }

                        // Force re-registration with new settings.
                        isRegistered = false
                        ensureLocationRegistration()
                    }
                }
            }
        }

        if (intent?.action == ACTION_POLL_ALARM) {
            // Acquire a wakelock so the CPU stays awake for the GPS fix + network send.
            // setExactAndAllowWhileIdle wakes the CPU but doesn't hold it; without this
            // the device can sleep again before forceLocationUpdateAndGet() completes.
            if (!pollWakeLock.isHeld) pollWakeLock.acquire(60_000L)
            isAlarmWakePending = true
            locationSource.wakePoll()
        }
        if (intent?.action == ACTION_GEOFENCE_EVENT) {
            Log.d(TAG, "onStartCommand: Received Geofence Exit event")
            removeGeofence()
            // Resume full tracking
            currentPriority = Priority.PRIORITY_HIGH_ACCURACY
            currentInterval = 10_000L
            isStill = false
            isRegistered = false
            ensureLocationRegistration()
            logReliability(WakeSource.ALARM, true) // Geofence is a form of alarm wake
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

        return START_STICKY
    }

    private fun setGeofenceAt(
        lat: Double,
        lng: Double,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val geofence =
            com.google.android.gms.location.Geofence.Builder()
                .setRequestId("stationary_fence")
                .setCircularRegion(lat, lng, MOVEMENT_RADIUS_THRESHOLD_METERS)
                .setExpirationDuration(com.google.android.gms.location.Geofence.NEVER_EXPIRE)
                .setTransitionTypes(com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()

        val request =
            com.google.android.gms.location.GeofencingRequest.Builder()
                .setInitialTrigger(com.google.android.gms.location.GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofence(geofence)
                .build()

        try {
            geofencingClient.addGeofences(request, getGeofencePendingIntent())
            Log.i(TAG, "Stationary: Geofence set at $lat, $lng")
            e2eeManager.addDiagnosticEvent("Stationary: Geofence set")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting geofence: ${e.message}")
        }
    }

    private fun removeGeofence() {
        geofencingClient.removeGeofences(getGeofencePendingIntent())
        Log.i(TAG, "Moving: Geofence removed")
    }

    private fun getGeofencePendingIntent(): PendingIntent {
        val intent = Intent(this, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureLocationRegistration() {
        val hasPermission = hasLocationPermission()
        val isSharing = userStore.isSharingLocation.value

        if (!hasPermission || !isSharing) {
            if (isRegistered) {
                Log.i(
                    TAG,
                    "Location registration no longer needed (permission=$hasPermission, sharing=$isSharing); resetting registration state.",
                )
                try {
                    fusedClient.removeLocationUpdates(locationCallback)
                } catch (_: SecurityException) {
                }
                isRegistered = false
            }
            if (isPassiveRegistered) {
                try {
                    fusedClient.removeLocationUpdates(passiveLocationCallback)
                } catch (_: SecurityException) {
                }
                isPassiveRegistered = false
            }
            // Note: We don't call stopSelf() here even if permissions are missing or sharing is paused.
            // This is intentional:
            // 1. To avoid ForegroundServiceDidNotStartInTimeException on startup.
            // 2. To allow the service to continue polling for friend updates in the background
            //    even if we cannot share our own location.
            // 3. To provide a persistent notification warning the user that their location
            //    sharing intent is failing due to missing permissions or is paused.
            return
        }

        if (locationSource.friends.value.isEmpty() && locationSource.allPendingInvites.value.isEmpty()) {
            if (isRegistered) {
                try { fusedClient.removeLocationUpdates(locationCallback) } catch (_: SecurityException) {}
                isRegistered = false
            }
            if (isPassiveRegistered) {
                try { fusedClient.removeLocationUpdates(passiveLocationCallback) } catch (_: SecurityException) {}
                isPassiveRegistered = false
            }
            return
        }

        if (isRegistered) return

        if (!isPassiveRegistered) {
            val passiveRequest =
                LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 1_000L)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
            try {
                fusedClient.requestLocationUpdates(passiveRequest, passiveLocationCallback, mainLooper)
                isPassiveRegistered = true
                Log.i(TAG, "Passive location updates registered.")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException while requesting passive location updates: ${e.message}")
            }
        }

        val request =
            LocationRequest.Builder(currentPriority, currentInterval)
                .setMinUpdateIntervalMillis(10_000L) // Floor on active-registration delivery; passive piggybacking handled by PRIORITY_PASSIVE registration above.
                .setMinUpdateDistanceMeters(MOVEMENT_RADIUS_THRESHOLD_METERS)
                .setMaxUpdateDelayMillis(60_000L)
                .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isRegistered = true
            Log.i(TAG, "Location updates registered successfully with priority=$currentPriority.")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while requesting location updates: ${e.message}")
        }
    }

    private fun ensureActivityRecognitionRegistration() {
        val hasPermission = hasActivityPermission()
        val isSharing = userStore.isSharingLocation.value

        if (!hasPermission || !isSharing) {
            if (isActivityRegistered) {
                Log.i(TAG, "Activity recognition no longer needed; removing updates.")
                activityRecognitionClient.removeActivityTransitionUpdates(getActivityTransitionPendingIntent())
                isActivityRegistered = false
            }
            return
        }

        if (isActivityRegistered) return

        val transitions = mutableListOf<ActivityTransition>()
        val activities =
            listOf(
                DetectedActivity.STILL,
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.IN_VEHICLE,
            )

        for (activity in activities) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            )
        }

        val request = ActivityTransitionRequest(transitions)
        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, getActivityTransitionPendingIntent())
            isActivityRegistered = true
            Log.i(TAG, "Activity transition updates registered successfully.")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while requesting activity transitions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register activity transitions: ${e.message}")
        }
    }

    private fun getActivityTransitionPendingIntent(): PendingIntent {
        val intent = Intent(this, LocationService::class.java).apply { action = ACTION_ACTIVITY_TRANSITION }
        return PendingIntent.getService(this, PENDING_INTENT_REQUEST_CODE_ACTIVITY, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException removing location updates in onDestroy", e)
        }
        if (isPassiveRegistered) {
            try {
                fusedClient.removeLocationUpdates(passiveLocationCallback)
            } catch (_: SecurityException) {
            }
            isPassiveRegistered = false
        }
        if (BuildConfig.ACTIVITY_RECOGNITION_ENABLED && isActivityRegistered) {
            try {
                activityRecognitionClient.removeActivityTransitionUpdates(getActivityTransitionPendingIntent())
            } catch (_: Exception) {
            }
            isActivityRegistered = false
        }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
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
            val isSharing = userStore.isSharingLocation.value
            val source = if (isAlarmWakePending) {
                isAlarmWakePending = false
                WakeSource.ALARM
            } else {
                WakeSource.TIMER
            }
            if (locationSource.friends.value.isEmpty() && locationSource.allPendingInvites.value.isEmpty()) {
                Log.i(TAG, "No friends or pending invites; stopping service.")
                stopSelf()
                return
            }
            // Always poll — even when sharing is off we need to process incoming
            // EpochRotations and post Ratchet Acks so Alice's location doesn't get
            // stuck.  The interval is 30 min in that case (maintenance-only).
            doPoll(source)
            // Heartbeat: ensure we send at least once every 5 minutes when stationary.
            // Runs regardless of foreground state so background location stays alive.
            if (isSharing) {
                val now = clock()
                val loc =
                    if (now - lastSentTime > STATIONARY_FORCE_UPDATE_THRESHOLD_MS) {
                        if (isStill) {
                            Log.d(TAG, "Stationary and STILL; skipping forced GPS fix, will re-report cached location.")
                            null
                        } else {
                            Log.d(TAG, "Stationary threshold exceeded; forcing fresh location fix.")
                            forceLocationUpdateAndGet()
                        }
                    } else {
                        null
                    }

                val lastLoc =
                    if (loc != null) {
                        locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                        Triple(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                    } else {
                        locationSource.lastLocation.value
                    }

                if (lastLoc != null) {
                    // Force the heartbeat send. The pollLoop timing (5 mins) is already
                    // what we want for stationary updates, and 'force' ensures we bypass
                    // the internal 5-min de-duplication check which might be too tight.
                    sendLocationIfNeeded(lastLoc.first, lastLoc.second, isHeartbeat = true, force = true, source = WakeSource.HEARTBEAT)
                } else {
                    // RECOVERY (§5.3): If we have no GPS fix but are sharing, send a
                    // keepalive message to all active friends to keep the session alive
                    // and let them know we're still there.
                    try {
                        val activeFriends =
                            e2eeManager.listFriends().filter {
                                it.id !in userStore.pausedFriendIds.value && !it.isStale
                            }
                        for (friend in activeFriends) {
                            locationClient.sendKeepalive(friend.id)
                        }
                        lastSentTime = now
                        logReliability(WakeSource.HEARTBEAT, true)
                    } catch (_: Exception) {
                    }
                }
            }
            val interval = pollInterval(rapid, inForeground, isSharing)
            // Always schedule a doze alarm as a fallback wakeup, even during rapid polling.
            // Without this, backgrounding during key exchange can silently stall the service.
            scheduleDozeAlarm(maxOf(interval, STATIONARY_FORCE_UPDATE_THRESHOLD_MS))
            locationSource.awaitPollWake(interval)
            cancelDozeAlarm()
        }
    }

    private fun scheduleDozeAlarm(delayMs: Long) {
        val intent = Intent(this, LocationService::class.java).apply { action = ACTION_POLL_ALARM }
        val pi = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // Some OEMs restrict setExactAndAllowWhileIdle despite it not formally requiring
            // SCHEDULE_EXACT_ALARM. Fall back to inexact but still Doze-aware alarm.
            Log.w(TAG, "setExactAndAllowWhileIdle denied; using inexact fallback: ${e.message}")
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
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
            else -> 30 * 60 * 1000L // maintenance-only (Ratchet keepalives and Acks). Required for DH sync during global pause.
        }

    @VisibleForTesting
    internal suspend fun isRapidPolling(): Boolean {
        // We consider it rapid if the share sheet is open, or we're in key exchange naming.
        val now = clock()
        val recentlyTriggered = now - locationSource.lastRapidPollTrigger.value < 60_000L
        val isSheetShowing = uiStateStore.isInviteSheetShowing.value
        // Also check if Bob is on the naming screen.
        val isNaming = uiStateStore.pendingQrForNaming.value != null
        return isSheetShowing || locationSource.pendingInitPayload.value != null || recentlyTriggered || isNaming
    }

    @VisibleForTesting
    internal var lastCleanupTime: Long = 0L

    internal suspend fun doPoll(source: WakeSource = WakeSource.TIMER) {
        try {
            Log.d(TAG, "Polling for location updates (source=${source.value})")
            val now = clock()
            if (now - lastCleanupTime > 3600_000L) {
                e2eeManager.cleanupExpiredInvites(48 * 3600L)
                lastCleanupTime = now
            }
            val updates =
                locationClient.poll(
                    isForeground = locationSource.isAppInForeground.value,
                    pausedFriendIds = userStore.pausedFriendIds.value,
                )
            Log.d(TAG, "Got ${updates.size} location updates")
            withContext(Dispatchers.Main) {
                val now = System.currentTimeMillis()
                for (update in updates) {
                    locationSource.onFriendUpdate(update, now)
                    locationSource.onFriendLocationReceived(update.userId)
                    // Persistence: use the timestamp from the update payload.
                    e2eeManager.updateLastLocation(update.userId, update.lat, update.lng, update.timestamp)
                }
                pollPendingInvites()
                locationSource.onFriendsUpdated(e2eeManager.listFriends())
                locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
                updateStatus(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    private suspend fun pollPendingInvites() {
        try {
            val results = locationClient.pollPendingInvites()
            if (results.isEmpty()) return

            val pendingInvites = e2eeManager.listPendingInvites()
            val filteredResults =
                results.filter { result ->
                    pendingInvites.any { it.qrPayload.ekPub.contentEquals(result.inviteEkPub) }
                }

            if (filteredResults.isEmpty()) {
                Log.d(TAG, "pollPendingInvites: received ${results.size} results, but none match active pending invites. Ignoring.")
                return
            }

            // If we already have a naming dialog up, don't overwrite it, but the UI
            // will now be able to see all pending invites via allPendingInvites.
            if (locationSource.pendingInitPayload.value == null) {
                val result = filteredResults.first()
                if (result.pairingError != null) {
                    withContext(Dispatchers.Main) {
                        uiStateStore.setInviteSheetShowing(false)
                        updateStatus(Exception(result.pairingError))
                    }
                    return
                }
                val initPayload = result.payload
                Log.d(
                    TAG,
                    "pollPendingInvites: received KeyExchangeInit from ${initPayload.suggestedName} " +
                        "(multipleScans=${result.multipleScansDetected})",
                )
                withContext(Dispatchers.Main) {
                    uiStateStore.setMultipleScansDetected(result.multipleScansDetected)
                    uiStateStore.setInviteSheetShowing(false)
                    locationSource.onPendingInit(initPayload, result.inviteEkPub) // THE FIX: Pass our own EK
                    updateStatus(null)
                }
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
        source: WakeSource = WakeSource.LOCATION_UPDATE,
    ) {
        if (!userStore.isSharingLocation.value) return
        val now = clock()
        val interval = lastSuccessfulSendTime?.let { now - it }
        val shouldSend =
            sendLock.withLock {
                val canSend =
                    force || lastSentTime == 0L ||
                        (!isHeartbeat && now - lastSentTime > MIN_SEND_INTERVAL_MS) ||
                        (isHeartbeat && now - lastSentTime > HEARTBEAT_INTERVAL_MS)
                if (canSend) {
                    lastSentTime = now
                    true
                } else {
                    false
                }
            }
        if (!shouldSend) return
        try {
            locationClient.sendLocation(lat, lng, userStore.pausedFriendIds.value)
            logReliability(source, true, interval)
            lastSuccessfulSendTime = now
            updateStatus(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Restore lastSentTime on failure so the next update can retry immediately.
            sendLock.withLock {
                lastSentTime = 0L
            }
            Log.e(TAG, "Failed to send location: ${e.message}")
            logReliability(source, false, interval)
            updateStatus(e)
        }
    }

    @VisibleForTesting
    internal suspend fun forceLocationUpdateAndGet(): android.location.Location? {
        return try {
            val loc = withTimeoutOrNull(10_000L) {
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            }
            when {
                loc != null -> Log.d(TAG, "Forced location fix successful: ${loc.latitude}, ${loc.longitude}")
                else -> {
                    Log.d(TAG, "Forced location fix timed out or returned null; falling back to last known location")
                    return fusedClient.lastLocation.await()
                }
            }
            loc
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during forced location fix: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Forced location fix failed: ${e.message}")
            null
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

    private fun buildNotification(): Notification {
        // Use the live repository state for the notification.
        // Note: onCreate ensures this is initialised from UserPrefs before the first call.
        val sharing = userStore.isSharingLocation.value
        val hasPermission = hasLocationPermission()
        val text =
            when {
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
        const val ACTION_ACTIVITY_TRANSITION = "net.af0.where.ACTION_ACTIVITY_TRANSITION"
        const val ACTION_GEOFENCE_EVENT = "net.af0.where.ACTION_GEOFENCE_EVENT"
        private const val PENDING_INTENT_REQUEST_CODE_ACTIVITY = 1
        const val EXTRA_FRIEND_ID = "friend_id"

        /**
         * Minimum distance in meters before a location update is considered movement
         * and broadcast to friends.
         */
        const val MOVEMENT_RADIUS_THRESHOLD_METERS = 200f

        /**
         * Interval to wait before forcing a fresh GPS fix if stationary (§5.3).
         * This "pokes" the fused location provider to ensure background updates
         * flow even when the OS throttles streaming updates in sleep mode.
         */
        const val STATIONARY_FORCE_UPDATE_THRESHOLD_MS = 5 * 60 * 1000L

        /** Minimum interval between non-heartbeat (movement-triggered) location sends. */
        const val MIN_SEND_INTERVAL_MS = 30_000L

        /** Interval between heartbeat sends when stationary. */
        const val HEARTBEAT_INTERVAL_MS = 300_000L

        /**
         * When true, on STILL transitions demote the FLP request to PRIORITY_PASSIVE
         * (i.e. the GPS subsystem only piggybacks on other apps' fixes) and rely
         * solely on the doze alarm + WorkManager periodic restart for heartbeat
         * wakeups. Battery drops to near zero, but in deep Doze the alarm can be
         * deferred to the next maintenance window (often >1h), so the heartbeat
         * cadence collapses overnight.
         *
         * When false (default), on STILL transitions keep the FLP request alive
         * at PRIORITY_LOW_POWER + 5-min interval. FLP satisfies these callbacks
         * from wifi/cell/cached fixes without powering the GNSS chip, and — since
         * the foreground service is `location` typed — the callbacks are exempt
         * from Doze, giving us a deterministic ~5-min heartbeat wake source.
         */
        var deepSleepWhenStationary = false

        /** Overridable in tests. */
        var clock: () -> Long = { System.currentTimeMillis() }

        private const val CHANNEL_ID = "where_location"
        private const val NOTIFICATION_ID = 1
    }
}
