package net.af0.where

import org.junit.Test
import kotlin.test.assertEquals

class SharingPillTest {
    /** Without background location, sharing stops once the app's service is restarted from the background, so it's limited. */
    @Test
    fun withoutBackgroundLocationSharingIsLimited() {
        assertEquals(
            SharingPill.SHARING_LIMITED,
            sharingPill(isSharing = true, hasLocationPermission = true, hasBackgroundLocationPermission = false),
        )
        assertEquals(
            SharingPill.SHARING,
            sharingPill(isSharing = true, hasLocationPermission = true, hasBackgroundLocationPermission = true),
        )
    }

    @Test
    fun notSharingOrNoPermissionIsPaused() {
        assertEquals(
            SharingPill.PAUSED,
            sharingPill(isSharing = false, hasLocationPermission = true, hasBackgroundLocationPermission = true),
        )
        assertEquals(
            SharingPill.PAUSED,
            sharingPill(isSharing = false, hasLocationPermission = true, hasBackgroundLocationPermission = false),
        )
        assertEquals(
            SharingPill.PAUSED,
            sharingPill(isSharing = true, hasLocationPermission = false, hasBackgroundLocationPermission = false),
        )
    }
}
