package net.af0.where

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Great-circle (haversine) distance in meters between two points in degrees. */
fun distanceMeters(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double,
): Double {
    val toRad = PI / 180
    val dLat = (lat2 - lat1) * toRad
    val dLng = (lng2 - lng1) * toRad
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * toRad) * cos(lat2 * toRad) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}
