package net.af0.where

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: LocationViewModel by viewModels()

    fun startLocationService() {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            startForegroundService(Intent(this, LocationService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val users by viewModel.visibleUsers.collectAsState()
                val friendIds by viewModel.friendIds.collectAsState()
                val isSharing by viewModel.isSharingLocation.collectAsState()
                MapScreen(
                    userId = viewModel.userId,
                    users = users,
                    friendIds = friendIds,
                    isSharing = isSharing,
                    onToggleSharing = { viewModel.toggleSharing() },
                    onAddFriend = { viewModel.addFriend(it) },
                    onRemoveFriend = { viewModel.removeFriend(it) },
                    onLocationPermissionGranted = ::startLocationService,
                )
            }
        }
    }
}
