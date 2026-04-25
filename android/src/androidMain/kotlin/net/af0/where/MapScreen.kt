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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.launch
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.model.UserLocation
import net.af0.where.shared.MR

@OptIn(ExperimentalPermissionsApi::class)
private val MultiplePermissionsState.anyPermissionGranted: Boolean
    get() = permissions.any { it.status.isGranted }

@OptIn(ExperimentalPermissionsApi::class)
private val MultiplePermissionsState.fineLocationGranted: Boolean
    get() = permissions.find { it.permission == android.Manifest.permission.ACCESS_FINE_LOCATION }?.status?.isGranted == true

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    ownLocation: UserLocation?,
    ownHeading: Double?,
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
    selectedUserId: String?,
    onSelectedUserIdChange: (String?) -> Unit,
    onLocationPermissionGranted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
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
        if (!locationPermissions.anyPermissionGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.anyPermissionGranted) {
        if (locationPermissions.anyPermissionGranted) {
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

    if (!locationPermissions.anyPermissionGranted) {
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

    var showFriends by remember { mutableStateOf(false) }
    var zoomToUserId by remember { mutableStateOf<String?>(null) }
    var showErrorAlert by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val initialPosition =
        remember {
            val last = UserPrefs.getLastLocation(context)
            if (last != null) {
                CameraPosition.fromLatLngZoom(LatLng(last.first, last.second), last.third)
            } else {
                CameraPosition.fromLatLngZoom(LatLng(37.33, -122.03), 10f)
            }
        }

    val cameraPositionState = rememberCameraPositionState { position = initialPosition }

    val mapLocationSource =
        remember {
            object : com.google.android.gms.maps.LocationSource {
                private var listener: com.google.android.gms.maps.LocationSource.OnLocationChangedListener? = null

                override fun activate(l: com.google.android.gms.maps.LocationSource.OnLocationChangedListener) {
                    listener = l
                }

                override fun deactivate() {
                    listener = null
                }

                fun update(
                    loc: UserLocation,
                    heading: Double?,
                ) {
                    val androidLoc =
                        android.location.Location("where").apply {
                            latitude = loc.lat
                            longitude = loc.lng
                            time = loc.timestamp * 1000
                            if (heading != null) {
                                bearing = heading.toFloat()
                            }
                            // Add a default accuracy so the native layer shows the dot clearly
                            accuracy = 10f
                        }
                    listener?.onLocationChanged(androidLoc)
                }
            }
        }

    LaunchedEffect(ownLocation, ownHeading) {
        ownLocation?.let { mapLocationSource.update(it, ownHeading) }
    }

    DisposableEffect(Unit) {
        onDispose {
            val pos = cameraPositionState.position
            UserPrefs.setLastLocation(
                context,
                pos.target.latitude,
                pos.target.longitude,
                pos.zoom,
            )
        }
    }

    LaunchedEffect(ownLocation) {
        if (ownLocation != null && UserPrefs.getLastLocation(context) == null) {
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

    val mapStyleJson =
        """
        [
          {
            "featureType": "poi",
            "elementType": "labels",
            "stylers": [
              { "visibility": "off" }
            ]
          }
        ]
        """.trimIndent()

    Box(modifier.fillMaxSize()) {
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            contentPadding = PaddingValues(bottom = 96.dp + navBarBottom),
            onMapClick = { onSelectedUserIdChange(null) },
            properties =
                MapProperties(
                    isMyLocationEnabled = locationPermissions.fineLocationGranted,
                    mapStyleOptions = com.google.android.gms.maps.model.MapStyleOptions(mapStyleJson),
                ),
            uiSettings =
                MapUiSettings(
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                    compassEnabled = false,
                ),
            locationSource = mapLocationSource,
        ) {
            val friendData = users.map { user ->
                val friend = friends.find { it.id == user.userId }
                val name = friend?.name ?: user.userId.take(8)
                Triple(user, friend, name)
            }

            friendData.forEach { (user, friend, name) ->
                val timeAgo = timeAgoStringFromMs(friendLastPing[user.userId])
                val isSelected = selectedUserId == user.userId
                key(user.userId, isSelected, name) {
                    val markerState = rememberMarkerState(key = "${user.userId}_$name", position = LatLng(user.lat, user.lng))
                    LaunchedEffect(user.lat, user.lng) {
                        markerState.position = LatLng(user.lat, user.lng)
                    }
                    MarkerComposable(
                        state = markerState,
                        anchor = Offset(0.5f, 1f),
                        onClick = {
                            scope.launch {
                                onSelectedUserIdChange(if (selectedUserId == user.userId) null else user.userId)
                            }
                            true
                        },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = Color.Black.copy(alpha = 0.65f),
                                contentColor = Color.White,
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    if (isSelected) {
                                        Text(
                                            text = timeAgo,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = Color.White.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.error,
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
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
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
                Text(
                    if (isSharing) stringResource(MR.strings.sharing) else stringResource(MR.strings.paused),
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
                            if (locationPermissions.anyPermissionGranted && !locationPermissions.fineLocationGranted) {
                                Text(
                                    text = "(" + stringResource(MR.strings.approximate) + ")",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
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
