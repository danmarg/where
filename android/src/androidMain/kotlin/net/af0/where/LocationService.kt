package net.af0.where

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    internal var locationProviderOverride: LocationProvider? = null

    @VisibleForTesting
    internal var e2eeManagerOverride: E2eeManager? = null

    @VisibleForTesting
    internal var locationClientOverride: LocationClient? = null

    @VisibleForTesting
    internal var locationSourceOverride: LocationSource? = null

    @VisibleForTesting
    internal var uiStateStoreOverride: UiStateSource? = null

    @VisibleForTesting
    internal var friendPollerOverride: FriendPoller? = null

    // Robolectric's ShadowService doesn't emulate the real platform's foregroundServiceType
    // permission enforcement (startForeground() never throws there regardless of permission
    // state), so this lets tests simulate the SecurityException the real OS throws on API 34+.
    @VisibleForTesting
    internal var startForegroundOverride: (() -> Unit)? = null

    private lateinit var alarmManager: AlarmManager
    private lateinit var pollWakeLock: PowerManager.WakeLock
    private lateinit var networkWakeLock: PowerManager.WakeLock
    private lateinit var locationProvider: LocationProvider

    // Set when startForeground() throws in onCreate() (see there). Everything below that call
    // in onCreate() — including the lateinit properties above — never gets initialized in that
    // case, so onDestroy() must skip teardown entirely rather than dereference them.
    private var startForegroundFailed = false

    @VisibleForTesting
    internal var isRegistered = false

    @VisibleForTesting
    internal var isPassiveRegistered = false

    // "A fence may be registered", NOT "a fence is confirmed registered": set when an add is
    // requested (GMS may still fail it), cleared once a remove has been requested. Starts TRUE
    // because a NEVER_EXPIRE fence from a previous process outlives it, so the first time this
    // process sees sharing/permission off it removes one unconditionally (removal is
    // idempotent). A fence's exit event while not sharing also removes it - see
    // ACTION_GEOFENCE_EVENT.
    @VisibleForTesting
    internal var geofenceMayBeRegistered = true

    // Three separate pieces of plant state (see canAttemptGeofencePlant):
    //
    // needsGeofence: no fence established since startup or the last removal. Cleared only by a
    // plant GMS accepted (SUBMITTED/QUEUED), so it survives a FAILED one. While set, plant paths
    // keep trying: the startup and resume plants, and the next fix when no location was cached.
    @VisibleForTesting
    internal var needsGeofence = true

    // geofenceRetryBlocked: the last attempt FAILED (transient, e.g. a SecurityException). Holds
    // off further attempts - so every fix doesn't retry - until the next registration clears it
    // (e.g. the one after permission comes back).
    @VisibleForTesting
    internal var geofenceRetryBlocked = false

    // geofencingUnsupported: a plant returned UNSUPPORTED (F-Droid). Never retried in this process.
    private var geofencingUnsupported = false

    /** Center of the fence last requested in this process, or null if none or unknown; see [isStaleGeofenceExit]. */
    @VisibleForTesting
    internal var geofenceCenter: Pair<Double, Double>? = null

    @VisibleForTesting
    internal var currentPriority = LocationAccuracy.BALANCED

    @VisibleForTesting
    internal var currentInterval = BALANCED_INTERVAL_MS

    /** [elapsedClock] time of the last geofence exit; see [endHighAccuracyIfIdle]. */
    @VisibleForTesting
    internal var lastGeofenceExitAt = 0L

    @VisibleForTesting
    internal var consecutiveLateHeartbeats = 0

    @VisibleForTesting
    internal var lastLocationCallbackTime: Long = 0L

    @VisibleForTesting
    internal var lastRegistrationTime: Long = 0L

    private val pendingFriendSends = Channel<String>(Channel.UNLIMITED)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Tracks pollLoop()'s coroutine so onStartCommand can detect and recover from the case
    // where the loop has exited (e.g. via its own stopSelf() on the no-friends path) but this
    // Service instance is still alive because a racing startForegroundService() call landed
    // before Android finished tearing it down. stopSelf() is asynchronous and can be
    // superseded by a later start command; when that happens onCreate()/onDestroy() never
    // re-run, so nothing else would ever relaunch pollLoop.
    private var pollLoopJob: Job? = null

    private fun ensurePollLoopRunning() {
        val job = pollLoopJob
        if (job == null || job.isCompleted) {
            Log.w(TAG, "pollLoop not running (job=$job); relaunching")
            pollLoopJob = serviceScope.launch { pollLoop() }
        }
    }

    private lateinit var e2eeManager: E2eeManager

    private fun logReliability(
        source: WakeSource,
        success: Boolean,
        intervalMs: Long? = null,
        wakeTrigger: WakeSource? = null,
    ) {
        val status = if (success) "OK" else "ERR"
        val prefix = "Wake: ${source.value} -> $status"
        var message = prefix
        // wakeTrigger identifies what actually drove this pollLoop iteration (Alarm/Worker/Timer/
        // etc.) when it differs from `source` itself (e.g. a HEARTBEAT send triggered by a
        // WORKER-driven wake). Without this, every heartbeat/GPS-poll-failure event looks
        // identical regardless of whether the doze alarm, the WorkManager fallback, or the
        // service's own timer produced it - making it impossible to tell "the fallbacks are
        // firing but the send is failing" from "the fallbacks never fired at all" during a
        // multi-hour sync gap.
        if (wakeTrigger != null && wakeTrigger != source) {
            message += " [via ${wakeTrigger.value}]"
        }
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
    private var locationProvidersChangedReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()

        val app = application as WhereApplication
        locationSource = locationSourceOverride ?: app.locationSource
        uiStateStore = uiStateStoreOverride ?: app.uiStateStore
        userStore = app.userStore

        // Always call startForeground immediately to avoid ForegroundServiceDidNotStartInTimeException.
        // This is declared foregroundServiceType="location" in the manifest, so on API 34+ the OS
        // requires the location permission to already be held or this throws SecurityException.
        // Callers are expected to check permission before starting us, but permission can be
        // revoked between that check and this call (auto-revoke of unused permissions, or the
        // user flipping it off in Settings while we're already scheduled to (re)start) — so this
        // is a real, reachable race, not a defensive check against something that can't happen.
        // Android 12+ also throws ForegroundServiceStartNotAllowedException (an
        // IllegalStateException) when we were started from the background without an exemption,
        // e.g. by the doze alarm or activity-transition PendingIntents (PendingIntent.getService)
        // while the service was dead. Uncaught, that crashes the process.
        try {
            startForegroundOverride?.invoke() ?: startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Narrow on purpose: other IllegalStateExceptions here (e.g. a missing/invalid
            // foregroundServiceType) are configuration bugs that should crash loudly.
            if (e !is SecurityException && !isBackgroundStartNotAllowed(e)) throw e
            Log.e(TAG, "startForeground failed (${e::class.simpleName}); stopping service", e)
            startForegroundFailed = true
            stopSelf()
            return
        }

        alarmManager = getSystemService(AlarmManager::class.java)
        pollWakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "where:poll_alarm").also {
                    it.setReferenceCounted(false)
                }
        // Separate from pollWakeLock: both are non-reference-counted, so sharing one let a
        // network-change sync release the lock mid-way through an alarm-driven fix + send.
        networkWakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "where:network_sync").also {
                    it.setReferenceCounted(false)
                }

        e2eeManager = e2eeManagerOverride ?: app.e2eeManager
        locationClient = locationClientOverride ?: app.locationClient
        sendScheduler = createSendScheduler()
        net.af0.where.e2ee.KtorMailboxClient.onConnectionReset = {
            e2eeManager.addDiagnosticEvent("Mailbox HTTP client auto-reset after repeated failures")
        }

        locationProvider = locationProviderOverride ?: createLocationProvider()
        locationProvider.init(this) { lat, lng, bearing ->
            lastLocationCallbackTime = clock()
            locationSource.onLocation(lat, lng, bearing)
        }

        if (hasLocationPermission()) {
            locationProvider.getLastLocationAsync { loc ->
                if (loc != null) {
                    locationSource.onLocation(
                        loc.latitude,
                        loc.longitude,
                        if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    )
                    // Plant the initial geofence. Otherwise only a geofence exit replants it,
                    // so a device already stationary at start would never get one. No center is
                    // recorded: if an exit relaunched us, this lands where that exit triggered,
                    // and checking the exit against it would wrongly call it stale.
                    if (canAttemptGeofencePlant()) setGeofenceAt(loc.latitude, loc.longitude, recordCenter = false)
                }
            }
        }

        ensureLocationRegistration()

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available, triggering syncNow()")
                    networkWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                    serviceScope.launch {
                        // A location send backing off after failures may retry now.
                        sendScheduler.onNetworkRestored()
                        try {
                            try {
                                locationClient.syncNow(
                                    pausedFriendIds = userStore.effectivelyPausedIds(),
                                    // As FriendPoller's poll: without permission we send nothing,
                                    // so stops still owed are retried.
                                    sharingEnabled = canSendLocations(),
                                )
                                logReliability(WakeSource.NETWORK, true)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                logReliability(WakeSource.NETWORK, false)
                            }
                        } finally {
                            if (networkWakeLock.isHeld) networkWakeLock.release()
                        }
                    }
                }
            }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)

        // MapScreen.kt registers an equivalent PROVIDERS_CHANGED_ACTION receiver to gate its own
        // UI (independent of whether this service is even running yet, e.g. before sharing is
        // first turned on). Keep both receivers' logic in sync if this one changes.
        locationProvidersChangedReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    Log.d(TAG, "Location providers changed, refreshing registration and notification")
                    ensureLocationRegistration()
                    updateNotification()
                }
            }.also {
                ContextCompat.registerReceiver(
                    this,
                    it,
                    IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }

        serviceScope.launch {
            userStore.isSharingLocation.collect {
                if (!it) sendScheduler.reset()
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

        pollLoopJob = serviceScope.launch { pollLoop() }
        serviceScope.launch {
            locationSource.lastLocation.collect { loc ->
                if (loc != null) {
                    if (userStore.isSharingLocation.value && canAttemptGeofencePlant()) {
                        // No fence yet (no cached location at startup or on resume): plant at the
                        // first fix. No center, for the same reason as the startup plant.
                        setGeofenceAt(loc.first, loc.second, recordCenter = false)
                    }
                    if (userStore.isSharingLocation.value) sendScheduler.onFix(loc.first, loc.second)

                    while (true) {
                        val friendId = pendingFriendSends.tryReceive().getOrNull() ?: break
                        // Before reading the sharing state: a stop issued after it drops the send.
                        val stamp = locationClient.sharingStamp()
                        if (!canSendLocations()) break
                        // The friend may have been paused (or their timed share expired) while
                        // this send sat queued waiting for a fix.
                        if (friendId in userStore.effectivelyPausedIds()) continue
                        launch {
                            try {
                                locationClient.sendLocationToFriend(friendId, loc.first, loc.second, stamp = stamp)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send deferred location to $friendId: ${e.message}")
                            }
                        }
                    }

                    // When our own location changes, trigger a poll for friend updates.
                    // This ensures that whenever we share, we also receive. At most once per
                    // MIN_SEND_INTERVAL_MS: fixes arrive up to 1/s (the passive listener), and a
                    // poll on each would be as wasteful as a send on each.
                    val now = clock()
                    if (now - lastLocationPollWakeTime >= MIN_SEND_INTERVAL_MS) {
                        lastLocationPollWakeTime = now
                        locationSource.wakePoll()
                    }
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
        if (startForegroundFailed) {
            // onCreate() bailed out before initializing anything below the startForeground()
            // call (see there), so nothing below is safe to touch. A queued command can still
            // reach us here — e.g. ACTION_HEARTBEAT_TICK from LocationServiceRestartWorker,
            // which deliberately sends without a permission check — even after stopSelf(),
            // since AOSP delivers start commands separately from service creation/destruction.
            Log.w(TAG, "onStartCommand: startForeground previously failed; ignoring command")
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand: isRegistered=$isRegistered")
        ensurePollLoopRunning()
        ensureLocationRegistration()
        // Acquire the wake lock for any intent that drives real work (GPS fix + network send).
        // Applied unconditionally — no isHeld guard — so back-to-back wakes refresh the timeout.
        // ACTION_GEOFENCE_EVENT is included because it launches forceLocationUpdateAndGet()
        // which can take 10 s+, and Samsung re-enters Doze immediately after onReceive() in
        // GeofenceReceiver returns if no wake lock is held here.
        val staleGeofenceExit = intent?.action == ACTION_GEOFENCE_EVENT && isStaleGeofenceExit(intent)
        if (intent?.action == ACTION_POLL_ALARM || intent?.action == ACTION_HEARTBEAT_TICK ||
            (intent?.action == ACTION_GEOFENCE_EVENT && userStore.isSharingLocation.value && !staleGeofenceExit)
        ) {
            pollWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        if (intent?.action == ACTION_POLL_ALARM || intent?.action == ACTION_HEARTBEAT_TICK) {
            // ACTION_HEARTBEAT_TICK comes from LocationServiceRestartWorker (15-min cadence),
            // useful on Samsung where setExactAndAllowWhileIdle is heavily throttled.
            pendingWakeSource = if (intent.action == ACTION_HEARTBEAT_TICK) WakeSource.WORKER else WakeSource.ALARM
            locationSource.wakePoll()
        }
        if (intent?.action == ACTION_GEOFENCE_EVENT && !userStore.isSharingLocation.value) {
            // A NEVER_EXPIRE fence planted while sharing outlives the session (and the process).
            // Don't force a high-accuracy fix or replant it - remove it so it stops waking us.
            Log.d(TAG, "onStartCommand: Geofence event while not sharing; removing stale fence")
            locationProvider.removeGeofence()
        } else if (staleGeofenceExit) {
            // Not a departure from the fence we're watching (see isStaleGeofenceExit): don't start
            // a HIGH window or replant for it.
            Log.d(TAG, "onStartCommand: Geofence exit not from the current fence; ignoring")
            e2eeManager.addDiagnosticEvent("Stale geofence exit ignored")
        } else if (intent?.action == ACTION_GEOFENCE_EVENT) {
            Log.d(TAG, "onStartCommand: Received Geofence Exit event")
            currentPriority = LocationAccuracy.HIGH
            currentInterval = HIGH_INTERVAL_MS
            lastGeofenceExitAt = elapsedClock()
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
            // Before reading the sharing state: a stop issued after it drops the send.
            val stamp = locationClient.sharingStamp()
            if (canSendLocations()) {
                if (friendId != null && friendId in userStore.effectivelyPausedIds()) {
                    Log.d(TAG, "ACTION_FORCE_PUBLISH: $friendId is paused; not sending")
                } else if (friendId != null) {
                    val loc = locationSource.lastLocation.value
                    if (loc != null) {
                        serviceScope.launch {
                            try {
                                locationClient.sendLocationToFriend(friendId, loc.first, loc.second, stamp = stamp)
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
                    if (loc != null) sendScheduler.onFix(loc.first, loc.second)
                    sendScheduler.requestForced()
                }
            }
        }

        return START_STICKY
    }

    /** [recordCenter] false leaves [geofenceCenter] unknown, so exits aren't checked against it. */
    private fun setGeofenceAt(
        lat: Double,
        lng: Double,
        recordCenter: Boolean = true,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        // Radius sizing is shared with iOS (GeofencePolicy.radiusMeters(), Kotlin commonMain) so
        // the two platforms can't silently drift apart on "how big" the way the automated-
        // keepalive throttle once did. Android has no stationary signal, so it always uses the
        // moving radius. Re-centering *cadence* is NOT shared - unlike iOS's proactive per-fix
        // drift check (GeofencePolicy.shouldRecenter()), Android replants only on an actual
        // GEOFENCE_TRANSITION_EXIT (see ACTION_GEOFENCE_EVENT below), at service start, and when
        // tracking resumes without a fence (see ensureLocationRegistration). That's still
        // self-bounding - exiting the current fence is exactly what triggers the next
        // replant - just a different mechanism, not a shared one.
        // Only plant while sharing: async callers (getLastLocationAsync, the geofence-event fix)
        // can complete after sharing was turned off, and a fence planted then would never be
        // removed by ensureLocationRegistration().
        if (!userStore.isSharingLocation.value) return
        val radiusMeters = GeofencePolicy.radiusMeters(isMoving = true).toFloat()
        val result = locationProvider.setGeofenceAt(lat, lng, radiusMeters)
        when (result) {
            GeofenceRequestResult.SUBMITTED, GeofenceRequestResult.QUEUED -> {
                geofenceMayBeRegistered = true
                geofenceCenter = if (recordCenter) lat to lng else null
                needsGeofence = false
                geofenceRetryBlocked = false
            }
            // Transient: retried after the next registration, not on every fix.
            GeofenceRequestResult.FAILED -> geofenceRetryBlocked = true
            GeofenceRequestResult.UNSUPPORTED -> geofencingUnsupported = true
        }
        when (result) {
            GeofenceRequestResult.SUBMITTED -> {
                // GMS: request submitted; actual confirmation logged by provider's Task listener.
                Log.d(TAG, "Stationary: Geofence submitted at $lat, $lng")
                e2eeManager.addDiagnosticEvent("Stationary: Geofence submitted")
            }
            GeofenceRequestResult.QUEUED -> {
                // A prior request for a different target is still in flight; this one will
                // submit once that settles (GmsLocationProvider). Logged distinctly from
                // SUBMITTED so a diagnostics read of the event log doesn't claim this target
                // was sent to GMS before it actually was.
                Log.d(TAG, "Stationary: Geofence queued at $lat, $lng (prior request in flight)")
                e2eeManager.addDiagnosticEvent("Stationary: Geofence queued")
            }
            GeofenceRequestResult.FAILED -> {
                // This attempt failed (e.g. permission revoked mid-request); the next registration
                // retries.
                Log.w(TAG, "Stationary: geofence request failed; will retry on re-registration")
            }
            GeofenceRequestResult.UNSUPPORTED -> {
                // F-Droid build: geofencing unavailable (GMS-only). Movement-triggered restart
                // is absent; the service relies solely on WorkManager and the alarm fallback.
                Log.w(TAG, "Stationary: geofence not set — movement-triggered restart unavailable")
            }
        }
    }

    private fun ensureLocationRegistration() {
        val hasPermission = hasLocationPermission()
        val servicesEnabled = hasLocationServicesEnabled()
        val isSharing = userStore.isSharingLocation.value

        if (!hasPermission || !servicesEnabled || !isSharing) {
            if (isRegistered) {
                Log.i(
                    TAG,
                    "Location registration no longer needed " +
                        "(permission=$hasPermission, servicesEnabled=$servicesEnabled, sharing=$isSharing); " +
                        "resetting registration state.",
                )
                locationProvider.removeActiveUpdates()
                isRegistered = false
            }
            // A geofence exit's HIGH request belongs to that sharing session; the next one starts
            // at BALANCED rather than resuming HIGH until the next poll wake ends it. Only on
            // sharing off: a brief permission or location-services blip mid-drive keeps HIGH.
            if (!isSharing) resetToBalancedAccuracy()
            if (isPassiveRegistered) {
                locationProvider.removePassiveUpdates()
                isPassiveRegistered = false
            }
            if (geofenceMayBeRegistered) {
                locationProvider.removeGeofence()
                geofenceMayBeRegistered = false
                geofenceCenter = null
                needsGeofence = true
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
                locationProvider.removeActiveUpdates()
                isRegistered = false
            }
            if (isPassiveRegistered) {
                locationProvider.removePassiveUpdates()
                isPassiveRegistered = false
            }
            // Nothing to track for: like sharing off, a later session starts at BALANCED.
            resetToBalancedAccuracy()
            return
        }

        if (isRegistered) return

        if (!isPassiveRegistered) {
            isPassiveRegistered = locationProvider.requestPassiveUpdates()
            if (isPassiveRegistered) Log.i(TAG, "Passive location updates registered.")
        }

        // Keep max delivery delay tight (10s) so the provider doesn't batch up to a full minute of
        // fixes before delivering — that batching can mask "moving" updates by up to 60s in
        // marginal conditions.
        isRegistered = locationProvider.requestActiveUpdates(currentPriority, currentInterval, 10_000L)
        // A new registration is the retry point for a FAILED plant.
        if (isRegistered) geofenceRetryBlocked = false
        if (isRegistered && canAttemptGeofencePlant()) {
            // No fence established yet: at startup, after a FAILED plant, or after sharing turned
            // back on removed it. needsGeofence decides, not geofenceMayBeRegistered, which starts
            // true for a fence a previous process may have left - so a fresh process whose first
            // plant failed still retries here, without waiting for a fix that may never come
            // while stationary. Replanting the same fence ID is harmless.
            plantGeofenceAtLastKnownLocation()
        }
        if (isRegistered) {
            lastRegistrationTime = clock()
            lastLocationCallbackTime = 0L // reset so watchdog waits for the first callback from this registration
            Log.i(TAG, "Location updates registered successfully with priority=$currentPriority.")
        } else {
            Log.w(TAG, "requestActiveUpdates failed (likely missing permission); isRegistered=false")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        if (startForegroundFailed) {
            // onCreate() bailed out before initializing anything below the startForeground()
            // call, so there's nothing to tear down.
            super.onDestroy()
            return
        }
        locationProvider.onDestroy()
        isRegistered = false
        isPassiveRegistered = false
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        locationProvidersChangedReceiver?.let { unregisterReceiver(it) }
        isRegistered = false
        pendingFriendSends.close()
        cancelDozeAlarm()
        serviceScope.cancel()
        // pollLoop() is the only other place this is released; if the Service is torn down
        // while a wake is in flight (or after pollLoop has already died — see the
        // pollLoopJob recovery logic in onStartCommand), nothing else would ever release it,
        // holding a full-power partial wakelock for its 120s timeout.
        if (pollWakeLock.isHeld) pollWakeLock.release()
        if (networkWakeLock.isHeld) networkWakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastLocationPollWakeTime = 0L

    // What woke pollLoop for the heartbeat being drained, for the reliability log.
    private var heartbeatWakeTrigger: WakeSource? = null

    /** The only sender of broadcast Locations; see [LocationSendScheduler]. */
    @VisibleForTesting
    internal lateinit var sendScheduler: LocationSendScheduler

    private fun createSendScheduler() =
        LocationSendScheduler(
            client = locationClient.asBroadcastSender(),
            gate =
                object : SendGate {
                    override fun canSend() = canSendLocations()

                    override fun pausedFriendIds() = userStore.effectivelyPausedIds()

                    // Android has no stationary signal (no Activity Recognition; see #391).
                    override fun stationary() = false
                },
            // Monotonic: send intervals are elapsed time, and wall-clock changes mustn't move them.
            clock = { elapsedClock() },
            scope = serviceScope,
            listener = { reason, success, sinceLastSuccessMs, error ->
                val source =
                    when (reason) {
                        SendReason.HEARTBEAT -> WakeSource.HEARTBEAT
                        SendReason.FORCED, SendReason.LOCATION -> WakeSource.LOCATION_UPDATE
                    }
                logReliability(source, success, sinceLastSuccessMs, heartbeatWakeTrigger)
                if (success) checkLateHeartbeat(sinceLastSuccessMs)
                updateStatus(if (success) null else error ?: Exception("send failed"))
            },
        ).also { it.start() }

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
            val source =
                if (pending != null) {
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
            // without any error callback. If we haven't seen a callback for
            // STALE_REGISTRATION_THRESHOLD_MS, force a re-registration.
            val now = clock()
            if (isRegistered) {
                val referenceTime = if (lastLocationCallbackTime > 0L) lastLocationCallbackTime else lastRegistrationTime
                if (referenceTime > 0L && now - referenceTime > STALE_REGISTRATION_THRESHOLD_MS) {
                    Log.w(TAG, "FLP callbacks stale for ${(now - referenceTime) / 60000}min; re-registering")
                    e2eeManager.addDiagnosticEvent("FLP stale; re-registering")
                    isRegistered = false
                    lastLocationCallbackTime = 0L
                    ensureLocationRegistration()
                }
            }
            endHighAccuracyIfIdle(elapsedClock())
            // Always poll — even when sharing is off we need to process incoming
            // EpochRotations and post Ratchet Acks so Alice's location doesn't get
            // stuck.  The interval is 30 min in that case (maintenance-only).
            doPoll(source)
            // Heartbeat: ensure we send at least once every 5 minutes when stationary.
            // Runs regardless of foreground state so background location stays alive. Only when
            // due: this loop also wakes on location callbacks and the 10 s / 2 s timers, and
            // would otherwise force a GPS fix on each.
            if (isSharing && sendScheduler.isHeartbeatDue()) {
                Log.d(TAG, "Stationary threshold exceeded; forcing fresh location fix.")
                val loc = forceLocationUpdateAndGet()

                val lastLoc =
                    if (loc != null) {
                        locationSource.onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                        Triple(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                    } else {
                        locationSource.lastLocation.value
                    }

                if (lastLoc != null) {
                    // The scheduler sends the latest fix, reading "here since" stationarity at
                    // send time. Drained here so the send happens under this wake's wake lock.
                    heartbeatWakeTrigger = source
                    sendScheduler.onFix(lastLoc.first, lastLoc.second)
                    val drained = sendScheduler.wakeAndDrain()
                    heartbeatWakeTrigger = null
                    // Held back (a backoff after failures): it goes out on the scheduler's timer or
                    // a later wake, not under this wake lock.
                    if (drained is DrainResult.Pending) Log.d(TAG, "Heartbeat pending for ${drained.inMs}ms")
                } else {
                    // RECOVERY (§5.3): If we have no GPS fix but are sharing, send a
                    // keepalive message to all active friends to keep the session alive
                    // and let them know we're still there. Throttling (so an unresponsive
                    // friend isn't spammed every RECOVERY cycle) and the outbox-redundancy
                    // check live in LocationClient.sendRecoveryKeepalives, shared with
                    // sendLocation()'s per-friend throttle.
                    val sent =
                        try {
                            locationClient.sendRecoveryKeepalives(userStore.effectivelyPausedIds())
                            logReliability(WakeSource.HEARTBEAT, true, wakeTrigger = source)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    // Counts as this heartbeat's attempt either way, so the next wake doesn't
                    // force a GPS fix straight away.
                    sendScheduler.noteKeepalive(sent)
                }
            }
            val interval = pollInterval(rapid, inForeground, isSharing)
            // Schedule the doze alarm above the platform's ~9-min minimum for
            // setExactAndAllowWhileIdle. Scheduling below that causes silent deferral to the
            // next maintenance window — often hours on Samsung in deep Doze.
            // During rapid polling (key exchange) keep the tight interval for responsiveness.
            // FLP callbacks and the poll timer drive the finer-grained heartbeat while the
            // foreground service is alive; the alarm is the dead-service safety net.
            scheduleDozeAlarm(if (rapid) interval else maxOf(DOZE_ALARM_INTERVAL_MS, interval))
            // All work for this wake is complete. Release the wake lock now so we don't hold
            // it across the entire sleep interval (could be 10+ min in background).
            if (pollWakeLock.isHeld) pollWakeLock.release()
            locationSource.awaitPollWake(interval)
            cancelDozeAlarm()
        }
    }

    /**
     * Drops the HIGH / 10 s request a geofence exit started back to BALANCED / 60 s once
     * [HIGH_ACCURACY_IDLE_MS] pass without another exit. Nothing else lowers it, so without this
     * GPS stays on at high accuracy after arriving anywhere until the process dies. Each exit
     * replants the fence (moving radius) at the new position, so a device that keeps moving
     * keeps exiting and stays on HIGH; one that has arrived stops exiting. Runs on poll wakes,
     * so in deep Doze it can land up to a doze-alarm interval late. [now] is [elapsedClock] time.
     */
    @VisibleForTesting
    internal fun endHighAccuracyIfIdle(now: Long) {
        if (currentPriority != LocationAccuracy.HIGH || now - lastGeofenceExitAt < HIGH_ACCURACY_IDLE_MS) return
        Log.i(TAG, "No geofence exit for ${HIGH_ACCURACY_IDLE_MS / 60_000}min; back to balanced accuracy")
        e2eeManager.addDiagnosticEvent("Stopped moving; balanced accuracy")
        resetToBalancedAccuracy()
        isRegistered = false
        ensureLocationRegistration()
    }

    /** No fence is established, the last attempt didn't fail this registration, and geofencing works. */
    private fun canAttemptGeofencePlant(): Boolean = needsGeofence && !geofenceRetryBlocked && !geofencingUnsupported

    private fun plantGeofenceAtLastKnownLocation() {
        // Record the center only when this process removed the old fence itself (sharing went off
        // and on): then a pending exit from it can be told apart. While a previous process's fence
        // may still be live - at startup, or before any plant has succeeded - an exit can have
        // relaunched us from about where this plant lands, so no center (see the startup plant).
        val recordCenter = !geofenceMayBeRegistered
        val loc = locationSource.lastLocation.value
        if (loc != null) {
            setGeofenceAt(loc.first, loc.second, recordCenter)
        } else {
            // A fix may plant first, or an attempt fail first; check again when it lands.
            locationProvider.getLastLocationAsync { last ->
                if (last != null && canAttemptGeofencePlant()) setGeofenceAt(last.latitude, last.longitude, recordCenter)
            }
        }
    }

    /**
     * True if a geofence exit can't be a departure from the fence we're watching now, so it must
     * have been pending from an earlier one (e.g. delivered just after sharing went off and on):
     * - No fence may be registered: every removal clears [geofenceMayBeRegistered] and only a plant
     *   sets it, so the exit came from a fence we removed.
     * - Its triggering location is well inside the current fence ([geofenceCenter]): an exit of
     *   this fence is reported from at least its radius away. Half the radius leaves room for fix
     *   error. A stale exit triggered far from the current fence is accepted, which is right: the
     *   device has moved since. Exits with no triggering location, or before this process knows
     *   the center, can't be checked and are accepted.
     */
    private fun isStaleGeofenceExit(intent: Intent): Boolean {
        if (!geofenceMayBeRegistered) return true
        val center = geofenceCenter ?: return false
        val lat = intent.getDoubleExtra(EXTRA_GEOFENCE_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_GEOFENCE_LNG, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return false
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(center.first, center.second, lat, lng, distance)
        return distance[0] < GeofencePolicy.radiusMeters(isMoving = true) / 2
    }

    private fun resetToBalancedAccuracy() {
        currentPriority = LocationAccuracy.BALANCED
        currentInterval = BALANCED_INTERVAL_MS
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
            rapid -> FriendPoller.RAPID_POLL_INTERVAL_MS
            inForeground -> FriendPoller.FOREGROUND_POLL_INTERVAL_MS
            isSharingLocation -> 5 * 60 * 1000L // heartbeat + friend poll
            else -> 30 * 60 * 1000L // maintenance-only (Ratchet keepalives and Acks). Required for DH sync during global pause.
        }

    private val friendPoller: FriendPoller by lazy {
        friendPollerOverride ?: FriendPoller(
            locationClient, e2eeManager, userStore, locationSource, uiStateStore,
            clock = { clock() },
            gate = (application as? WhereApplication)?.pollGate ?: PollGate(),
            canShare = { hasLocationPermission() },
        )
    }

    @VisibleForTesting
    internal suspend fun isRapidPolling(): Boolean = friendPoller.isRapidPolling()

    internal suspend fun doPoll(source: WakeSource = WakeSource.TIMER) = friendPoller.poll(source)

    // Sharing is on and we hold location permission. Without permission the ViewModel has told
    // friends we stopped (reconcileSharingWithPermission) while sharing stays on as the user's
    // choice, so a cached fix must not go out after that stop; when permission returns, sends
    // resume. Read after taking a LocationClient.sharingStamp(), like the pause state.
    private fun canSendLocations(): Boolean = userStore.isSharingLocation.value && hasLocationPermission()

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
            val loc = locationProvider.getCurrentLocation()
            when {
                loc != null -> Log.d(TAG, "Forced location fix successful: ${loc.latitude}, ${loc.longitude}")
                else -> {
                    Log.d(TAG, "Forced location fix timed out or returned null; falling back to last known location")
                    return locationProvider.getLastLocation()
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
        val servicesEnabled = hasLocationServicesEnabled()
        val text =
            when {
                sharing && !hasPermission -> stringResource(MR.strings.location_permission_missing)
                !sharing && !hasPermission -> stringResource(MR.strings.location_sharing_paused_no_permission)
                sharing && !servicesEnabled -> stringResource(MR.strings.location_services_disabled)
                !sharing && !servicesEnabled -> stringResource(MR.strings.location_sharing_paused_no_location_services)
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
        const val ACTION_GEOFENCE_EVENT = "net.af0.where.ACTION_GEOFENCE_EVENT"
        const val ACTION_HEARTBEAT_TICK = "net.af0.where.ACTION_HEARTBEAT_TICK"
        const val EXTRA_FRIEND_ID = "friend_id"
        const val EXTRA_GEOFENCE_LAT = "geofence_lat"
        const val EXTRA_GEOFENCE_LNG = "geofence_lng"

        /**
         * Minimum distance in meters before a location update is considered movement
         * and broadcast to friends.
         */
        const val MOVEMENT_RADIUS_THRESHOLD_METERS = 200f

        /** Active request interval at BALANCED priority, the default. */
        const val BALANCED_INTERVAL_MS = 60_000L

        /** Active request interval at HIGH priority, after a geofence exit. */
        const val HIGH_INTERVAL_MS = 10_000L

        /**
         * How long HIGH priority lasts after the last geofence exit before dropping back to
         * BALANCED. Covers a walk across the moving-radius fence (400 m, ~5 min at walking pace)
         * plus the OS's exit latency (often 2-6 min with the screen off), so a steady walker
         * doesn't flap between HIGH and BALANCED; short next to the hours it otherwise stayed on.
         */
        const val HIGH_ACCURACY_IDLE_MS = 15 * 60 * 1000L

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
         * window, which can be hours in deep Doze. FLP callbacks and the poll timer drive the
         * finer-grained heartbeat while the foreground service is alive.
         */
        const val DOZE_ALARM_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * If no FLP callback has arrived for this long, treat the registration as silently
         * dropped and force a re-registration. Samsung's power management can revoke FLP
         * registrations without any error callback.
         */
        const val STALE_REGISTRATION_THRESHOLD_MS = 15 * 60 * 1000L

        /** Overridable in tests. */
        var clock: () -> Long = { System.currentTimeMillis() }

        /** Monotonic time, including deep sleep, for the send scheduler. Tests replace it. */
        @VisibleForTesting
        var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }

        private const val CHANNEL_ID = "where_location"
        private const val NOTIFICATION_ID = 1
    }
}
