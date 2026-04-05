package net.af0.where

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.toHex
import net.af0.where.e2ee.discoveryToken

private const val TAG = "LocationService"

private const val TAG = "LocationService"

/**
 * Foreground service that keeps the process alive and emits GPS coordinates into
 * LocationRepository. All E2EE protocol work (polling, sending location over the
 * encrypted channel) is handled by LocationViewModel, which continues running while
 * this service keeps the process alive.
 */
class LocationService : Service() {
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var e2eeStore: E2eeStore
    private lateinit var locationClient: LocationClient

    private var lastSentLocation: Pair<Double, Double>? = null
    private var lastSentTime: Long = 0
    private val mutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LocationService onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        e2eeStore = E2eeStore(SharedPrefsE2eeStorage(this))
        locationClient = LocationClient(BuildConfig.SERVER_HTTP_URL, e2eeStore)

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

        serviceScope.launch {
            pollLoop()
        }

        serviceScope.launch {
            LocationRepository.lastLocation.collectLatest { loc ->
                if (loc != null) {
                    maybeSendLocation(loc.first, loc.second)
                }
            }
        }
    }

    private suspend fun maybeSendLocation(lat: Double, lng: Double) {
        val isSharing = LocationRepository.isSharingLocation.value
        if (!isSharing) return

        mutex.withLock {
            val now = System.currentTimeMillis()
            val pausedFriendIds = LocationRepository.pausedFriendIds.value

            val lastLoc = lastSentLocation
            val distance = if (lastLoc != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(lastLoc.first, lastLoc.second, lat, lng, results)
                results[0]
            } else {
                Float.MAX_VALUE
            }

            // Thresholds: 1 min if moved > 10m, 5 min heartbeat
            val shouldSend = lastLoc == null ||
                            (distance > 10 && now - lastSentTime > 1 * 60_000L) ||
                            (now - lastSentTime > 5 * 60_000L)

            if (shouldSend) {
                try {
                    // Always poll before sending, even in background.
                    // It updates e2eeStore but only updates UI flows if appropriate.
                    doPollInternal()

                    Log.d(TAG, "Sending location: $lat, $lng")
                    locationClient.sendLocation(lat, lng, pausedFriendIds)
                    lastSentLocation = Pair(lat, lng)
                    lastSentTime = now
                    LocationRepository.onConnectionStatus(ConnectionStatus.Ok)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send location", e)
                    LocationRepository.onConnectionError(e)
                }
            }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            val isForeground = LocationRepository.isAppInForeground.value
            val rapid = isRapidPolling()

            if (isForeground || rapid) {
                doPollInternal()
            }

            LocationRepository.lastLocation.value?.let { (lat, lng) ->
                maybeSendLocation(lat, lng)
            }

            val interval = if (rapid) 2_000L else 60_000L
            delay(interval)
        }
    }

    private suspend fun doPollInternal() {
        try {
            val updates = locationClient.poll()
            val isForeground = LocationRepository.isAppInForeground.value
            val rapid = isRapidPolling()

            for (update in updates) {
                e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, System.currentTimeMillis() / 1000L)
                if (isForeground || rapid) {
                    LocationRepository.onFriendUpdate(update)
                }
            }

            if (isForeground || rapid) {
                pollPendingInviteInternal()
            }
            LocationRepository.onConnectionStatus(ConnectionStatus.Ok)
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed", e)
            LocationRepository.onConnectionError(e)
        }
    }

    private suspend fun pollPendingInviteInternal() {
        val qr = e2eeStore.pendingQrPayload ?: run {
            LocationRepository.onPendingInit(null)
            return
        }
        try {
            val discoveryHex = qr.discoveryToken().toHex()
            val messages = net.af0.where.e2ee.E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, discoveryHex)
            val initPayload = messages.filterIsInstance<net.af0.where.e2ee.KeyExchangeInitPayload>().firstOrNull()
            if (initPayload != null) {
                LocationRepository.onPendingInit(initPayload)
            }
        } catch (e: Exception) {
            LocationRepository.onConnectionError(e)
        }
    }

    private fun isRapidPolling(): Boolean {
        val isPairing = e2eeStore.pendingQrPayload != null || LocationRepository.pendingInitPayload.value != null
        return isPairing
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val request =
            LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                30_000L,
            ).setMinUpdateIntervalMillis(15_000L).build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

        private const val CHANNEL_ID = "where_location"
        private const val NOTIFICATION_ID = 1
    }
}
