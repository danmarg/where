package net.af0.where

import android.content.Context
import net.af0.where.e2ee.UserStore

/**
 * Android-specific wrapper for accessing the shared UserStore.
 * Delegates most calls to the application-level UserStore singleton.
 */
object UserPrefs {
    private fun store(context: Context): UserStore {
        val app =
            context.applicationContext as? WhereApplication
                ?: throw IllegalStateException("Context must be an instance of WhereApplication or provide one")
        return app.userStore
    }

    fun isSharing(context: Context): Boolean = store(context).isSharingLocation.value

    fun setSharing(
        context: Context,
        sharing: Boolean,
    ) = store(context).setSharing(sharing)

    fun getLastLocation(context: Context): Triple<Double, Double, Float>? = store(context).lastMapCamera.value

    fun setLastLocation(
        context: Context,
        lat: Double,
        lng: Double,
        zoom: Float,
    ) = store(context).setLastMapCamera(lat, lng, zoom)

    fun hasRequestedCamera(context: Context): Boolean = store(context).cameraRequested.value

    fun setCameraRequested(context: Context) = store(context).setCameraRequested(true)

    fun hasShownBackgroundLocationRationale(context: Context): Boolean = store(context).backgroundLocationRationaleShown.value

    fun setBackgroundLocationRationaleShown(context: Context) = store(context).setBackgroundLocationRationaleShown(true)
}
