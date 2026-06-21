@file:OptIn(ExperimentalPermissionsApi::class)

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation
import net.af0.where.shared.MR

private val MultiplePermissionsState.hasAnyLocationPermission: Boolean
    get() = permissions.any { it.status.isGranted }

private val MultiplePermissionsState.hasFineLocationPermission: Boolean
    get() = permissions.find { it.permission == android.Manifest.permission.ACCESS_FINE_LOCATION }?.status?.isGranted == true

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    ownLocation: UserLocation?,
    ownHeading: Double?,
    users: List<UserLocation>,
    friends: List<FriendEntry>,
    diagnosticLog: List<String> = emptyList(),
    pendingInvites: List<net.af0.where.e2ee.PendingInviteView>,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    pausedFriendIds: Set<String>,
    friendLastPing: Map<String, Long>,
    onTogglePause: (String) -> Unit,
    onCancelInvite: (ByteArray) -> Unit,
    isSharing: Boolean,
    onSetSharing: (sharing: Boolean) -> Unit,
    friendExpiresAt: Map<String, Long> = emptyMap(),
    onSetFriendExpiry: (id: String, expiresAt: Long?) -> Unit = { _, _ -> },
    connectionStatus: ConnectionStatus,
    onCreateInvite: () -> Unit,
    onScanQr: () -> Unit,
    onPasteUrl: (String) -> Unit,
    onRenameFriend: (String, String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    selectedUserId: String?,
    onSelectedUserIdChange: (String?) -> Unit,
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
        if (!locationPermissions.hasAnyLocationPermission) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.hasAnyLocationPermission) {
        if (locationPermissions.hasAnyLocationPermission) {
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
            title = { Text(stringResource(MR.strings.background_location_title)) },
            text = {
                Text(stringResource(MR.strings.background_location_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundRationale = false
                    backgroundLocationPermission?.launchPermissionRequest()
                }) { Text(stringResource(MR.strings.allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundRationale = false }) { Text(stringResource(MR.strings.skip)) }
            },
        )
    }

    if (!locationPermissions.hasAnyLocationPermission) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(MR.strings.location_permission_required))
                Button(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                    Text(stringResource(MR.strings.grant_permission))
                }
            }
        }
        return
    }

    val context = LocalContext.current
    var showFriends by remember { mutableStateOf(false) }
    var zoomToUserId by remember { mutableStateOf<String?>(null) }
    var showErrorAlert by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        MapComposable(
            ownLocation = ownLocation,
            ownHeading = ownHeading,
            users = users,
            friends = friends,
            friendLastPing = friendLastPing,
            selectedUserId = selectedUserId,
            hasFineLocationPermission = locationPermissions.hasFineLocationPermission,
            zoomToUserId = zoomToUserId,
            onZoomToUserIdConsumed = { zoomToUserId = null },
            onSelectedUserIdChange = onSelectedUserIdChange,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom controls row
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Master sharing toggle. Per-friend timers live in FriendsSheet.
            val sharingLabel = if (isSharing) stringResource(MR.strings.sharing) else stringResource(MR.strings.paused)

            FilledTonalButton(
                onClick = { onSetSharing(!isSharing) },
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
                Text(
                    sharingLabel,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // Your Name chip + Connection status
            Surface(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable {
                            if (connectionStatus is ConnectionStatus.Error) {
                                showErrorAlert = true
                            } else {
                                zoomToUserId = "__own__"
                            }
                        },
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(MR.strings.you),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (locationPermissions.hasAnyLocationPermission && !locationPermissions.hasFineLocationPermission) {
                                Text(
                                    text = "(" + stringResource(MR.strings.approximate) + ")",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8f,
                                        ),
                                )
                            }
                        }
                        if (connectionStatus is ConnectionStatus.Error) {
                            Text(
                                text = connectionStatus.message.toString(context),
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
            diagnosticLog = diagnosticLog,
            pendingInvites = pendingInvites,
            displayName = displayName,
            onDisplayNameChange = onDisplayNameChange,
            pausedFriendIds = pausedFriendIds,
            friendLastPing = friendLastPing,
            friendExpiresAt = friendExpiresAt,
            onSetFriendExpiry = onSetFriendExpiry,
            onTogglePause = onTogglePause,
            onCancelInvite = onCancelInvite,
            onCreateInvite = onCreateInvite,
            onScanQr = onScanQr,
            onPasteUrl = onPasteUrl,
            onRename = onRenameFriend,
            onRemove = onRemoveFriend,
            onDismiss = { showFriends = false },
            onZoomTo = {
                zoomToUserId = it
                onSelectedUserIdChange(it)
            },
        )
    }

    if (showErrorAlert && connectionStatus is ConnectionStatus.Error) {
        AlertDialog(
            onDismissRequest = { showErrorAlert = false },
            title = { Text(stringResource(MR.strings.connection_error)) },
            text = { Text(connectionStatus.message.toString(context)) },
            confirmButton = {
                TextButton(onClick = { showErrorAlert = false }) { Text(stringResource(MR.strings.ok)) }
            },
        )
    }
}
