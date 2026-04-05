package net.af0.where

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

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
    private var isRegistered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

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
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
        fusedClient.removeLocationUpdates(locationCallback)
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
