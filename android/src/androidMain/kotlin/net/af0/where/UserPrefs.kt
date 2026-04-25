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

    fun getDisplayName(context: Context): String = store(context).displayName.value

    fun setDisplayName(
        context: Context,
        name: String,
    ) = store(context).setDisplayName(name)

    fun isSharing(context: Context): Boolean = store(context).isSharingLocation.value

    fun setSharing(
        context: Context,
        sharing: Boolean,
    ) = store(context).setSharing(sharing)

    fun getPausedFriends(context: Context): Set<String> = store(context).pausedFriendIds.value

    fun setPausedFriends(
        context: Context,
        paused: Set<String>,
    ) = store(context).setPausedFriends(paused)

    fun getLastLocation(context: Context): Triple<Double, Double, Float>? = store(context).lastMapCamera.value

    fun setLastLocation(
        context: Context,
        lat: Double,
        lng: Double,
        zoom: Float,
    ) = store(context).setLastMapCamera(lat, lng, zoom)

    fun hasRequestedCamera(context: Context): Boolean =
        context.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
            .getBoolean("camera_requested", false)

    fun setCameraRequested(context: Context) =
        context.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("camera_requested", true)
            .apply()
}
