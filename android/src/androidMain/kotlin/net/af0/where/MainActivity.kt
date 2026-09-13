package net.af0.where

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.e2ee.InviteState
import net.af0.where.shared.MR

class MainActivity : ComponentActivity() {
    private val viewModel: LocationViewModel by viewModels { LocationViewModel.Factory }

    private val shareReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context,
                intent: android.content.Intent,
            ) {
                val ekPub = intent.getByteArrayExtra("ekPub") ?: return
                viewModel.markInviteExported(ekPub)
            }
        }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("net.af0.where.ACTION_INVITE_EXPORTED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shareReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(shareReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(shareReceiver)
    }

    private val scanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { viewModel.processQrUrl(it) }
        }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchScanner()
            }
        }

    // No rationale needed here per Android guidance: POST_NOTIFICATIONS is low-friction
    // (it only gates the persistent foreground-service notification, not any functionality),
    // so we just ask once directly.
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setBeepEnabled(false)
                setOrientationLocked(false)
            },
        )
    }

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
        // Without this, the persistent notification that's supposed to warn the user when
        // sharing is failing or paused (see LocationService.ensureLocationRegistration) is
        // simply invisible on API 33+ until the user happens to grant it some other way.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app UI has no dark theme variant (Theme.Where is always light), so pin the
        // system bar icons to dark-on-light rather than letting enableEdgeToEdge() follow
        // the device's dark mode setting, which would make them invisible against our
        // light backgrounds.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        intent?.data?.toString()?.let { viewModel.processQrUrl(it) }

        setContent {
            MaterialTheme {
                val ownLocation by viewModel.ownLocation.collectAsStateWithLifecycle()
                val ownHeading by viewModel.ownHeading.collectAsStateWithLifecycle()
                val users by viewModel.visibleUsers.collectAsStateWithLifecycle()
                val friends by viewModel.friends.collectAsStateWithLifecycle()
                val pendingInvites by viewModel.allPendingInvites.collectAsStateWithLifecycle()
                val displayName by viewModel.displayName.collectAsStateWithLifecycle()
                val pausedFriendIds by viewModel.pausedFriendIds.collectAsStateWithLifecycle()
                val friendLastPing by viewModel.friendLastPing.collectAsStateWithLifecycle()
                val isSharing by viewModel.isSharingLocation.collectAsStateWithLifecycle()
                val friendExpiresAt by viewModel.friendExpiresAt.collectAsStateWithLifecycle()
                val inviteState by viewModel.inviteState.collectAsStateWithLifecycle()
                val isInviteActive = inviteState is InviteState.Pending
                androidx.compose.runtime.LaunchedEffect(isInviteActive) {
                    viewModel.setInviteSheetShowing(isInviteActive)
                }
                val pendingQrForNaming by viewModel.pendingQrForNaming.collectAsStateWithLifecycle()
                val pendingInitPayload by viewModel.pendingInitPayload.collectAsStateWithLifecycle()
                val multipleScansDetected by viewModel.multipleScansDetected.collectAsStateWithLifecycle()
                val isExchanging by viewModel.isExchanging.collectAsStateWithLifecycle()
                val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
                val diagnosticLog by viewModel.diagnosticLog.collectAsStateWithLifecycle()
                val isInviteSheetShowing by viewModel.isInviteSheetShowing.collectAsStateWithLifecycle()

                var showSimulatorScanner by remember { mutableStateOf(false) }
                var showCameraRationale by remember { mutableStateOf(false) }
                var showCameraSettingsRationale by remember { mutableStateOf(false) }
                var selectedUserId by remember { mutableStateOf<String?>(null) }

                MapScreen(
                    ownLocation = ownLocation,
                    ownHeading = ownHeading,
                    users = users,
                    friends = friends,
                    diagnosticLog = diagnosticLog,
                    pendingInvites = pendingInvites,
                    displayName = displayName,
                    onDisplayNameChange = { viewModel.setDisplayName(it) },
                    pausedFriendIds = pausedFriendIds,
                    onTogglePause = { viewModel.togglePauseFriend(it) },
                    onCancelInvite = { viewModel.cancelPendingInvite(it) },
                    isSharing = isSharing,
                    onSetSharing = { sharing -> viewModel.setSharing(sharing) },
                    friendExpiresAt = friendExpiresAt,
                    onSetFriendExpiry = { id, exp -> viewModel.setFriendExpiry(id, exp) },
                    connectionStatus = connectionStatus,
                    onCreateInvite = { viewModel.createInvite() },
                    onScanQr = {
                        if (android.os.Build.PRODUCT.contains("sdk") ||
                            android.os.Build.MODEL.contains("Emulator") ||
                            android.os.Build.DEVICE.contains("generic")
                        ) {
                            showSimulatorScanner = true
                        } else {
                            val permission = Manifest.permission.CAMERA
                            val hasAskedBefore = UserPrefs.hasRequestedCamera(this)
                            when {
                                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                                    launchScanner()
                                }
                                ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                                    showCameraRationale = true
                                }
                                hasAskedBefore -> {
                                    // If we've asked before, but shouldShowRequestPermissionRationale is false,
                                    // it means the user has permanently denied the permission.
                                    showCameraSettingsRationale = true
                                }
                                else -> {
                                    UserPrefs.setCameraRequested(this)
                                    requestCameraPermissionLauncher.launch(permission)
                                }
                            }
                        }
                    },
                    onPasteUrl = { viewModel.processQrUrl(it) },
                    friendLastPing = friendLastPing,
                    onRenameFriend = { id, name -> viewModel.renameFriend(id, name) },
                    onRemoveFriend = { viewModel.removeFriend(it) },
                    selectedUserId = selectedUserId,
                    onSelectedUserIdChange = { selectedUserId = it },
                    onLocationPermissionGranted = ::startLocationService,
                )

                if (showCameraRationale) {
                    AlertDialog(
                        onDismissRequest = { showCameraRationale = false },
                        title = { Text(stringResource(MR.strings.scan)) },
                        text = { Text(stringResource(MR.strings.camera_permission_rationale)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showCameraRationale = false
                                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }) { Text(stringResource(MR.strings.ok)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCameraRationale = false }) {
                                Text(stringResource(MR.strings.cancel))
                            }
                        },
                    )
                }

                if (showCameraSettingsRationale) {
                    AlertDialog(
                        onDismissRequest = { showCameraSettingsRationale = false },
                        title = { Text(stringResource(MR.strings.scan)) },
                        text = { Text(stringResource(MR.strings.camera_permission_settings_rationale)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showCameraSettingsRationale = false
                                val intent =
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.fromParts("package", packageName, null)
                                    }
                                startActivity(intent)
                            }) { Text(stringResource(MR.strings.open_settings)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCameraSettingsRationale = false }) {
                                Text(stringResource(MR.strings.cancel))
                            }
                        },
                    )
                }

                val showBatteryOptimizationDialog by viewModel.showBatteryOptimizationDialog.collectAsStateWithLifecycle()
                if (showBatteryOptimizationDialog) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissBatteryOptimizationDialog() },
                        title = { Text("Improve background updates") },
                        text = {
                            Text(
                                "For reliable location sharing when the screen is off, allow Where to run without battery restrictions.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.dismissBatteryOptimizationDialog()
                                startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$packageName")
                                    },
                                )
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissBatteryOptimizationDialog() }) {
                                Text("Skip")
                            }
                        },
                    )
                }

                if (isExchanging) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                if (showSimulatorScanner) {
                    var manualUrl by remember { mutableStateOf("https://where.af0.net/invite#...") }
                    AlertDialog(
                        onDismissRequest = { showSimulatorScanner = false },
                        title = { Text(stringResource(MR.strings.qr_scanner_simulator)) },
                        text = {
                            Column {
                                Text(stringResource(MR.strings.camera_unavailable_emulator))
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = manualUrl,
                                    onValueChange = { manualUrl = it },
                                    label = { Text(stringResource(MR.strings.invite_url)) },
                                    singleLine = true,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.processQrUrl(manualUrl)
                                showSimulatorScanner = false
                            }) {
                                Text(stringResource(MR.strings.simulate_scan))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSimulatorScanner = false }) {
                                Text(stringResource(MR.strings.cancel))
                            }
                        },
                    )
                }

                (inviteState as? InviteState.Pending)?.let { state ->
                    InviteSheet(
                        qrPayload = state.qr,
                        displayName = displayName,
                        onDisplayNameChange = { viewModel.setDisplayName(it) },
                        onDismiss = { viewModel.clearInviteIfNotExported() },
                        onExportedIntent = { ekPub ->
                            val intent =
                                Intent("net.af0.where.ACTION_INVITE_EXPORTED").apply {
                                    setPackage(packageName)
                                    putExtra("ekPub", ekPub)
                                }
                            android.app.PendingIntent.getBroadcast(
                                this@MainActivity,
                                0,
                                intent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                            )
                        },
                    )
                }

                pendingQrForNaming?.let { qr ->
                    if (!isInviteSheetShowing) {
                        var name by remember(qr) { mutableStateOf(qr.suggestedName) }
                        AlertDialog(
                            onDismissRequest = { viewModel.cancelQrScan() },
                            title = { Text(stringResource(MR.strings.name_this_contact)) },
                            text = {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text(stringResource(MR.strings.friend_name_label)) },
                                    singleLine = true,
                                )
                            },
                            confirmButton = {
                                val friendDefault = stringResource(MR.strings.friend)
                                TextButton(onClick = { viewModel.confirmQrScan(qr, name.ifEmpty { friendDefault }) }) {
                                    Text(stringResource(MR.strings.add))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.cancelQrScan() }) {
                                    Text(stringResource(MR.strings.cancel))
                                }
                            },
                        )
                    }
                }

                pendingInitPayload?.let { payload ->
                    if (!isInviteSheetShowing) {
                        var name by remember(payload) { mutableStateOf(payload.suggestedName) }
                        AlertDialog(
                            onDismissRequest = { viewModel.cancelPendingInit() },
                            title = { Text(stringResource(MR.strings.name_this_contact)) },
                            text = {
                                Column {
                                    Text(stringResource(MR.strings.new_friend_scanned_qr))
                                    if (multipleScansDetected) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            stringResource(MR.strings.multiple_scans_detected_warning),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = { Text(stringResource(MR.strings.friend_name_label)) },
                                        singleLine = true,
                                    )
                                }
                            },
                            confirmButton = {
                                val friendDefault = stringResource(MR.strings.friend)
                                TextButton(onClick = { viewModel.confirmPendingInit(name.ifEmpty { friendDefault }) }) {
                                    Text(stringResource(MR.strings.save))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.cancelPendingInit() }) {
                                    Text(stringResource(MR.strings.cancel))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as WhereApplication).locationSource.setAppForeground(true)
        (application as WhereApplication).locationSource.wakePoll()
    }

    override fun onPause() {
        super.onPause()
        (application as WhereApplication).locationSource.setAppForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { viewModel.processQrUrl(it) }
    }
}
