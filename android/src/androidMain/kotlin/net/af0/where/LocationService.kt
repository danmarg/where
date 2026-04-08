package net.af0.where

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.af0.where.e2ee.E2eeMailboxClient
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.discoveryToken
import net.af0.where.e2ee.toHex

private const val TAG = "LocationService"

/**
 * Foreground service that keeps the process alive and handles both GPS tracking
 * and the E2EE protocol work (polling, sending location).
 */
class LocationService : Service() {
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isRegistered = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var e2eeStore: E2eeStore
    private lateinit var locationClient: LocationClient

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val app = application as WhereApplication
        e2eeStore = app.e2eeStore
        locationClient = app.locationClient

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    locationSource.onLocation(loc.latitude, loc.longitude)
                }
            }

        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) locationSource.onLocation(loc.latitude, loc.longitude)
            }
        } catch (_: SecurityException) {
        }

        val request =
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
                .setMinUpdateIntervalMillis(15_000L)
                .setMinUpdateDistanceMeters(10f)
                .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isRegistered = true
        } catch (_: SecurityException) {
            stopSelf()
        }

        serviceScope.launch { pollLoop() }
        serviceScope.launch {
            locationSource.lastLocation.collect { loc ->
                if (loc != null) {
                    if (locationSource.isSharingLocation.value) {
                        sendLocationIfNeeded(loc.first, loc.second, isHeartbeat = false)
                    }
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
        if (!isRegistered) {
            val request =
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
                    .setMinUpdateIntervalMillis(15_000L)
                    .setMinUpdateDistanceMeters(10f)
                    .build()
            try {
                fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
                isRegistered = true
            } catch (_: SecurityException) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        fusedClient.removeLocationUpdates(locationCallback)
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
            // Only poll for friends' locations when the user can see the result.
            if (shouldPollFriends(rapid, inForeground)) {
                doPoll()
            }
            // Heartbeat: ensure we send at least once every 5 minutes when stationary.
            // Runs regardless of foreground state so background location stays alive.
            locationSource.lastLocation.value?.let { (lat, lng) ->
                sendLocationIfNeeded(lat, lng, isHeartbeat = true)
            }
            locationSource.awaitPollWake(pollInterval(rapid, inForeground))
        }
    }

    @VisibleForTesting
    internal fun shouldPollFriends(rapid: Boolean, inForeground: Boolean): Boolean = inForeground || rapid

    @VisibleForTesting
    internal fun pollInterval(rapid: Boolean, inForeground: Boolean): Long = when {
        rapid -> 2_000L
        inForeground -> 10_000L
        else -> 5 * 60 * 1000L
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

    private suspend fun doPoll() {
        try {
            Log.d(TAG, "Polling for location updates")
            val updates = locationClient.poll()
            Log.d(TAG, "Got ${updates.size} location updates")
            withContext(Dispatchers.Main) {
                val now = System.currentTimeMillis()
                for (update in updates) {
                    locationSource.onFriendUpdate(update, now)
                    e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, now / 1000L)
                }
                pollPendingInvite()
                locationSource.onFriendsUpdated(e2eeStore.listFriends())
                updateStatus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    private suspend fun pollPendingInvite() {
        if (locationSource.pendingInitPayload.value != null) return
        val qr = e2eeStore.pendingQrPayload() ?: return
        try {
            val discoveryHex = qr.discoveryToken().toHex()
            Log.d(TAG, "pollPendingInvite: polling discoveryHex=$discoveryHex")
            val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, discoveryHex)
            updateStatus(null)
            val initPayload = messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull() ?: return

            Log.d(TAG, "pollPendingInvite: received KeyExchangeInit from ${initPayload.suggestedName}")
            withContext(Dispatchers.Main) {
                locationSource.onPendingInit(initPayload)
            }
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
                        (!isHeartbeat && now - lastSentTime > 15_000L) ||
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
        } catch (e: Exception) {
            // Restore lastSentTime on failure so the next update can retry immediately.
            sendLock.withLock {
                lastSentTime = 0L
            }
            Log.e(TAG, "Failed to send location: ${e.message}")
            updateStatus(e)
        }
    }

    private fun updateStatus(e: Throwable?) {
        if (e == null) {
            locationSource.onConnectionStatus(ConnectionStatus.Ok)
        } else {
            locationSource.onConnectionError(e)
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Where Location",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Where")
            .setContentText("Sharing your location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

    companion object {
        /** Overridable in tests; defaults to the production singleton. */
        var locationSource: LocationSource = LocationRepository

        /** Overridable in tests. */
        var clock: () -> Long = { System.currentTimeMillis() }

        private const val CHANNEL_ID = "where_location"
        private const val NOTIFICATION_ID = 1
    }
}
