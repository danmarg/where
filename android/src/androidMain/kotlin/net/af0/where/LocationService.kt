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
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.toHex

private const val TAG = "LocationService"

class LocationService : Service() {
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var e2eeStore: E2eeStore
    private lateinit var locationClient: LocationClient

    private var lastSentLocation: Pair<Double, Double>? = null
    private var lastSentTime: Long = 0
    private var isRapidPolling = false

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
                    LocationRepository.onLocation(loc.latitude, loc.longitude)
                }
            }

        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    LocationRepository.onLocation(loc.latitude, loc.longitude)
                }
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

    private fun maybeSendLocation(lat: Double, lng: Double) {
        val sharingPrefs = getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
        val isSharing = sharingPrefs.getBoolean("is_sharing", true)
        if (!isSharing) return

        val now = System.currentTimeMillis()
        val pausedFriendIds = sharingPrefs.getString("paused_friends", "")
            ?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        val lastLoc = lastSentLocation
        val distance = if (lastLoc != null) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(lastLoc.first, lastLoc.second, lat, lng, results)
            results[0]
        } else {
            Float.MAX_VALUE
        }

        // Send if:
        // 1. Never sent before
        // 2. Moved > 10 meters AND > 1 minute since last send
        // 3. > 5 minutes since last send (stationary heartbeat)
        val shouldSend = lastLoc == null ||
                        (distance > 10 && now - lastSentTime > 1 * 60_000L) ||
                        (now - lastSentTime > 5 * 60_000L)

        if (shouldSend) {
            serviceScope.launch {
                try {
                    Log.d(TAG, "Sending location: $lat, $lng")
                    locationClient.sendLocation(lat, lng, pausedFriendIds)
                    lastSentLocation = Pair(lat, lng)
                    lastSentTime = now
                    LocationRepository.onConnectionStatus(ConnectionStatus.Ok)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send location", e)
                    LocationRepository.onConnectionStatus(ConnectionStatus.Error(e.message ?: "unknown error"))
                }
            }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            val rapid = isRapidPolling()
            doPoll()

            // Also check for stationary heartbeat in the poll loop in case location updates stop firing
            LocationRepository.lastLocation.value?.let { (lat, lng) ->
                maybeSendLocation(lat, lng)
            }

            val interval = if (rapid) 2_000L else 60_000L
            delay(interval)
        }
    }

    private suspend fun doPoll() {
        try {
            val updates = locationClient.poll()
            for (update in updates) {
                LocationRepository.onFriendUpdate(update)
                e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, System.currentTimeMillis() / 1000L)
            }
            LocationRepository.onConnectionStatus(ConnectionStatus.Ok)
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed", e)
            LocationRepository.onConnectionStatus(ConnectionStatus.Error(e.message ?: "unknown error"))
        }
    }

    private fun isRapidPolling(): Boolean {
        return e2eeStore.pendingQrPayload != null
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
        private const val CHANNEL_ID = "where_location"
        private const val NOTIFICATION_ID = 1
    }
}
