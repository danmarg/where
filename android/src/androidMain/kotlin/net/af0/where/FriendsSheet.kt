package net.af0.where

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.FriendEntry

fun timeAgoString(lastPingMs: Long?): String {
    if (lastPingMs == null) return "never"
    val seconds = (System.currentTimeMillis() - lastPingMs) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsSheet(
    friends: List<FriendEntry>,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    pausedFriendIds: Set<String>,
    friendLastPing: Map<String, Long>,
    onTogglePause: (String) -> Unit,
    onCreateInvite: () -> Unit,
    onScanQr: () -> Unit,
    onPasteUrl: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
    onZoomTo: (String) -> Unit = {},
) {
    var confirmDeleteFriend by remember { mutableStateOf<FriendEntry?>(null) }
    var renameFriend by remember { mutableStateOf<FriendEntry?>(null) }
    var showPasteField by remember { mutableStateOf(false) }
    var pastedUrl by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Friends", style = MaterialTheme.typography.titleLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onCreateInvite()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Invite")
                }
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onScanQr()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Scan")
                }
            }

            if (showPasteField) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = pastedUrl,
                        onValueChange = { pastedUrl = it },
                        label = { Text("Paste where://invite?...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (pastedUrl.isNotEmpty()) {
                                    onDismiss()
                                    onPasteUrl(pastedUrl)
                                    pastedUrl = ""
                                    showPasteField = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Go")
                        }
                        FilledTonalButton(
                            onClick = {
                                pastedUrl = ""
                                showPasteField = false
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = { showPasteField = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Paste URL")
                }
            }

            if (friends.isNotEmpty()) {
                Text("Friends (${friends.size})", style = MaterialTheme.typography.labelMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(friends, key = { it.id }) { friend ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onZoomTo(friend.id)
                                        onDismiss()
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    friend.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    friend.safetyNumber,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    timeAgoString(friendLastPing[friend.id]),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val ackStale = friend.isInitiator &&
                                    friend.lastAckTs != Long.MAX_VALUE &&
                                    System.currentTimeMillis() / 1000 - friend.lastAckTs > E2eeStore.ACK_TIMEOUT_SECONDS
                                if (ackStale) {
                                    Text(
                                        "Not receiving this friend's acks — location sharing paused",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            IconButton(onClick = { renameFriend = friend }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
                            }

                            val isPaused = friend.id in pausedFriendIds
                            IconButton(onClick = { onTogglePause(friend.id) }) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause",
                                )
                            }

                            IconButton(onClick = { confirmDeleteFriend = friend }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "No friends yet. Tap Invite to share your QR code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmDeleteFriend?.let { friend ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFriend = null },
            title = { Text("Remove Friend") },
            text = { Text("Remove ${friend.name}? This will permanently delete the key.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(friend.id)
                        confirmDeleteFriend = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFriend = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    renameFriend?.let { friend ->
        var newName by remember { mutableStateOf(friend.name) }
        AlertDialog(
            onDismissRequest = { renameFriend = null },
            title = { Text("Rename Friend") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Friend's Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(friend.id, newName)
                        renameFriend = null
                    },
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameFriend = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
