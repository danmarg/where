package net.af0.where

import org.junit.Test
import kotlin.test.assertEquals

class SharingPillTest {
    private fun pill(
        isSharing: Boolean = true,
        permission: Boolean = true,
        services: Boolean = true,
        background: Boolean = true,
    ) = sharingPill(
        isSharing = isSharing,
        hasLocationPermission = permission,
        locationServicesEnabled = services,
        hasBackgroundLocationPermission = background,
    )

    private fun tap(
        isSharing: Boolean = true,
        permission: Boolean = true,
        permanentlyDenied: Boolean = false,
        services: Boolean = true,
        background: Boolean = true,
    ) = sharingTap(
        isSharing = isSharing,
        hasLocationPermission = permission,
        permissionPermanentlyDenied = permanentlyDenied,
        locationServicesEnabled = services,
        hasBackgroundLocationPermission = background,
    )

    /** Without background location, sharing stops once the app's service is restarted from the background, so it's limited. */
    @Test
    fun withoutBackgroundLocationSharingIsLimited() {
        assertEquals(SharingPill.SHARING_LIMITED, pill(background = false))
        assertEquals(SharingPill.SHARING, pill())
    }

    @Test
    fun notSharingIsPaused() {
        assertEquals(SharingPill.PAUSED, pill(isSharing = false))
        assertEquals(SharingPill.PAUSED, pill(isSharing = false, background = false))
    }

    /** Nothing can be shared without permission or location services, whatever the toggle says. */
    @Test
    fun noPermissionOrLocationServicesIsSharingOff() {
        for (isSharing in listOf(true, false)) {
            assertEquals(SharingPill.SHARING_OFF, pill(isSharing = isSharing, permission = false, background = false))
            assertEquals(SharingPill.SHARING_OFF, pill(isSharing = isSharing, services = false))
        }
    }

    /** Sharing, a tap stops - limited or not. */
    @Test
    fun tapWhileSharingStops() {
        assertEquals(SharingTap.STOP, tap())
        assertEquals(SharingTap.STOP, tap(background = false))
    }

    /** Not sharing, a tap starts, and asks for background location when it's missing. */
    @Test
    fun tapWhilePausedStartsAndAsksForWhatsMissing() {
        assertEquals(SharingTap.START, tap(isSharing = false))
        assertEquals(SharingTap.START_AND_ASK_BACKGROUND, tap(isSharing = false, background = false))
    }

    /** Sharing off, a tap goes to the fix without touching the toggle. */
    @Test
    fun tapWhenSharingIsOffGoesToTheFix() {
        for (isSharing in listOf(true, false)) {
            assertEquals(SharingTap.REQUEST_PERMISSION, tap(isSharing = isSharing, permission = false))
            assertEquals(SharingTap.OPEN_APP_SETTINGS, tap(isSharing = isSharing, permission = false, permanentlyDenied = true))
            assertEquals(SharingTap.OPEN_LOCATION_SETTINGS, tap(isSharing = isSharing, services = false))
        }
        assertEquals(SharingTap.REQUEST_PERMISSION, tap(permission = false, services = false), "permission first")
    }
}
