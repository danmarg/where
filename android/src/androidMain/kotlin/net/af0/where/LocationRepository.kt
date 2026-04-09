package net.af0.where

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.model.UserLocation

sealed class ConnectionStatus {
    object Ok : ConnectionStatus()

    data class Error(val message: String) : ConnectionStatus()
}

/** Minimal interface over the shared location state, making it injectable for tests. */
interface LocationSource {
    val lastLocation: StateFlow<Pair<Double, Double>?>
    val friendLocations: StateFlow<Map<String, UserLocation>>
    val friendLastPing: StateFlow<Map<String, Long>>
    val connectionStatus: StateFlow<ConnectionStatus>
    val isAppInForeground: StateFlow<Boolean>
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?>
    val isSharingLocation: StateFlow<Boolean>
    val pausedFriendIds: StateFlow<Set<String>>
    val friends: StateFlow<List<FriendEntry>>
    val lastRapidPollTrigger: StateFlow<Long>
    val pendingQrForNaming: StateFlow<QrPayload?>

    /** Wait for a poll wake signal (either interval timeout or immediate trigger). */
    suspend fun awaitPollWake(timeoutMillis: Long)

    fun onLocation(
        lat: Double,
        lng: Double,
    )

    fun onFriendUpdate(
        update: UserLocation,
        timestamp: Long = System.currentTimeMillis(),
    )

    fun onFriendRemoved(id: String)

    fun onConnectionStatus(status: ConnectionStatus)

    fun onConnectionError(e: Throwable)

    fun setAppForeground(foreground: Boolean)

    fun onPendingInit(payload: KeyExchangeInitPayload?)

    fun setSharingLocation(sharing: Boolean)

    fun setPausedFriends(friendIds: Set<String>)

    fun setInitialFriendLocations(
        locations: Map<String, UserLocation>,
        pings: Map<String, Long>,
    )

    fun onFriendsUpdated(friends: List<FriendEntry>)

    fun triggerRapidPoll()

    fun resetRapidPoll()

    fun wakePoll()
}

/**
 * Singleton that bridges the LocationService (which has no ViewModel access)
 * with the LocationViewModel via simple StateFlows.
 */
object LocationRepository : LocationSource {
    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation.asStateFlow()

    private val _friendLocations = MutableStateFlow<Map<String, UserLocation>>(emptyMap())
    override val friendLocations: StateFlow<Map<String, UserLocation>> = _friendLocations.asStateFlow()

    private val _friendLastPing = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _isAppInForeground = MutableStateFlow(false)
    override val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    @get:VisibleForTesting
    internal val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    override val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload.asStateFlow()

    private val _isSharingLocation = MutableStateFlow(true)
    override val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    private val _pausedFriendIds = MutableStateFlow<Set<String>>(emptySet())
    override val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends.asStateFlow()

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    override val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming.asStateFlow()

    private val pollWakeSignal = Channel<Unit>(Channel.CONFLATED)

    @get:VisibleForTesting
    internal val _lastRapidPollTrigger = MutableStateFlow(0L)
    override val lastRapidPollTrigger: StateFlow<Long> = _lastRapidPollTrigger.asStateFlow()

    override fun onLocation(
        lat: Double,
        lng: Double,
    ) {
        _lastLocation.update { Pair(lat, lng) }
    }

    override fun onFriendUpdate(
        update: UserLocation,
        timestamp: Long,
    ) {
        _friendLocations.update { it + (update.userId to update) }
        _friendLastPing.update { it + (update.userId to timestamp) }
    }

    override fun onFriendRemoved(id: String) {
        _friendLocations.update { it - id }
        _friendLastPing.update { it - id }
    }

    override fun onConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    override fun onConnectionError(e: Throwable) {
        val msg =
            when {
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "not resolved"
                e.message?.contains("timeout", ignoreCase = true) == true -> "timeout"
                e.message?.contains("ConnectException", ignoreCase = true) == true -> "no connection"
                e.message?.contains("Failed to post to mailbox: 500", ignoreCase = true) == true -> "server error 500"
                else -> e.message?.take(32) ?: "unknown error"
            }
        _connectionStatus.value = ConnectionStatus.Error(msg)
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(payload: KeyExchangeInitPayload?) {
        _pendingInitPayload.value = payload
    }

    /** Called by ViewModel to notify Bob scanned a QR and is naming the friend. */
    fun onPendingQrForNaming(qr: QrPayload?) {
        _pendingQrForNaming.value = qr
    }

    override fun setSharingLocation(sharing: Boolean) {
        _isSharingLocation.value = sharing
    }

    override fun setPausedFriends(friendIds: Set<String>) {
        _pausedFriendIds.value = friendIds
    }

    override fun setInitialFriendLocations(
        locations: Map<String, UserLocation>,
        pings: Map<String, Long>,
    ) {
        // Merge with current state to avoid overwriting live updates that arrived before initial load
        _friendLocations.update { locations + it }
        _friendLastPing.update { pings + it }
    }

    override fun onFriendsUpdated(friends: List<FriendEntry>) {
        _friends.value = friends
    }

    override fun triggerRapidPoll() {
        _lastRapidPollTrigger.value = LocationService.clock()
        pollWakeSignal.trySend(Unit)
    }

    override fun resetRapidPoll() {
        _lastRapidPollTrigger.value = 0L
    }

    override fun wakePoll() {
        pollWakeSignal.trySend(Unit)
    }

    override suspend fun awaitPollWake(timeoutMillis: Long) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            pollWakeSignal.receive()
        }
    }

    /** Resets all state for tests. */
    fun reset() {
        _lastLocation.value = null
        _friendLocations.value = emptyMap()
        _friendLastPing.value = emptyMap()
        _connectionStatus.value = ConnectionStatus.Ok
        _isAppInForeground.value = false
        _pendingInitPayload.value = null
        _isSharingLocation.value = true
        _pausedFriendIds.value = emptySet()
        _friends.value = emptyList()
        _lastRapidPollTrigger.value = 0L
        while (pollWakeSignal.tryReceive().isSuccess) { /* drain */ }
    }
}
