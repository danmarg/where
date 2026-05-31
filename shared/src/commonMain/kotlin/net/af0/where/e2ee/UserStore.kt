package net.af0.where.e2ee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Shared store for user-level preferences that need to be reactive across platforms.
 * Uses the same RawKeyValueStorage as E2eeManager but manages its own keys.
 */
class UserStore(private val storage: RawKeyValueStorage) {
    private val _isSharingLocation =
        MutableStateFlow(
            storage.getString(KEY_IS_SHARING)?.toBoolean() ?: true,
        )
    val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    private val _displayName =
        MutableStateFlow(
            storage.getString(KEY_DISPLAY_NAME) ?: "",
        )
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _pausedFriendIds =
        MutableStateFlow(
            loadPausedFriends(),
        )
    val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds.asStateFlow()

    private fun loadPausedFriends(): Set<String> {
        val str = storage.getString(KEY_PAUSED_FRIENDS) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(str)
        } catch (_: Exception) {
            // Fallback for comma-separated (migration from older Android versions)
            str.split(",").filter { it.isNotEmpty() }.toSet()
        }
    }

    private fun persistPausedFriends(ids: Set<String>) {
        storage.putString(KEY_PAUSED_FRIENDS, json.encodeToString(ids))
    }

    fun setSharing(sharing: Boolean) {
        _isSharingLocation.value = sharing
        storage.putString(KEY_IS_SHARING, sharing.toString())
    }

    fun setDisplayName(name: String) {
        _displayName.value = name
        storage.putString(KEY_DISPLAY_NAME, name)
    }

    fun togglePauseFriend(id: String) {
        _pausedFriendIds.update { current ->
            val new = if (id in current) current - id else current + id
            persistPausedFriends(new)
            new
        }
    }

    fun removePausedFriend(id: String) {
        _pausedFriendIds.update { current ->
            if (id !in current) return@update current
            val new = current - id
            persistPausedFriends(new)
            new
        }
    }

    fun setPausedFriends(ids: Set<String>) {
        _pausedFriendIds.value = ids
        persistPausedFriends(ids)
    }

    private val _lastMapCamera = MutableStateFlow(loadLastMapCamera())
    val lastMapCamera: StateFlow<Triple<Double, Double, Float>?> = _lastMapCamera.asStateFlow()

    private fun loadLastMapCamera(): Triple<Double, Double, Float>? {
        val lat = storage.getString(KEY_LAST_LAT)?.toDoubleOrNull() ?: return null
        val lng = storage.getString(KEY_LAST_LNG)?.toDoubleOrNull() ?: return null
        val zoom = storage.getString(KEY_LAST_ZOOM)?.toFloatOrNull() ?: return null
        return Triple(lat, lng, zoom)
    }

    fun setLastMapCamera(
        lat: Double,
        lng: Double,
        zoom: Float,
    ) {
        _lastMapCamera.value = Triple(lat, lng, zoom)
        storage.putString(KEY_LAST_LAT, lat.toString())
        storage.putString(KEY_LAST_LNG, lng.toString())
        storage.putString(KEY_LAST_ZOOM, zoom.toString())
    }

    private val _cameraRequested =
        MutableStateFlow(
            storage.getString(KEY_CAMERA_REQUESTED)?.toBoolean() ?: false,
        )
    val cameraRequested: StateFlow<Boolean> = _cameraRequested.asStateFlow()

    fun setCameraRequested(requested: Boolean) {
        _cameraRequested.value = requested
        storage.putString(KEY_CAMERA_REQUESTED, requested.toString())
    }

    // ---- Per-friend timed share ------------------------------------------------------------

    /**
     * Per-friend epoch-seconds at which sharing with that friend auto-pauses.
     * A friend not in the map shares indefinitely (subject to the global toggle and
     * pausedFriendIds). Persisted as JSON in KV, like [pausedFriendIds].
     */
    private val _friendExpiresAt =
        MutableStateFlow(loadFriendExpiresAt())
    val friendExpiresAt: StateFlow<Map<String, Long>> = _friendExpiresAt.asStateFlow()

    private fun loadFriendExpiresAt(): Map<String, Long> {
        val str = storage.getString(KEY_FRIEND_EXPIRES_AT) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(str)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistFriendExpiresAt(map: Map<String, Long>) {
        storage.putString(KEY_FRIEND_EXPIRES_AT, json.encodeToString(map))
    }

    fun setFriendExpiry(friendId: String, epochSeconds: Long?) {
        _friendExpiresAt.update { current ->
            val new = if (epochSeconds == null) current - friendId else current + (friendId to epochSeconds)
            persistFriendExpiresAt(new)
            new
        }
    }

    fun removeFriendExpiry(friendId: String) = setFriendExpiry(friendId, null)

    /**
     * Source-of-truth set of friends that must not receive Location messages right now,
     * combining the user's explicit pause list with any per-friend timer that has elapsed.
     * Every send-path gate uses this — the per-friend watcher is the fast path that
     * propagates expiry → persisted pause + StoppedSharing, but this set is the hard
     * guarantee that no Location slips through in the window before the watcher fires.
     */
    fun effectivelyPausedIds(nowSeconds: Long = currentTimeSeconds()): Set<String> {
        val expired = _friendExpiresAt.value.entries.asSequence()
            .filter { (_, expiry) -> nowSeconds >= expiry }
            .map { it.key }
            .toSet()
        return if (expired.isEmpty()) _pausedFriendIds.value else _pausedFriendIds.value + expired
    }

    companion object {
        private const val KEY_IS_SHARING = "is_sharing"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PAUSED_FRIENDS = "paused_friends"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        private const val KEY_LAST_ZOOM = "last_zoom"
        private const val KEY_CAMERA_REQUESTED = "camera_requested"
        private const val KEY_FRIEND_EXPIRES_AT = "friend_expires_at"
    }
}
