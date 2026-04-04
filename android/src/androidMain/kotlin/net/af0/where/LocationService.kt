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

    @Volatile
    private var lastSentLat: Double? = null
    @Volatile
    private var lastSentLng: Double? = null
    @Volatile
    private var lastSentTime: Long = System.currentTimeMillis()
    private val pendingFriendIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var isRegistered = false

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

        val request =
            LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                30_000L,
            )
                .setMinUpdateIntervalMillis(15_000L)
                .setMinUpdateDistanceMeters(10f)
                .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isRegistered = true
        } catch (_: SecurityException) {
            stopSelf()
        }

        // Heartbeat timer: ensure we send and poll at least every 5 minutes.
        // Polling is required even in background to stay in sync with E2EE protocol messages
        // (RatchetAcks, EpochRotations) from friends and to persist friend location updates.
        serviceScope.launch {
            while (isActive) {
                try {
                    Log.d(TAG, "Periodic background protocol sync (poll)")
                    val updates = locationClient.poll()
                    // Persist poll results so ViewModel sees them when it polls next
                    val now = System.currentTimeMillis()
                    for (update in updates) {
                        e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, now / 1000L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background poll failed", e)
                }

                LocationRepository.lastLocation.value?.let { (lat, lng) ->
                    handleNewLocation(lat, lng, isHeartbeat = true)
                }
                delay(300_000L) // 5 minutes
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_FORCE_PUBLISH) {
            val friendId = intent.getStringExtra(EXTRA_FRIEND_ID)
            LocationRepository.lastLocation.value?.let { (lat, lng) ->
                if (friendId != null) {
                    // Send specifically to this friend, but also update lastSent state
                    // to prevent an immediate redundant publish to everyone from heartbeats.
                    serviceScope.launch {
                        try {
                            Log.d(TAG, "Forced publish to friend: $friendId")
                            locationClient.sendLocationToFriend(friendId, lat, lng)
                            lastSentLat = lat
                            lastSentLng = lng
                            lastSentTime = System.currentTimeMillis()
                        } catch (e: Exception) {
                            Log.e(TAG, "Forced publish to friend failed", e)
                        }
                    }
                } else {
                    Log.d(TAG, "Forced publish to all")
                    handleNewLocation(lat, lng, isHeartbeat = false, force = true)
                }
            } ?: run {
                if (friendId != null) {
                    pendingFriendIds.add(friendId)
                }
            }
        }

        if (!isRegistered) {
            val request =
                LocationRequest.Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    30_000L,
                )
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

    private fun handleNewLocation(
        lat: Double,
        lng: Double,
        isHeartbeat: Boolean,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()

        val shouldSend =
            force || lastSentLat == null ||
                (!isHeartbeat && now - lastSentTime > 15_000L) ||
                (isHeartbeat && now - lastSentTime > 300_000L)

        if (shouldSend) {
            serviceScope.launch {
                val sharingPrefs = getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
                val isSharing = sharingPrefs.getBoolean("is_sharing", true)
                if (!isSharing) return@launch

                val pausedIds =
                    sharingPrefs.getString("paused_friends", "")
                        ?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

                try {
                    Log.d(TAG, "Sending background location: $lat, $lng (heartbeat=$isHeartbeat)")

                    // Drain queued single-friend forced publishes (don't broadcast to all when draining queue)
                    val hadPendingFriends = pendingFriendIds.isNotEmpty()
                    while (pendingFriendIds.isNotEmpty()) {
                        val id = pendingFriendIds.poll() ?: break
                        Log.d(TAG, "Processing queued forced publish to: $id")
                        locationClient.sendLocationToFriend(id, lat, lng)
                    }

                    // Only broadcast to all friends if not just handling pending single-friend sends
                    if (!hadPendingFriends || force) {
                        locationClient.sendLocation(lat, lng, pausedIds)
                    }
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

        const val ACTION_FORCE_PUBLISH = "net.af0.where.ACTION_FORCE_PUBLISH"
        const val EXTRA_FRIEND_ID = "friend_id"
    }
}
