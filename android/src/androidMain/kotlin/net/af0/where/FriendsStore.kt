package net.af0.where

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FriendsStore(context: Context, private val ownUserId: String) {

    private val prefs = context.getSharedPreferences("where_friends", Context.MODE_PRIVATE)

    private val _friendIds = MutableStateFlow(loadFriends())
    val friendIds: StateFlow<Set<String>> = _friendIds.asStateFlow()

    private val _isSharingLocation = MutableStateFlow(prefs.getBoolean("is_sharing", true))
    val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    fun add(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty() || trimmed == ownUserId) return
        _friendIds.update { it + trimmed }
        persist(_friendIds.value)
    }

    fun remove(id: String) {
        _friendIds.update { it - id }
        persist(_friendIds.value)
    }

    fun toggleSharing() {
        _isSharingLocation.update { prev ->
            (!prev).also { new -> prefs.edit().putBoolean("is_sharing", new).apply() }
        }
    }

    private fun loadFriends(): Set<String> =
        prefs.getStringSet("ids", emptySet())?.toSet() ?: emptySet()

    private fun persist(ids: Set<String>) =
        prefs.edit().putStringSet("ids", ids).apply()
}
