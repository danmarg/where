package net.af0.where

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient

private const val TAG = "LocationService"

class LocationService : Service() {
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: LocationClient
    private lateinit var e2eeStore: E2eeStore

    private var lastSentLat: Double? = null
    private var lastSentLng: Double? = null
    private var lastSentTime: Long = 0

    override fun onCreate() {
        super.onCreate()
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
                    handleNewLocation(loc.latitude, loc.longitude, isHeartbeat = false)
                }
            }

        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    LocationRepository.onLocation(loc.latitude, loc.longitude)
                    handleNewLocation(loc.latitude, loc.longitude, isHeartbeat = false)
                }
            }
        } catch (_: SecurityException) {
        }

        // Heartbeat timer: ensure we send at least every 5 minutes so friends see a recent "last seen".
        serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                LocationRepository.lastLocation.value?.let { (lat, lng) ->
                    handleNewLocation(lat, lng, isHeartbeat = true)
                }
            }
        }
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
            )
            .setMinUpdateIntervalMillis(15_000L)
            .setMinUpdateDistanceMeters(10f) // OS filter: trust this for "significant movement"
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun handleNewLocation(lat: Double, lng: Double, isHeartbeat: Boolean) {
        val now = System.currentTimeMillis()
        
        // Trust the OS distance filter for movements. Just prevent rapid-fire jitter.
        val shouldSend = lastSentLat == null || 
                        (!isHeartbeat && now - lastSentTime > 60_000L) ||
                        (isHeartbeat && now - lastSentTime > 300_000L)

        if (shouldSend) {
            val sharingPrefs = getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
            val isSharing = sharingPrefs.getBoolean("is_sharing", true)
            if (!isSharing) return

            val pausedIds = sharingPrefs.getString("paused_friends", "")
                ?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

            serviceScope.launch {
                try {
                    Log.d(TAG, "Sending background location: $lat, $lng (heartbeat=$isHeartbeat)")
                    locationClient.sendLocation(lat, lng, pausedIds)
                    lastSentLat = lat
                    lastSentLng = lng
                    lastSentTime = now
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send background location", e)
                }
            }
        }
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
