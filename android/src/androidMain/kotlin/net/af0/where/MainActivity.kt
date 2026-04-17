package net.af0.where

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.af0.where.shared.MR
import dev.icerock.moko.resources.compose.stringResource
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.StateFlow
import net.af0.where.e2ee.InviteState
import net.af0.where.model.UserLocation

class MainActivity : ComponentActivity() {
    private val viewModel: LocationViewModel by viewModels { LocationViewModel.Factory }

    private val scanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { viewModel.processQrUrl(it) }
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.data?.toString()?.let { viewModel.processQrUrl(it) }

        setContent {
            MaterialTheme {
                val ownLocation by viewModel.ownLocation.collectAsState()
                val users by viewModel.visibleUsers.collectAsState()
                val friends by viewModel.friends.collectAsState()
                val displayName by viewModel.displayName.collectAsState()
                val pausedFriendIds by viewModel.pausedFriendIds.collectAsState()
                val friendLastPing by viewModel.friendLastPing.collectAsState()
                val isSharing by viewModel.isSharingLocation.collectAsState()
                val inviteState by viewModel.inviteState.collectAsState()
                val pendingQrForNaming by viewModel.pendingQrForNaming.collectAsState()
                val pendingInitPayload by viewModel.pendingInitPayload.collectAsState()
                val multipleScansDetected by viewModel.multipleScansDetected.collectAsState()
                val isExchanging by viewModel.isExchanging.collectAsState()
                val connectionStatus by viewModel.connectionStatus.collectAsState()

                var showSimulatorScanner by remember { mutableStateOf(false) }

                MapScreen(
                    ownLocation = ownLocation,
                    users = users,
                    friends = friends,
                    displayName = displayName,
                    onDisplayNameChange = { viewModel.setDisplayName(it) },
                    pausedFriendIds = pausedFriendIds,
                    onTogglePause = { viewModel.togglePauseFriend(it) },
                    isSharing = isSharing,
                    onToggleSharing = { viewModel.toggleSharing() },
                    connectionStatus = connectionStatus,
                    onCreateInvite = { viewModel.createInvite() },
                    onScanQr = {
                        if (android.os.Build.PRODUCT.contains("sdk") ||
                            android.os.Build.MODEL.contains("Emulator") ||
                            android.os.Build.DEVICE.contains("generic")
                        ) {
                            showSimulatorScanner = true
                        } else {
                            scanLauncher.launch(
                                ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                },
                            )
                        }
                    },
                    onPasteUrl = { viewModel.processQrUrl(it) },
                    friendLastPing = friendLastPing,
                    onRenameFriend = { id, name -> viewModel.renameFriend(id, name) },
                    onRemoveFriend = { viewModel.removeFriend(it) },
                    onLocationPermissionGranted = ::startLocationService,
                )

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
                        onDismiss = { viewModel.clearInvite() },
                    )
                }

                pendingQrForNaming?.let { qr ->
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

                pendingInitPayload?.let { payload ->
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

    override fun onResume() {
        super.onResume()
        LocationRepository.setAppForeground(true)
        LocationRepository.wakePoll()
    }

    override fun onPause() {
        super.onPause()
        LocationRepository.setAppForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { viewModel.processQrUrl(it) }
    }
}
