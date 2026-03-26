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
                val friendIds by viewModel.friendIds.collectAsState()
                val isSharing by viewModel.isSharingLocation.collectAsState()
                val pendingInviteQr by viewModel.pendingInviteQr.collectAsState()

                MapScreen(
                    userId = viewModel.userId,
                    users = users,
                    friendIds = friendIds,
                    isSharing = isSharing,
                    onToggleSharing = { viewModel.toggleSharing() },
                    onCreateInvite = { viewModel.createInvite() },
                    onScanQr = {
                        scanLauncher.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            },
                        )
                    },
                    onRemoveFriend = { viewModel.removeFriend(it) },
                    onLocationPermissionGranted = ::startLocationService,
                )

                pendingInviteQr?.let { qr ->
                    InviteSheet(qrPayload = qr, onDismiss = { viewModel.clearInvite() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { viewModel.processQrUrl(it) }
    }
}
