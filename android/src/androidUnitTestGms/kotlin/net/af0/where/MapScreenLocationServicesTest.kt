package net.af0.where

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// MapScreen always composes the real MapComposable — the MapLibre-backed fdroid variant JNI-loads
// a native lib that isn't present under Robolectric, so these only run against the GMS flavor.
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

    private fun setContent(
        isSharing: Boolean = true,
        onSetSharing: (Boolean) -> Unit = {},
    ) {
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
                isSharing = isSharing,
                onSetSharing = onSetSharing,
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

    private fun text(res: dev.icerock.moko.resources.StringResource): String =
        StringDesc.Resource(res).toString(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun testMapScreen_WithoutPermission_IsReceiveOnly_NotBlocked() {
        setLocationServicesEnabled(true)
        setContent(isSharing = true)
        composeTestRule.waitForIdle()

        // Non-blocking prompt, not a full-page wall.
        composeTestRule.onNodeWithText(text(MR.strings.location_permission_needed_to_share)).assertExists()
        composeTestRule.onNodeWithText(text(MR.strings.grant_permission)).assertExists()
        // Friends/invite UI is still there (the friends button shows the count).
        composeTestRule.onNodeWithText("0").assertExists()
        // A stored "sharing on" is shown as paused and cannot be toggled.
        composeTestRule.onNodeWithText(text(MR.strings.paused)).assertExists().assertIsNotEnabled()
        composeTestRule.onNodeWithText(text(MR.strings.sharing)).assertDoesNotExist()
    }

    @Test
    fun testMapScreen_WithoutPermission_SharingToggleDoesNothing() {
        setLocationServicesEnabled(true)
        val calls = mutableListOf<Boolean>()
        setContent(isSharing = false, onSetSharing = { calls += it })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(text(MR.strings.paused)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(calls.isEmpty(), "disabled toggle must not enable sharing, got $calls")
    }

    @Test
    fun testMapScreen_WithPermission_NoBannerAndToggleWorks() {
        grantLocationPermission()
        setLocationServicesEnabled(true)
        val calls = mutableListOf<Boolean>()
        setContent(isSharing = true, onSetSharing = { calls += it })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(text(MR.strings.location_permission_needed_to_share)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(MR.strings.sharing)).assertExists().assertIsEnabled().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), calls)
    }

    @Test
    fun testMapScreen_WithoutPermission_ShowsPermissionBannerInsteadOfServicesWarning() {
        setLocationServicesEnabled(false)
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(text(MR.strings.location_permission_needed_to_share)).assertExists()
        composeTestRule.onNodeWithText(text(MR.strings.location_services_required)).assertDoesNotExist()
    }

    @Test
    fun testMapScreen_PermissionPreviouslyRequestedAndDenied_OffersAppSettings() {
        val app: android.app.Application = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("location_permission_requested", true).commit()
        setLocationServicesEnabled(true)
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(text(MR.strings.grant_permission)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(MR.strings.open_settings)).performClick()
        composeTestRule.waitForIdle()

        val started = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started?.action)
    }

    @Test
    fun testMapScreen_PermissionNeverRequested_OffersGrant() {
        setLocationServicesEnabled(true)
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(text(MR.strings.grant_permission)).assertExists()
        composeTestRule.onNodeWithText(text(MR.strings.open_settings)).assertDoesNotExist()
    }
}
