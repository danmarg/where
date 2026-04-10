package net.af0.where

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    ownLocation: UserLocation?,
    users: List<UserLocation>,
    friends: List<FriendEntry>,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    pausedFriendIds: Set<String>,
    onTogglePause: (String) -> Unit,
    isSharing: Boolean,
    onToggleSharing: () -> Unit,
    connectionStatus: ConnectionStatus,
    onCreateInvite: () -> Unit,
    onScanQr: () -> Unit,
    onPasteUrl: (String) -> Unit,
    friendLastPing: Map<String, Long>,
    onRenameFriend: (String, String) -> Unit,
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
        val target =
            if (id == "__own__") {
                ownLocation?.let { LatLng(it.lat, it.lng) }
            } else {
                users.find { it.userId == id }?.let { LatLng(it.lat, it.lng) }
            }
        if (target != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, 15f))
        }
        zoomToUserId = null
    }

    Box(modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
        ) {
            ownLocation?.let { own ->
                key("__own__") {
                    MarkerComposable(
                        state = MarkerState(position = LatLng(own.lat, own.lng)),
                        anchor = Offset(0.5f, 1f),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shadowElevation = 2.dp,
                            ) {
                                Text(
                                    text = if (displayName.isNotEmpty()) displayName else "You",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = Color.Blue,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
            users.forEach { user ->
                val name = friends.find { it.id == user.userId }?.name ?: user.userId.take(8)
                key(user.userId) {
                    MarkerComposable(
                        state = MarkerState(position = LatLng(user.lat, user.lng)),
                        anchor = Offset(0.5f, 1f),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shadowElevation = 2.dp,
                            ) {
                                Text(
                                    text = name,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = Color.Red,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
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

            // Your Name chip + Connection status
            Surface(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable { zoomToUserId = "__own__" },
                shape = MaterialTheme.shapes.medium,
                color = Color.Black.copy(alpha = 0.7f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(
                                    color = if (connectionStatus is ConnectionStatus.Ok) Color.Green else Color(0xFFFFA500),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                ),
                    )
                    Column {
                        Text(
                            text = "You",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (connectionStatus is ConnectionStatus.Error) {
                            Text(
                                text = connectionStatus.message,
                                color = Color(0xFFFFA500),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
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
            friends = friends,
            displayName = displayName,
            onDisplayNameChange = onDisplayNameChange,
            pausedFriendIds = pausedFriendIds,
            friendLastPing = friendLastPing,
            onTogglePause = onTogglePause,
            onCreateInvite = onCreateInvite,
            onScanQr = onScanQr,
            onPasteUrl = onPasteUrl,
            onRename = onRenameFriend,
            onRemove = onRemoveFriend,
            onDismiss = { showFriends = false },
            onZoomTo = { zoomToUserId = it },
        )
    }
}
