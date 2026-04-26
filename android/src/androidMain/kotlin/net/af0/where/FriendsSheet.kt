package net.af0.where

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.PendingInviteSummary
import net.af0.where.e2ee.discoveryToken
import net.af0.where.e2ee.toHex
import net.af0.where.shared.MR

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FriendsSheet(
    friends: List<FriendEntry>,
    pendingInvites: List<PendingInviteSummary> = emptyList(),
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
    onRemovePendingInviteSummary: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onZoomTo: (String) -> Unit = {},
) {
    var confirmDeleteFriend by remember { mutableStateOf<FriendEntry?>(null) }
    var confirmDeleteInvite by remember { mutableStateOf<PendingInviteSummary?>(null) }
    var renameFriend by remember { mutableStateOf<FriendEntry?>(null) }
    var showPasteField by remember { mutableStateOf(false) }
    var pastedUrl by remember { mutableStateOf("") }
    var debugExpandedFriendId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(MR.strings.friends), style = MaterialTheme.typography.titleLarge)

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
                    Text(" " + stringResource(MR.strings.invite))
                }
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onScanQr()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" " + stringResource(MR.strings.scan))
                }
            }

            if (friends.isNotEmpty() || pendingInvites.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (friends.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(MR.strings.friends) + " (${friends.size})",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    items(friends, key = { it.id }) { friend ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onZoomTo(friend.id)
                                                onDismiss()
                                            },
                                            onLongClick = {
                                                debugExpandedFriendId =
                                                    if (debugExpandedFriendId == friend.id) null else friend.id
                                            },
                                        ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (friend.isConfirmed) {
                                            friend.name
                                        } else {
                                            "${friend.name} (" + stringResource(MR.strings.pending) + ")"
                                        },
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
                                        timeAgoStringFromMs(friendLastPing[friend.id]),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (friend.isStale) {
                                        Text(
                                            stringResource(MR.strings.stale_friend_warning),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }

                                IconButton(onClick = { renameFriend = friend }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(MR.strings.rename),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                val isPaused = friend.id in pausedFriendIds
                                IconButton(onClick = { onTogglePause(friend.id) }) {
                                    Icon(
                                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription =
                                            if (isPaused) {
                                                stringResource(
                                                    MR.strings.resume,
                                                )
                                            } else {
                                                stringResource(MR.strings.pause)
                                            },
                                    )
                                }

                                IconButton(onClick = { confirmDeleteFriend = friend }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(MR.strings.remove),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (debugExpandedFriendId == friend.id) {
                                val recvToken = friend.session.recvToken.toHex().take(8)
                                val sendToken = friend.session.sendToken.toHex().take(8)
                                Column(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                                    Text(
                                        "recv: ${timeAgoStringFromSeconds(
                                            friend.lastRecvTs,
                                        )}  sent: ${timeAgoStringFromSeconds(friend.lastSentTs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "recvTok: $recvToken  sendTok: $sendToken",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val ratchet = if (friend.session.needsRatchet) "YES" else "no"
                                    val pending = if (friend.session.isSendTokenPending) "YES" else "no"
                                    Text(
                                        "needsRatchet: $ratchet  sendPending: $pending",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (pendingInvites.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(MR.strings.pending_invites) + " (${pendingInvites.size})",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(pendingInvites, key = { it.discoveryTokenHex }) { invite ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(MR.strings.invite),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        timeAgoStringFromSeconds(invite.createdAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { confirmDeleteInvite = invite }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(MR.strings.remove),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(MR.strings.no_friends_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmDeleteInvite?.let { invite ->
        AlertDialog(
            onDismissRequest = { confirmDeleteInvite = null },
            title = { Text(stringResource(MR.strings.cancel_invite_title)) },
            text = { Text(stringResource(MR.strings.cancel_invite_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemovePendingInviteSummary(invite.discoveryTokenHex)
                        confirmDeleteInvite = null
                    },
                ) {
                    Text(stringResource(MR.strings.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteInvite = null }) {
                    Text(stringResource(MR.strings.cancel))
                }
            },
        )
    }

    confirmDeleteFriend?.let { friend ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFriend = null },
            title = { Text(stringResource(MR.strings.remove_friend_title)) },
            text = { Text(stringResource(MR.strings.remove_friend_message, friend.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(friend.id)
                        confirmDeleteFriend = null
                    },
                ) {
                    Text(stringResource(MR.strings.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFriend = null }) {
                    Text(stringResource(MR.strings.cancel))
                }
            },
        )
    }

    renameFriend?.let { friend ->
        var newName by remember { mutableStateOf(friend.name) }
        AlertDialog(
            onDismissRequest = { renameFriend = null },
            title = { Text(stringResource(MR.strings.rename_friend_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(MR.strings.friend_name_label)) },
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
                    Text(stringResource(MR.strings.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameFriend = null }) {
                    Text(stringResource(MR.strings.cancel))
                }
            },
        )
    }
}
