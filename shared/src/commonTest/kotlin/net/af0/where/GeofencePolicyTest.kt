package net.af0.where

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeofencePolicyTest {
    @Test
    fun `moving radius is larger than stationary radius`() {
        assertTrue(GeofencePolicy.radiusMeters(isMoving = true) > GeofencePolicy.radiusMeters(isMoving = false))
    }

    @Test
    fun `does not recenter for small drift`() {
        assertFalse(GeofencePolicy.shouldRecenter(distanceFromCenterMeters = 1.0, isMoving = false))
        assertFalse(GeofencePolicy.shouldRecenter(distanceFromCenterMeters = 1.0, isMoving = true))
    }

    @Test
    fun `recenters once drift crosses half the active radius`() {
        val stationaryRadius = GeofencePolicy.radiusMeters(isMoving = false)
        assertFalse(GeofencePolicy.shouldRecenter(stationaryRadius * 0.49, isMoving = false))
        assertTrue(GeofencePolicy.shouldRecenter(stationaryRadius * 0.5, isMoving = false))

        val movingRadius = GeofencePolicy.radiusMeters(isMoving = true)
        assertFalse(GeofencePolicy.shouldRecenter(movingRadius * 0.49, isMoving = true))
        assertTrue(GeofencePolicy.shouldRecenter(movingRadius * 0.5, isMoving = true))
    }

    @Test
    fun `same drift distance recenters sooner while stationary than while moving`() {
        // A fixed drift that's past the (tighter) stationary threshold but not yet past
        // the (looser) moving threshold - proves the two radii are actually used, not just
        // present.
        val drift = GeofencePolicy.radiusMeters(isMoving = false) * 0.75
        assertTrue(GeofencePolicy.shouldRecenter(drift, isMoving = false))
        assertFalse(GeofencePolicy.shouldRecenter(drift, isMoving = true))
    }

    @Test
    fun `radius values are stable constants`() {
        assertEquals(200.0, GeofencePolicy.radiusMeters(isMoving = false))
        assertEquals(400.0, GeofencePolicy.radiusMeters(isMoving = true))
    }
}
