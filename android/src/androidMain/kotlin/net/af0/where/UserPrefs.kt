package net.af0.where

import android.content.Context
import android.content.SharedPreferences

object UserPrefs {
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_IS_SHARING = "is_sharing"
    private const val KEY_PAUSED_FRIENDS = "paused_friends"
    private const val KEY_LAST_LAT = "last_lat"
    private const val KEY_LAST_LNG = "last_lng"
    private const val KEY_LAST_ZOOM = "last_zoom"
    private const val KEY_DEFAULT_PRECISION = "default_precision"

    private fun prefs(context: Context): SharedPreferences {
        val app =
            context.applicationContext as? WhereApplication
                ?: return context.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
        return app.encryptedPrefs
    }

    fun getDisplayName(context: Context): String = prefs(context).getString(KEY_DISPLAY_NAME, "") ?: ""

    fun setDisplayName(
        context: Context,
        name: String,
    ) {
        prefs(context).edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    fun isSharing(context: Context): Boolean = prefs(context).getBoolean(KEY_IS_SHARING, true)

    fun setSharing(
        context: Context,
        sharing: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_IS_SHARING, sharing).apply()
    }

    fun getPausedFriends(context: Context): Set<String> =
        prefs(context).getString(KEY_PAUSED_FRIENDS, "")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun setPausedFriends(
        context: Context,
        paused: Set<String>,
    ) {
        prefs(context).edit().putString(KEY_PAUSED_FRIENDS, paused.joinToString(",")).apply()
    }

    fun getLastLocation(context: Context): Triple<Double, Double, Float>? {
        val p = prefs(context)
        if (!p.contains(KEY_LAST_LAT)) return null
        return Triple(
            p.getFloat(KEY_LAST_LAT, 0f).toDouble(),
            p.getFloat(KEY_LAST_LNG, 0f).toDouble(),
            p.getFloat(KEY_LAST_ZOOM, 0f),
        )
    }

    fun setLastLocation(
        context: Context,
        lat: Double,
        lng: Double,
        zoom: Float,
    ) {
        prefs(context).edit()
            .putFloat(KEY_LAST_LAT, lat.toFloat())
            .putFloat(KEY_LAST_LNG, lng.toFloat())
            .putFloat(KEY_LAST_ZOOM, zoom)
            .apply()
    }
 
    fun getDefaultPrecision(context: Context): net.af0.where.e2ee.LocationPrecision =
        prefs(context).getString(KEY_DEFAULT_PRECISION, null)?.let {
            net.af0.where.e2ee.LocationPrecision.valueOf(it)
        } ?: net.af0.where.e2ee.LocationPrecision.FINE
 
    fun setDefaultPrecision(
        context: Context,
        precision: net.af0.where.e2ee.LocationPrecision,
    ) {
        prefs(context).edit().putString(KEY_DEFAULT_PRECISION, precision.name).apply()
    }
}
