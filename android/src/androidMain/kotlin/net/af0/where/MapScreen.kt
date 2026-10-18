@file:OptIn(ExperimentalPermissionsApi::class)

package net.af0.where

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InvitePublicKey
import net.af0.where.model.UserLocation
import net.af0.where.shared.MR

private val MultiplePermissionsState.hasAnyLocationPermission: Boolean
    get() = permissions.any { it.status.isGranted }

private val MultiplePermissionsState.hasFineLocationPermission: Boolean
    get() = permissions.find { it.permission == android.Manifest.permission.ACCESS_FINE_LOCATION }?.status?.isGranted == true

private const val KEY_LOCATION_PERMISSION_REQUESTED = "location_permission_requested"
private const val KEY_BACKGROUND_LOCATION_REQUESTED = "background_location_requested"

/** The app's system settings page, where permissions Android no longer asks for can be granted. */
internal fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/**
 * What the master sharing pill shows. Without location permission or with location services off
 * nothing can be shared, whatever the toggle says. Without background location ("while in use"),
 * sharing continues only while the foreground service started from the app keeps running - a
 * restart from the background (process death, reboot) gets no location - so it's shown as limited.
 */
internal enum class SharingPill { SHARING_OFF, PAUSED, SHARING_LIMITED, SHARING }

internal fun sharingPill(
    isSharing: Boolean,
    hasLocationPermission: Boolean,
    locationServicesEnabled: Boolean,
    hasBackgroundLocationPermission: Boolean,
): SharingPill =
    when {
        !hasLocationPermission || !locationServicesEnabled -> SharingPill.SHARING_OFF
        !isSharing -> SharingPill.PAUSED
        !hasBackgroundLocationPermission -> SharingPill.SHARING_LIMITED
        else -> SharingPill.SHARING
    }

/**
 * What tapping the sharing pill does. Sharing - limited or not - it stops. Not sharing, it starts,
 * and without background location also asks for it (behind the Play-required disclosure). When
 * nothing can be shared it goes to the fix and leaves the toggle alone, so sharing resumes by
 * itself once that's done.
 */
internal enum class SharingTap { STOP, START, START_AND_ASK_BACKGROUND, REQUEST_PERMISSION, OPEN_APP_SETTINGS, OPEN_LOCATION_SETTINGS }

