package net.af0.where

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FriendsStore(context: Context, private val ownUserId: String) {

    private val prefs = context.getSharedPreferences("where_friends", Context.MODE_PRIVATE)

    private val _friendIds = MutableStateFlow(loadFriends())
    val friendIds: StateFlow<Set<String>> = _friendIds.asStateFlow()

    private val _isSharingLocation = MutableStateFlow(true)
    val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    fun add(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty() || trimmed == ownUserId) return
        val updated = _friendIds.value + trimmed
        _friendIds.value = updated
        persist(updated)
    }

    fun remove(id: String) {
        val updated = _friendIds.value - id
        _friendIds.value = updated
        persist(updated)
    }

    fun toggleSharing() {
        _isSharingLocation.value = !_isSharingLocation.value
    }

    private fun loadFriends(): Set<String> =
        prefs.getStringSet("ids", emptySet())?.toSet() ?: emptySet()

    private fun persist(ids: Set<String>) =
        prefs.edit().putStringSet("ids", ids).apply()
}
