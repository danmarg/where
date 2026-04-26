package net.af0.where

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.model.UserLocation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    @Test
    fun testMapScreenSelectionChangeDoesNotCrash() {
        // This is a minimal set of props for MapScreen
        val users = listOf(UserLocation("friend1", 1.0, 1.0, 1000L))
        val ownLocation = UserLocation("me", 0.0, 0.0, 1000L)

        val selectedUserIdState = mutableStateOf<String?>(null)

        composeTestRule.setContent {
            MapScreen(
                ownLocation = ownLocation,
                ownHeading = null,
                users = users,
                friends = emptyList(),
                pendingInvites = emptyList(),
                displayName = "Me",
                onDisplayNameChange = {},
                pausedFriendIds = emptySet(),
                onTogglePause = {},
                onCancelInvite = {},
                isSharing = true,
                onToggleSharing = {},
                connectionStatus = ConnectionStatus.Ok,
                onCreateInvite = {},
                onScanQr = {},
                onPasteUrl = {},
                friendLastPing = emptyMap(),
                onRenameFriend = { _, _ -> },
                onRemoveFriend = {},
                selectedUserId = selectedUserIdState.value,
                onSelectedUserIdChange = { selectedUserIdState.value = it },
            )
        }

        // 1. Initial state
        assert(selectedUserIdState.value == null)

        // 2. Simulate selecting a friend
        composeTestRule.runOnUiThread {
            selectedUserIdState.value = "friend1"
        }
        composeTestRule.waitForIdle()
        assert(selectedUserIdState.value == "friend1")

        // 3. Simulate deselecting
        composeTestRule.runOnUiThread {
            selectedUserIdState.value = null
        }
        composeTestRule.waitForIdle()
        assert(selectedUserIdState.value == null)
    }
}
