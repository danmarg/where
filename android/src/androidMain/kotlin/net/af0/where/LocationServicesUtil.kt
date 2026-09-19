package net.af0.where

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat

internal fun Context.hasLocationServicesEnabled(): Boolean {
    val locationManager = getSystemService(LocationManager::class.java) ?: return false
    return LocationManagerCompat.isLocationEnabled(locationManager)
}
