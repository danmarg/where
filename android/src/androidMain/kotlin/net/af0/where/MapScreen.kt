package net.af0.where

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    userId: String,
    users: List<UserLocation>,
    friends: List<FriendEntry>,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    pausedFriendIds: Set<String>,
    onTogglePause: (String) -> Unit,
    isSharing: Boolean,
    onToggleSharing: () -> Unit,
    onCreateInvite: () -> Unit,
    onScanQr: () -> Unit,
    onRemoveFriend: (String) -> Unit,
    onLocationPermissionGranted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val locationPermissions =
        rememberMultiplePermissionsState(
            listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )

    // Background location must be requested separately on Android 10+.
    val backgroundLocationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rememberPermissionState(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            null
        }
    var showBackgroundRationale by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) {
            onLocationPermissionGranted()
            // Show disclosure before requesting background location (required by Play Store).
            if (backgroundLocationPermission != null && !backgroundLocationPermission.status.isGranted) {
                showBackgroundRationale = true
            }
        }
    }

    if (showBackgroundRationale) {
        AlertDialog(
            onDismissRequest = { showBackgroundRationale = false },
            title = { Text("Background Location") },
            text = {
                Text(
                    "Allow Where to access your location in the background so friends can " +
                        "see your position even when the app is not open.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundRationale = false
                    backgroundLocationPermission?.launchPermissionRequest()
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundRationale = false }) { Text("Skip") }
            },
        )
    }

    if (!locationPermissions.allPermissionsGranted) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Location permission is required")
                Button(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                    Text("Grant Permission")
                }
            }
        }
        return
    }

    var showFriends by remember { mutableStateOf(false) }
    var zoomToUserId by remember { mutableStateOf<String?>(null) }

    val defaultPosition = remember { CameraPosition.fromLatLngZoom(LatLng(37.33, -122.03), 10f) }
    val cameraPositionState = rememberCameraPositionState { position = defaultPosition }

    val ownLocation = users.find { it.userId == userId }
    LaunchedEffect(ownLocation) {
        if (ownLocation != null && cameraPositionState.position == defaultPosition) {
            cameraPositionState.position =
                CameraPosition.fromLatLngZoom(
                    LatLng(ownLocation.lat, ownLocation.lng), 14f,
                )
        }
    }

    LaunchedEffect(zoomToUserId) {
        val id = zoomToUserId ?: return@LaunchedEffect
        val user = users.find { it.userId == id }
        if (user != null) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(user.lat, user.lng), 15f),
            )
        }
        zoomToUserId = null
    }

    Box(modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
        ) {
            users.forEach { user ->
                val isMe = user.userId == userId
                Marker(
                    state = MarkerState(position = LatLng(user.lat, user.lng)),
                    title = if (isMe) "You" else friends.find { it.id == user.userId }?.name ?: user.userId.take(8),
                    icon =
                        BitmapDescriptorFactory.defaultMarker(
                            if (isMe) {
                                BitmapDescriptorFactory.HUE_AZURE
                            } else {
                                BitmapDescriptorFactory.HUE_RED
                            },
                        ),
                )
            }
        }

        // Bottom controls row
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pause / resume sharing
            FilledTonalButton(
                onClick = onToggleSharing,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isSharing) Color(0xFF1565C0) else Color(0xFF555555),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(
                    if (isSharing) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isSharing) "Sharing" else "Paused", style = MaterialTheme.typography.labelMedium)
            }

            // Your ID chip
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = Color.Black.copy(alpha = 0.7f),
            ) {
                Text(
                    text = "You: ${userId.take(8)}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // Friends button
            FilledTonalButton(onClick = { showFriends = true }) {
                Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("${friends.size}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showFriends) {
        FriendsSheet(
            userId = userId,
            friends = friends,
            displayName = displayName,
            onDisplayNameChange = onDisplayNameChange,
            pausedFriendIds = pausedFriendIds,
            onTogglePause = onTogglePause,
            onCreateInvite = onCreateInvite,
            onScanQr = onScanQr,
            onRemove = onRemoveFriend,
            onDismiss = { showFriends = false },
            onZoomTo = { zoomToUserId = it },
        )
    }
}