internal fun sharingTap(
    isSharing: Boolean,
    hasLocationPermission: Boolean,
    permissionPermanentlyDenied: Boolean,
    locationServicesEnabled: Boolean,
    hasBackgroundLocationPermission: Boolean,
): SharingTap =
    when {
        !hasLocationPermission -> if (permissionPermanentlyDenied) SharingTap.OPEN_APP_SETTINGS else SharingTap.REQUEST_PERMISSION
        !locationServicesEnabled -> SharingTap.OPEN_LOCATION_SETTINGS
        isSharing -> SharingTap.STOP
        !hasBackgroundLocationPermission -> SharingTap.START_AND_ASK_BACKGROUND
        else -> SharingTap.START
    }

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
    onCancelInvite: (InvitePublicKey) -> Unit,
    isSharing: Boolean,
    onSetSharing: (sharing: Boolean) -> Unit,
    friendExpiresAt: Map<String, Long> = emptyMap(),
    onSetFriendExpiry: (id: String, expiresAt: Long?) -> Unit = { _, _ -> },
    connectionStatus: ConnectionStatus,
    onCreateInvite: () -> Unit,
    onScanQr: () -> Unit,
    onRenameFriend: (String, String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    selectedUserId: String?,
    onSelectedUserIdChange: (String?) -> Unit,
    onLocationPermissionGranted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val permissionPrefs = LocalContext.current.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
    var permissionRequested by remember { mutableStateOf(permissionPrefs.getBoolean(KEY_LOCATION_PERMISSION_REQUESTED, false)) }
    val locationPermissions =
        rememberMultiplePermissionsState(
            listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            onPermissionsResult = {
                permissionPrefs.edit().putBoolean(KEY_LOCATION_PERMISSION_REQUESTED, true).apply()
                permissionRequested = true
            },
        )

    // Background location must be requested separately on Android 10+.
    val backgroundLocationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rememberPermissionState(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            null
        }
    val backgroundRationaleContext = LocalContext.current
    var showBackgroundRationale by remember { mutableStateOf(false) }

    // After a second denial Android silently ignores the request, so go to the app's settings.
    fun requestBackgroundLocationOrOpenSettings() {
        val permission = backgroundLocationPermission ?: return
        val requested = permissionPrefs.getBoolean(KEY_BACKGROUND_LOCATION_REQUESTED, false)
        if (requested && !permission.status.shouldShowRationale) {
            backgroundRationaleContext.openAppSettings()
        } else {
            permissionPrefs.edit().putBoolean(KEY_BACKGROUND_LOCATION_REQUESTED, true).apply()
            permission.launchPermissionRequest()
        }
    }

    LaunchedEffect(Unit) {
        if (!locationPermissions.hasAnyLocationPermission) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(locationPermissions.hasAnyLocationPermission) {
        if (locationPermissions.hasAnyLocationPermission) {
            onLocationPermissionGranted()
            // Show disclosure before requesting background location (required by Play Store).
            // Only once per install — re-showing it on every resume after the user skips it
            // is its own nag.
            if (backgroundLocationPermission != null &&
                !backgroundLocationPermission.status.isGranted &&
                !UserPrefs.hasShownBackgroundLocationRationale(backgroundRationaleContext)
            ) {
                showBackgroundRationale = true
            }
        }
    }

    if (showBackgroundRationale) {
        AlertDialog(
            onDismissRequest = {
                showBackgroundRationale = false
                UserPrefs.setBackgroundLocationRationaleShown(backgroundRationaleContext)
            },
            title = { Text(stringResource(MR.strings.background_location_title)) },
            text = {
                Text(stringResource(MR.strings.background_location_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundRationale = false
                    UserPrefs.setBackgroundLocationRationaleShown(backgroundRationaleContext)
                    requestBackgroundLocationOrOpenSettings()
                }) { Text(stringResource(MR.strings.allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackgroundRationale = false
                    UserPrefs.setBackgroundLocationRationaleShown(backgroundRationaleContext)
                }) { Text(stringResource(MR.strings.skip)) }
            },
        )
    }

    // No early return without permission: receive-only use (pairing, seeing friends, keepalives)
    // doesn't need our own location. Only our own location layer and sharing depend on it.
    val hasLocationPermission = locationPermissions.hasAnyLocationPermission
    // Asked before, still denied, and Android won't offer the dialog again (no rationale to show).
    val permanentlyDenied =
        permissionRequested && !hasLocationPermission && locationPermissions.permissions.none { it.status.shouldShowRationale }

    val context = LocalContext.current

    // LocationService.kt registers an equivalent PROVIDERS_CHANGED_ACTION receiver to refresh
    // its own notification/registration state. This one is separate because it must gate the UI
    // even when that service isn't running yet (e.g. before sharing is first turned on). Keep
    // both receivers' logic in sync if this one changes.
    var locationServicesEnabled by remember { mutableStateOf(context.hasLocationServicesEnabled()) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    locationServicesEnabled = context.hasLocationServicesEnabled()
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

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

        // Missing permission means receive-only, not a blocked app: friends and pairing keep
        // working, so this is a banner rather than a wall. Location-services-off (below) is the
        // same idea for the case where permission is held but the system toggle is off. Neither
        // may hide friend-related UI: friends' locations keep arriving even while our own GPS
        // is unavailable.
        if (!hasLocationPermission) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                color = Color(0xFF37474F).copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.location_permission_needed_to_share),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(
                        onClick = {
                            if (permanentlyDenied) {
                                // Android shows no dialog any more; Settings is the only way.
                                context.openAppSettings()
                            } else {
                                locationPermissions.launchMultiplePermissionRequest()
                            }
                        },
                    ) {
                        Text(
                            stringResource(if (permanentlyDenied) MR.strings.open_settings else MR.strings.grant_permission),
                            color = Color.White,
                        )
                    }
                }
            }
        } else if (!locationServicesEnabled) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                color = Color(0xFFB71C1C).copy(alpha = 0.9f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.location_services_required),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                        Text(stringResource(MR.strings.enable_location_services), color = Color.White)
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
            // Master sharing toggle. Per-friend timers live in FriendsSheet. Without permission
            // we're receive-only; the pill says sharing is off and leads to the fix.
            val hasBackgroundLocationPermission = backgroundLocationPermission?.status?.isGranted ?: true
            val pill =
                sharingPill(
                    isSharing = isSharing,
                    hasLocationPermission = hasLocationPermission,
                    locationServicesEnabled = locationServicesEnabled,
                    hasBackgroundLocationPermission = hasBackgroundLocationPermission,
                )
            val sharingLabel =
                when (pill) {
                    SharingPill.SHARING_OFF -> stringResource(MR.strings.sharing_off)
                    SharingPill.PAUSED -> stringResource(MR.strings.paused)
                    SharingPill.SHARING_LIMITED -> stringResource(MR.strings.sharing_limited)
                    SharingPill.SHARING -> stringResource(MR.strings.sharing)
                }

            FilledTonalButton(
                onClick = {
                    val tap =
                        sharingTap(
                            isSharing = isSharing,
                            hasLocationPermission = hasLocationPermission,
                            permissionPermanentlyDenied = permanentlyDenied,
                            locationServicesEnabled = locationServicesEnabled,
                            hasBackgroundLocationPermission = hasBackgroundLocationPermission,
                        )
                    when (tap) {
                        SharingTap.STOP -> onSetSharing(false)
                        SharingTap.START -> onSetSharing(true)
                        SharingTap.START_AND_ASK_BACKGROUND -> {
                            onSetSharing(true)
                            // The disclosure must come right before the request (Play policy).
                            showBackgroundRationale = true
                        }
                        SharingTap.REQUEST_PERMISSION -> locationPermissions.launchMultiplePermissionRequest()
                        SharingTap.OPEN_APP_SETTINGS -> context.openAppSettings()
                        SharingTap.OPEN_LOCATION_SETTINGS -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                },
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor =
                            when (pill) {
                                SharingPill.SHARING_OFF -> Color(0xFFB71C1C)
                                SharingPill.PAUSED -> Color(0xFF555555)
                                SharingPill.SHARING_LIMITED -> Color(0xFFE65100)
                                SharingPill.SHARING -> Color(0xFF1565C0)
                            },
                        contentColor = Color.White,
                    ),
            ) {
                Icon(
                    when (pill) {
                        SharingPill.SHARING, SharingPill.SHARING_LIMITED -> Icons.Default.LocationOn
                        SharingPill.SHARING_OFF, SharingPill.PAUSED -> Icons.Default.LocationOff
                    },
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
                            if (hasLocationPermission && !locationPermissions.hasFineLocationPermission) {
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
