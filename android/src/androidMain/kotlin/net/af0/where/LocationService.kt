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
import kotlinx.coroutines.delay
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
    WORKER("Worker"),
    GEOFENCE("Geofence"),
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

    @VisibleForTesting
    internal var consecutiveLateHeartbeats = 0

    @VisibleForTesting
    internal var lastLocationCallbackTime: Long = 0L

    @VisibleForTesting
    internal var lastRegistrationTime: Long = 0L

    private data class RecentFix(val ts: Long, val lat: Double, val lng: Double)

    private val recentFixes = ArrayDeque<RecentFix>()

    private fun recordRecentFix(lat: Double, lng: Double) {
        val now = clock()
        recentFixes.addLast(RecentFix(now, lat, lng))
        val cutoff = now - RECENT_FIX_WINDOW_MS
        while (recentFixes.isNotEmpty() && recentFixes.first().ts < cutoff) {
            recentFixes.removeFirst()
        }
    }

    @VisibleForTesting
    internal fun maxRecentDisplacementMeters(): Float {
        if (recentFixes.size < 2) return 0f
        val results = FloatArray(1)
        var maxD = 0f
        val list = recentFixes.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                android.location.Location.distanceBetween(
                    list[i].lat, list[i].lng,
                    list[j].lat, list[j].lng,
                    results,
                )
                if (results[0] > maxD) maxD = results[0]
            }
        }
        return maxD
    }

    private val pendingFriendSends = Channel<String>(Channel.UNLIMITED)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var e2eeManager: E2eeManager

    private fun logReliability(
        source: WakeSource,
        success: Boolean,
        intervalMs: Long? = null,
    ) {
        val status = if (success) "OK" else "ERR"
        val prefix = "Wake: ${source.value} -> $status"
        var message = prefix
        if (intervalMs != null) {
            val mins = intervalMs / 60000
            val secs = (intervalMs % 60000) / 1000
            message += if (mins > 0) " (Interval: ${mins}m ${secs}s)" else " (Interval: ${secs}s)"
        }
        // Coalesce consecutive successes from the same source so frequent heartbeats
        // don't push more interesting events out of the bounded diagnostic buffer.
        // Errors are never coalesced.
        val coalesceKey = if (success) prefix else null
        e2eeManager.addDiagnosticEvent(message, coalesceKey)
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
                    lastLocationCallbackTime = clock()
                    recordRecentFix(loc.latitude, loc.longitude)
                    locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                }
            }

        passiveLocationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    lastLocationCallbackTime = clock()
                    recordRecentFix(loc.latitude, loc.longitude)
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
                        // Plant the initial geofence. Activity Recognition only fires on
                        // transitions, so if the device is already stationary at start no
                        // STILL-enter fires and the geofence would never be set otherwise.
                        setGeofenceAt(loc.latitude, loc.longitude)
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
                    pollWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                    serviceScope.launch {
                        try {
                            try {
                                locationClient.syncNow()
                                logReliability(WakeSource.NETWORK, true)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                logReliability(WakeSource.NETWORK, false)
                            }
                        } finally {
                            if (pollWakeLock.isHeld) pollWakeLock.release()
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
                        if (!userStore.isSharingLocation.value) break
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

    // The wake source that drove the next pollLoop iteration, set by onStartCommand for
    // alarm/worker-initiated wakes and consumed (cleared) once by the loop. Null means the
    // loop ran off its own timer (WakeSource.TIMER).
    private var pendingWakeSource: WakeSource? = null

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
                    // Cross-check: if Activity Recognition reports STILL but we've actually
                    // moved >100m in the last 2 minutes (e.g. phone-in-pocket while walking),
                    // ignore the signal rather than demoting the location request.
                    if (event.activityType == DetectedActivity.STILL &&
                        event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    ) {
                        val displacement = maxRecentDisplacementMeters()
                        if (displacement > STILL_DISPLACEMENT_IGNORE_METERS) {
                            Log.i(TAG, "Ignoring STILL enter: moved ${displacement.toInt()}m in last 2 min")
                            e2eeManager.addDiagnosticEvent("STILL ignored (moved ${displacement.toInt()}m)")
                            continue
                        }
                    }
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
                        } else {
                            isStill = false
                        }
                        // Always maintain a geofence as a belt-and-suspenders restart trigger
                        // in case the foreground service is killed. On MOVING transitions we
                        // replant it at the current position rather than removing it so that
                        // a 200m displacement still wakes us regardless of activity state.
                        val loc = locationSource.lastLocation.value
                        if (loc != null) {
                            setGeofenceAt(loc.first, loc.second)
                        }

                        // Force re-registration with new settings.
                        isRegistered = false
                        ensureLocationRegistration()
                    }
                }
            }
        }

        // Acquire the wake lock for any intent that drives real work (GPS fix + network send).
        // Applied unconditionally — no isHeld guard — so back-to-back wakes refresh the timeout.
        // ACTION_GEOFENCE_EVENT is included because it launches forceLocationUpdateAndGet()
        // which can take 10 s+, and Samsung re-enters Doze immediately after onReceive() in
        // GeofenceReceiver returns if no wake lock is held here.
        if (intent?.action == ACTION_POLL_ALARM || intent?.action == ACTION_HEARTBEAT_TICK ||
            intent?.action == ACTION_GEOFENCE_EVENT) {
            pollWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        if (intent?.action == ACTION_POLL_ALARM || intent?.action == ACTION_HEARTBEAT_TICK) {
            // ACTION_HEARTBEAT_TICK comes from LocationServiceRestartWorker (15-min cadence),
            // useful on Samsung where setExactAndAllowWhileIdle is heavily throttled.
            pendingWakeSource = if (intent.action == ACTION_HEARTBEAT_TICK) WakeSource.WORKER else WakeSource.ALARM
            locationSource.wakePoll()
        }
        if (intent?.action == ACTION_GEOFENCE_EVENT) {
            Log.d(TAG, "onStartCommand: Received Geofence Exit event")
            currentPriority = Priority.PRIORITY_HIGH_ACCURACY
            currentInterval = 10_000L
            isStill = false
            isRegistered = false
            ensureLocationRegistration()
            logReliability(WakeSource.GEOFENCE, true)
            // Use the triggering location from the geofence event to replant the fence,
            // so we don't anchor at a stale cached position the user has already moved past.
            val geofenceLat = intent.getDoubleExtra(EXTRA_GEOFENCE_LAT, Double.NaN)
            val geofenceLng = intent.getDoubleExtra(EXTRA_GEOFENCE_LNG, Double.NaN)
            val hasTriggeringLoc = !geofenceLat.isNaN() && !geofenceLng.isNaN()
            if (hasTriggeringLoc) {
                setGeofenceAt(geofenceLat, geofenceLng)
            }
            // Force a fresh fix before waking the poll loop. Without this, the service
            // re-registers with FLP but can wait 30 s+ for the first streaming callback;
            // the wake lock would expire on Samsung before the send completes.
            serviceScope.launch {
                val loc = forceLocationUpdateAndGet()
                if (loc != null) {
                    locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                    setGeofenceAt(loc.latitude, loc.longitude)
                } else if (!hasTriggeringLoc) {
                    val cached = locationSource.lastLocation.value
                    if (cached != null) setGeofenceAt(cached.first, cached.second)
                }
                locationSource.wakePoll()
            }
        }
        if (intent?.action == ACTION_FORCE_PUBLISH) {
            val friendId = intent.getStringExtra(EXTRA_FRIEND_ID)
            if (userStore.isSharingLocation.value) {
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
                } else {
                    // Broadcast to all (non-paused) friends — used on master sharing toggle off→on
                    // so peers see us without waiting for the next regular location tick.
                    val loc = locationSource.lastLocation.value
                    if (loc != null) {
                        serviceScope.launch {
                            sendLocationIfNeeded(
                                loc.first,
                                loc.second,
                                isHeartbeat = false,
                                force = true,
                                source = WakeSource.LOCATION_UPDATE,
                            )
                        }
                    }
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

        // When moving, keep max delivery delay tight (10s) so FLP doesn't batch up to a
        // full minute of fixes before delivering — that batching can mask "moving"
        // updates by up to 60s in marginal conditions. When STILL, batching is fine.
        val maxDelay = if (isStill) 60_000L else 10_000L
        val request =
            LocationRequest.Builder(currentPriority, currentInterval)
                .setMinUpdateIntervalMillis(10_000L) // Floor on active-registration delivery; passive piggybacking handled by PRIORITY_PASSIVE registration above.
                .setMinUpdateDistanceMeters(MOVEMENT_RADIUS_THRESHOLD_METERS)
                .setMaxUpdateDelayMillis(maxDelay)
                .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isRegistered = true
            lastRegistrationTime = clock()
            lastLocationCallbackTime = 0L  // reset so watchdog waits for the first callback from this registration
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
        // On cold/headless restart the StateFlow starts empty — hydrate once before the
        // first iteration so we don't stopSelf() before sending anything.
        if (locationSource.friends.value.isEmpty() && locationSource.allPendingInvites.value.isEmpty()) {
            locationSource.onFriendsUpdated(e2eeManager.listFriends())
            locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
        }
        // The loop continues until serviceScope is cancelled in onDestroy().
        while (true) {
            val rapid = isRapidPolling()
            val inForeground = locationSource.isAppInForeground.value
            val isSharing = userStore.isSharingLocation.value
            val pending = pendingWakeSource
            val source = if (pending != null) {
                pendingWakeSource = null
                pending
            } else {
                WakeSource.TIMER
            }
            if (locationSource.friends.value.isEmpty() && locationSource.allPendingInvites.value.isEmpty()) {
                Log.i(TAG, "No friends or pending invites; stopping service.")
                stopSelf()
                return
            }
            // Watchdog: Samsung (and some other OEMs) can silently revoke FLP registrations
            // without any error callback. If we're in moving mode and haven't seen a callback
            // for STALE_REGISTRATION_THRESHOLD_MS, force a re-registration.
            val now = clock()
            if (isRegistered && !isStill) {
                val referenceTime = if (lastLocationCallbackTime > 0L) lastLocationCallbackTime else lastRegistrationTime
                if (referenceTime > 0L && now - referenceTime > STALE_REGISTRATION_THRESHOLD_MS) {
                    Log.w(TAG, "FLP callbacks stale for ${(now - referenceTime) / 60000}min; re-registering")
                    e2eeManager.addDiagnosticEvent("FLP stale; re-registering")
                    isRegistered = false
                    lastLocationCallbackTime = 0L
                    ensureLocationRegistration()
                }
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
                                it.id !in userStore.effectivelyPausedIds() && !it.isStale
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
            // Schedule the doze alarm above the platform's ~9-min minimum for
            // setExactAndAllowWhileIdle. Scheduling below that causes silent deferral to the
            // next maintenance window — often hours on Samsung in deep Doze.
            // During rapid polling (key exchange) keep the tight interval for responsiveness.
            // FLP PRIORITY_LOW_POWER callbacks drive the finer-grained stationary heartbeat
            // while the foreground service is alive; the alarm is the dead-service safety net.
            scheduleDozeAlarm(if (rapid) interval else maxOf(DOZE_ALARM_INTERVAL_MS, interval))
            // All work for this wake is complete. Release the wake lock now so we don't hold
            // it across the entire sleep interval (could be 10+ min in background).
            if (pollWakeLock.isHeld) pollWakeLock.release()
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
                    pausedFriendIds = userStore.effectivelyPausedIds(),
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

        // Retry transient send failures within the same wake; otherwise a single
        // network blip during a 5-min heartbeat tick costs us another full poll
        // interval (5 min background) before the next attempt, stacking gaps fast.
        var lastError: Exception? = null
        val totalAttempts = 1 + SEND_RETRY_DELAYS_MS.size
        for (attempt in 0 until totalAttempts) {
            if (attempt > 0) {
                delay(SEND_RETRY_DELAYS_MS[attempt - 1])
                if (!userStore.isSharingLocation.value) return
            }
            try {
                locationClient.sendLocation(lat, lng, userStore.effectivelyPausedIds())
                val sendCompleteTime = clock()
                logReliability(source, true, interval)
                checkLateHeartbeat(interval)
                lastSuccessfulSendTime = sendCompleteTime
                updateStatus(null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Send attempt ${attempt + 1}/$totalAttempts failed: ${e.message}")
            }
        }

        // All retries exhausted — restore lastSentTime so the next wake can retry.
        sendLock.withLock { lastSentTime = 0L }
        Log.e(TAG, "Failed to send location after $totalAttempts attempts: ${lastError?.message}")
        logReliability(source, false, interval)
        updateStatus(lastError ?: Exception("send failed"))
    }

    private fun checkLateHeartbeat(intervalMs: Long?) {
        if (intervalMs == null) return
        if (intervalMs > 2 * HEARTBEAT_INTERVAL_MS) {
            consecutiveLateHeartbeats += 1
            val mins = intervalMs / 60_000
            val secs = (intervalMs % 60_000) / 1000
            e2eeManager.addDiagnosticEvent(
                "Late heartbeat: ${mins}m ${secs}s gap (count=$consecutiveLateHeartbeats)",
            )
        } else {
            consecutiveLateHeartbeats = 0
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
        const val ACTION_HEARTBEAT_TICK = "net.af0.where.ACTION_HEARTBEAT_TICK"
        private const val PENDING_INTENT_REQUEST_CODE_ACTIVITY = 1
        const val EXTRA_FRIEND_ID = "friend_id"
        const val EXTRA_GEOFENCE_LAT = "geofence_lat"
        const val EXTRA_GEOFENCE_LNG = "geofence_lng"

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

        /** Backoff delays for in-wake send retries on transient network failure. */
        val SEND_RETRY_DELAYS_MS = longArrayOf(5_000L, 20_000L)

        /**
         * Wake lock timeout. Long enough to cover a GPS fix (~10 s) plus a network send on a
         * cold Samsung radio. Without this, setExactAndAllowWhileIdle/geofence wakes the CPU
         * but the device can re-enter Doze before the send completes.
         */
        const val WAKE_LOCK_TIMEOUT_MS = 120_000L

        /**
         * Interval for the dead-service doze alarm. setExactAndAllowWhileIdle is rate-limited
         * to roughly one delivery per ~9 min by the Android platform (enforced more aggressively
         * on Samsung); scheduling below this causes silent deferral to the next maintenance
         * window, which can be hours in deep Doze. FLP PRIORITY_LOW_POWER callbacks drive the
         * finer-grained stationary heartbeat while the foreground service is alive.
         */
        const val DOZE_ALARM_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * If no FLP callback has arrived for this long while in non-STILL mode, treat the
         * registration as silently dropped and force a re-registration. Samsung's power
         * management can revoke FLP registrations without any error callback.
         */
        const val STALE_REGISTRATION_THRESHOLD_MS = 15 * 60 * 1000L

        /** Window of recent GPS fixes consulted by the STILL cross-check. */
        const val RECENT_FIX_WINDOW_MS = 2 * 60 * 1000L

        /**
         * If Activity Recognition reports STILL but we've actually moved more than this
         * many meters in the last [RECENT_FIX_WINDOW_MS], ignore the signal — common
         * mis-classification when the phone is in a steady pocket while walking.
         */
        const val STILL_DISPLACEMENT_IGNORE_METERS = 100f

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
