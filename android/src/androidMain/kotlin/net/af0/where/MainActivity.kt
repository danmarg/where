package net.af0.where

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private val viewModel: LocationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, LocationService::class.java))

        setContent {
            MaterialTheme {
                val users by viewModel.visibleUsers.collectAsState()
                val friendIds by viewModel.friendsStore.friendIds.collectAsState()
                val isSharing by viewModel.friendsStore.isSharingLocation.collectAsState()
                MapScreen(
                    userId = viewModel.userId,
                    users = users,
                    friendIds = friendIds,
                    isSharing = isSharing,
                    onToggleSharing = { viewModel.friendsStore.toggleSharing() },
                    onAddFriend = { viewModel.friendsStore.add(it) },
                    onRemoveFriend = { viewModel.friendsStore.remove(it) },
                )
            }
        }
    }
}
