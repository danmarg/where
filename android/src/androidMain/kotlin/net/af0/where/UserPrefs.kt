package net.af0.where

import android.content.Context
import java.util.UUID

object UserPrefs {
    private const val PREFS_NAME = "where_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_IS_SHARING = "is_sharing"
    private const val KEY_PAUSED_FRIENDS = "paused_friends"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUserId(context: Context): String {
        val prefs = prefs(context)
        return prefs.getString(KEY_USER_ID, null) ?: run {
            val newId = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_USER_ID, newId).apply()
            newId
        }
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
}
