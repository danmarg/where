package net.af0.where

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.model.UserLocation

/** Minimal interface over the shared location state, making it injectable for tests. */
interface LocationSource {
    val lastLocation: StateFlow<Pair<Double, Double>?>
    val friendLocations: StateFlow<Map<String, UserLocation>>
    val friendLastPing: StateFlow<Map<String, Long>>
    val connectionStatus: StateFlow<ConnectionStatus>
    val isAppInForeground: StateFlow<Boolean>
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?>
    val multipleScansDetected: StateFlow<Boolean>
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

    fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        multipleScans: Boolean = false,
    )

    fun setSharingLocation(sharing: Boolean)

    fun setPausedFriends(friendIds: Set<String>)

    fun setInitialFriendLocations(
        locations: Map<String, UserLocation>,
        pings: Map<String, Long>,
    )

    fun onFriendsUpdated(friends: List<FriendEntry>)

    fun onPendingQrForNaming(qr: QrPayload?)

    fun confirmQrScan()

    fun triggerRapidPoll()

    fun resetRapidPoll()

    fun markAwaitingFirstUpdate(friendId: String)

    fun onFriendLocationReceived(friendId: String)

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

    private val _multipleScansDetected = MutableStateFlow(false)
    override val multipleScansDetected: StateFlow<Boolean> = _multipleScansDetected.asStateFlow()

    private val _isSharingLocation = MutableStateFlow(false)
    override val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    private val _pausedFriendIds = MutableStateFlow<Set<String>>(emptySet())
    override val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends.asStateFlow()

    private val awaitingFirstUpdateIds = mutableSetOf<String>()

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
            when (e) {
                is net.af0.where.e2ee.ConnectException -> "No connection: Could not reach the server."
                is net.af0.where.e2ee.TimeoutException -> "Request timed out. Please try again."
                is net.af0.where.e2ee.ServerException -> "Server error ${e.statusCode}: Something went wrong on the server."
                is net.af0.where.e2ee.AuthenticationException -> "Authentication failed. Please try re-pairing."
                is net.af0.where.e2ee.ProtocolException -> "Protocol error: Session may be out of sync."
                is net.af0.where.e2ee.CryptoException -> "Security error: Cryptographic validation failed."
                is net.af0.where.e2ee.NetworkException -> "Network error: ${e.message}"
                else -> {
                    when {
                        e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                            "No connection: Could not reach the server."
                        e.message?.contains("timeout", ignoreCase = true) == true -> "Request timed out."
                        e.message?.contains("ConnectException", ignoreCase = true) == true ->
                            "No connection: Could not establish a connection."
                        else -> "An unexpected error occurred. Please try again."
                    }
                }
            }
        _connectionStatus.value = ConnectionStatus.Error(msg)
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        multipleScans: Boolean,
    ) {
        _pendingInitPayload.value = payload
        _multipleScansDetected.value = multipleScans
    }

    /** Called by ViewModel to notify Bob scanned a QR and is naming the friend. */
    override fun onPendingQrForNaming(qr: QrPayload?) {
        _pendingQrForNaming.value = qr
    }

    override fun confirmQrScan() {
        pollWakeSignal.trySend(Unit)
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
        awaitingFirstUpdateIds.clear()
    }

    override fun markAwaitingFirstUpdate(friendId: String) {
        awaitingFirstUpdateIds.add(friendId)
    }

    override fun onFriendLocationReceived(friendId: String) {
        if (awaitingFirstUpdateIds.remove(friendId)) {
            if (awaitingFirstUpdateIds.isEmpty()) {
                resetRapidPoll()
            }
        }
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
        _multipleScansDetected.value = false
        _isSharingLocation.value = false
        _pausedFriendIds.value = emptySet()
        _friends.value = emptyList()
        _lastRapidPollTrigger.value = 0L
        while (pollWakeSignal.tryReceive().isSuccess) { /* drain */ }
    }
}
