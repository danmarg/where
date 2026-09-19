package net.af0.where

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.model.UserLocation
import net.af0.where.shared.MR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// These tests grant location permission, which reaches MapScreen's real MapComposable — the
// MapLibre-backed fdroid variant JNI-loads a native lib that isn't present under Robolectric,
// so they only run against the GMS flavor. See androidUnitTest/MapScreenTest.kt for the
// permission-gated tests that run everywhere.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class MapScreenLocationServicesTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private fun setLocationServicesEnabled(enabled: Boolean) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(locationManager).setLocationEnabled(enabled)
    }

    private fun grantLocationPermission() {
        val context: android.app.Application = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun setContent() {
        val users = listOf(UserLocation("friend1", 1.0, 1.0, 1000L))
        val ownLocation = UserLocation("me", 0.0, 0.0, 1000L)
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
                onSetSharing = {},
                connectionStatus = ConnectionStatus.Ok,
                onCreateInvite = {},
                onScanQr = {},
                onPasteUrl = {},
                friendLastPing = emptyMap(),
                onRenameFriend = { _, _ -> },
                onRemoveFriend = {},
                selectedUserId = null,
                onSelectedUserIdChange = {},
            )
        }
    }

    @Test
    fun testMapScreen_ShowsLocationServicesWarning_WhenDisabled_WithoutHidingRestOfUi() {
        grantLocationPermission()
        setLocationServicesEnabled(false)
        setContent()
        composeTestRule.waitForIdle()

        val context: Context = ApplicationProvider.getApplicationContext()
        composeTestRule
            .onNodeWithText(StringDesc.Resource(MR.strings.location_services_required).toString(context))
            .assertExists()
        // The warning must not replace the rest of the map UI (friends button, sharing
        // toggle) — only the own-location state is misleading, not friend-related UI.
        composeTestRule
            .onNodeWithText(StringDesc.Resource(MR.strings.sharing).toString(context))
            .assertExists()
    }

    @Test
    fun testMapScreen_ReactsLiveToProvidersChangedBroadcast() {
        grantLocationPermission()
        setLocationServicesEnabled(true)
        setContent()
        composeTestRule.waitForIdle()

        val context: Context = ApplicationProvider.getApplicationContext()
        composeTestRule
            .onNodeWithText(StringDesc.Resource(MR.strings.location_services_required).toString(context))
            .assertDoesNotExist()

        setLocationServicesEnabled(false)
        context.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(StringDesc.Resource(MR.strings.location_services_required).toString(context))
            .assertExists()
    }
}
