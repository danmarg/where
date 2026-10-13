package net.af0.where

import org.junit.Test
import kotlin.test.assertEquals

class SharingPillTest {
    /** Without background location, sharing stops once the app's service can't run, so it's "while open". */
    @Test
    fun withoutBackgroundLocationSharingIsWhileOpen() {
        assertEquals(
            SharingPill.SHARING_WHILE_OPEN,
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
