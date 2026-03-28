package net.af0.where

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val viewModel: LocationViewModel by viewModels()

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
        intent?.data?.toString()?.let { viewModel.processQrUrl(it) }

        setContent {
            MaterialTheme {
                val users by viewModel.visibleUsers.collectAsState()
                val friends by viewModel.friends.collectAsState()
                val displayName by viewModel.displayName.collectAsState()
                val pausedFriendIds by viewModel.pausedFriendIds.collectAsState()
                val isSharing by viewModel.isSharingLocation.collectAsState()
                val pendingInviteQr by viewModel.pendingInviteQr.collectAsState()
                val pendingQrForNaming by viewModel.pendingQrForNaming.collectAsState()
                val pendingInitPayload by viewModel.pendingInitPayload.collectAsState()

                var showSimulatorScanner by remember { mutableStateOf(false) }

                MapScreen(
                    userId = viewModel.userId,
                    users = users,
                    friends = friends,
                    displayName = displayName,
                    onDisplayNameChange = { viewModel.setDisplayName(it) },
                    pausedFriendIds = pausedFriendIds,
                    onTogglePause = { viewModel.togglePauseFriend(it) },
                    isSharing = isSharing,
                    onToggleSharing = { viewModel.toggleSharing() },
                    onCreateInvite = { viewModel.createInvite() },
                    onScanQr = {
                        if (android.os.Build.PRODUCT.contains("sdk") || 
                            android.os.Build.MODEL.contains("Emulator") || 
                            android.os.Build.DEVICE.contains("generic")) {
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
                    onRemoveFriend = { viewModel.removeFriend(it) },
                    onLocationPermissionGranted = ::startLocationService,
                )

                if (showSimulatorScanner) {
                    var manualUrl by remember { mutableStateOf("where://invite?q=...") }
                    AlertDialog(
                        onDismissRequest = { showSimulatorScanner = false },
                        title = { Text("QR Scanner (Simulator)") },
                        text = {
                            Column {
                                Text("Camera is unavailable in the emulator. Enter an invite URL manually.")
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = manualUrl,
                                    onValueChange = { manualUrl = it },
                                    label = { Text("Invite URL") },
                                    singleLine = true,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.processQrUrl(manualUrl)
                                showSimulatorScanner = false
                            }) {
                                Text("Simulate Scan")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSimulatorScanner = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                pendingInviteQr?.let { qr ->
                    InviteSheet(qrPayload = qr, onDismiss = { viewModel.clearInvite() })
                }

                pendingQrForNaming?.let { qr ->
                    var name by remember(qr) { mutableStateOf(qr.suggestedName) }
                    AlertDialog(
                        onDismissRequest = { viewModel.cancelQrScan() },
                        title = { Text("Name this contact") },
                        text = {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Friend's Name") },
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmQrScan(qr, name.ifEmpty { "Friend" }) }) {
                                Text("Add")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.cancelQrScan() }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                pendingInitPayload?.let { _ ->
                    var name by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { viewModel.cancelPendingInit() },
                        title = { Text("Name this contact") },
                        text = {
                            Column {
                                Text("A new friend has scanned your QR code.")
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("Friend's Name") },
                                    singleLine = true,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmPendingInit(name.ifEmpty { "Friend" }) }) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.cancelPendingInit() }) {
                                Text("Skip")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { viewModel.processQrUrl(it) }
    }
}
