package net.af0.where

import android.content.Context
import android.location.LocationManager

internal fun Context.hasLocationServicesEnabled(): Boolean {
    val locationManager = getSystemService(LocationManager::class.java) ?: return false
    return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}
