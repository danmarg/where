package net.af0.where

import dev.icerock.moko.resources.desc.Raw
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.PendingInviteView
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.UserStore
import net.af0.where.model.UserLocation
import net.af0.where.shared.MR

/** Minimal interface over the shared location state, making it injectable for tests. */
interface LocationSource {
    val lastLocation: StateFlow<Triple<Double, Double, Double?>?>
    val friendLocations: StateFlow<Map<String, UserLocation>>
    val friendLastPing: StateFlow<Map<String, Long>>
    val connectionStatus: StateFlow<ConnectionStatus>
    val isAppInForeground: StateFlow<Boolean>
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?>
    val pendingInitAliceEkPub: StateFlow<ByteArray?>
    val isSharingLocation: StateFlow<Boolean>
    val pausedFriendIds: StateFlow<Set<String>>
    val friends: StateFlow<List<FriendEntry>>
    val allPendingInvites: StateFlow<List<PendingInviteView>>
    val lastRapidPollTrigger: StateFlow<Long>

    /** Wait for a poll wake signal (either interval timeout or immediate trigger). */
    suspend fun awaitPollWake(timeoutMillis: Long)

    fun onLocation(
        lat: Double,
        lng: Double,
        bearing: Double? = null,
    )

    fun onFriendUpdate(
        update: UserLocation,
        timestamp: Long = net.af0.where.e2ee.currentTimeMillis(),
    )

    fun onFriendRemoved(id: String)

    fun onConnectionStatus(status: ConnectionStatus)

    fun onConnectionError(e: Throwable)

    fun setAppForeground(foreground: Boolean)

    fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        aliceEkPub: ByteArray? = null,
    )

    fun onPendingInvitesUpdated(invites: List<PendingInviteView>)

    fun setSharingLocation(sharing: Boolean)

    fun setPausedFriends(friendIds: Set<String>)

    fun setInitialFriendLocations(
        locations: Map<String, UserLocation>,
        pings: Map<String, Long>,
    )

    fun onFriendsUpdated(friends: List<FriendEntry>)

    fun confirmQrScan()

    fun triggerRapidPoll()

    fun resetRapidPoll()

    fun markAwaitingFirstUpdate(friendId: String)

    fun onFriendLocationReceived(friendId: String)

    fun wakePoll()
}

/**
 * Repository that bridges the LocationService (which has no ViewModel access)
 * with the LocationViewModel via simple StateFlows.
 *
 * @param userStore The source of truth for user preferences (sharing, display name, etc).
 */
class LocationRepository(
    private val userStore: UserStore,
) : LocationSource {
    private val _lastLocation = MutableStateFlow<Triple<Double, Double, Double?>?>(null)
    override val lastLocation: StateFlow<Triple<Double, Double, Double?>?> = _lastLocation.asStateFlow()

    private val _friendLocations = MutableStateFlow<Map<String, UserLocation>>(emptyMap())
    override val friendLocations: StateFlow<Map<String, UserLocation>> = _friendLocations.asStateFlow()

    private val _friendLastPing = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _isAppInForeground = MutableStateFlow(false)
    override val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    internal val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    override val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload.asStateFlow()

    private val _pendingInitAliceEkPub = MutableStateFlow<ByteArray?>(null)
    override val pendingInitAliceEkPub: StateFlow<ByteArray?> = _pendingInitAliceEkPub.asStateFlow()

    override val isSharingLocation: StateFlow<Boolean> = userStore.isSharingLocation

    override val pausedFriendIds: StateFlow<Set<String>> = userStore.pausedFriendIds

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends.asStateFlow()

    private val _allPendingInvites = MutableStateFlow<List<PendingInviteView>>(emptyList())
    override val allPendingInvites: StateFlow<List<PendingInviteView>> = _allPendingInvites.asStateFlow()

    private val awaitingFirstUpdateIds = mutableSetOf<String>()

    private val pollWakeSignal = Channel<Unit>(Channel.CONFLATED)

    internal val _lastRapidPollTrigger = MutableStateFlow(0L)
    override val lastRapidPollTrigger: StateFlow<Long> = _lastRapidPollTrigger.asStateFlow()

    override fun onLocation(
        lat: Double,
        lng: Double,
        bearing: Double?,
    ) {
        _lastLocation.update { Triple(lat, lng, bearing) }
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
        if (e is kotlinx.coroutines.CancellationException) return
        val msg =
            when (e) {
                is net.af0.where.e2ee.ConnectException -> StringDesc.Resource(MR.strings.error_no_connection)
                is net.af0.where.e2ee.TimeoutException -> StringDesc.Resource(MR.strings.error_timeout)
                is net.af0.where.e2ee.ServerException -> {
                    if (e.statusCode == 429) {
                        StringDesc.Raw("Too many requests (rate limit)")
                    } else {
                        StringDesc.ResourceFormatted(MR.strings.error_server, e.statusCode)
                    }
                }
                is net.af0.where.e2ee.AuthenticationException -> StringDesc.Resource(MR.strings.error_auth)
                is net.af0.where.e2ee.SelfPairingException -> StringDesc.Raw("Cannot pair with yourself")
                is net.af0.where.e2ee.ProtocolException -> StringDesc.Resource(MR.strings.error_protocol)
                is net.af0.where.e2ee.CryptoException -> StringDesc.Resource(MR.strings.error_crypto)
                is net.af0.where.e2ee.NetworkException -> StringDesc.Raw("Network error: ${e.message}")
                else -> StringDesc.Resource(MR.strings.error_unexpected)
            }
        _connectionStatus.value = ConnectionStatus.Error(msg)
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        aliceEkPub: ByteArray?,
    ) {
        _pendingInitPayload.value = payload
        _pendingInitAliceEkPub.value = aliceEkPub
    }

    override fun onPendingInvitesUpdated(invites: List<PendingInviteView>) {
        _allPendingInvites.value = invites
    }

    override fun confirmQrScan() {
        pollWakeSignal.trySend(Unit)
    }

    override fun setSharingLocation(sharing: Boolean) {
        userStore.setSharing(sharing)
    }

    override fun setPausedFriends(friendIds: Set<String>) {
        userStore.setPausedFriends(friendIds)
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
        _lastRapidPollTrigger.value = net.af0.where.e2ee.currentTimeMillis()
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
        _pendingInitAliceEkPub.value = null
        _friends.value = emptyList()
        _allPendingInvites.value = emptyList()
        _lastRapidPollTrigger.value = 0L
        while (pollWakeSignal.tryReceive().isSuccess) { /* drain */ }
    }
}
