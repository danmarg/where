package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.af0.where.e2ee.E2eeMailboxClient
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.discoveryToken
import net.af0.where.e2ee.toHex
import net.af0.where.model.UserLocation

private const val TAG = "LocationViewModel"

sealed class ConnectionStatus {
    object Ok : ConnectionStatus()

    data class Error(val message: String) : ConnectionStatus()
}

sealed interface InviteState {
    object None : InviteState

    data class Pending(val qr: QrPayload) : InviteState

    data class Consumed(val qr: QrPayload) : InviteState
}

data class PollingState(
    val isPolling: Boolean = true,
    val lastRapidPollTrigger: Long = 0L,
    val lastSentLat: Double? = null,
    val lastSentLng: Double? = null,
    val lastSentTime: Long = 0L,
)

class LocationViewModel(
    app: Application,
    e2eeStore: E2eeStore? = null,
    locationClient: LocationClient? = null,
    startPolling: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val locationSource: LocationSource = LocationRepository,
) : AndroidViewModel(app) {
    // Use the Application-level singletons so LocationService and this ViewModel share the same
    // E2EE state. Fall back to creating new instances when running under test (app is not
    // WhereApplication in unit tests).
    private val e2eeStore: E2eeStore =
        e2eeStore
            ?: (app as? WhereApplication)?.e2eeStore
            ?: E2eeStore(SharedPrefsE2eeStorage(app))
    private val locationClient: LocationClient =
        locationClient
            ?: (app as? WhereApplication)?.locationClient
            ?: LocationClient(BuildConfig.SERVER_HTTP_URL, this.e2eeStore)

    val userId: String by lazy { UserPrefs.getUserId(getApplication()) }

    private val _isSharingLocation = MutableStateFlow(UserPrefs.isSharing(app))
    val isSharingLocation: StateFlow<Boolean> = _isSharingLocation

    private val _displayName = MutableStateFlow(UserPrefs.getDisplayName(app))
    val displayName: StateFlow<String> = _displayName

    private val _friends = MutableStateFlow(emptyList<FriendEntry>())
    val friends: StateFlow<List<FriendEntry>> = _friends

    private val _pausedFriendIds = MutableStateFlow(UserPrefs.getPausedFriends(app))
    val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds

    internal val friendLocations = MutableStateFlow(emptyMap<String, UserLocation>())
    private val _friendLastPing = MutableStateFlow(emptyMap<String, Long>())
    val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing

    private val _inviteState = MutableStateFlow<InviteState>(InviteState.None)
    val inviteState: StateFlow<InviteState> = _inviteState

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload

    private val _isExchanging = MutableStateFlow(false)
    val isExchanging: StateFlow<Boolean> = _isExchanging

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val pollingStateInternal = MutableStateFlow(PollingState())
    private val pollWakeSignal = Channel<Unit>(Channel.CONFLATED)

    val visibleUsers: StateFlow<List<UserLocation>> =
        combine(locationSource.lastLocation, _isSharingLocation, friendLocations) { myLoc, sharing, friendLocs ->
            buildList {
                if (myLoc != null && sharing) {
                    add(UserLocation(userId, myLoc.first, myLoc.second, clock() / 1000))
                }
                addAll(friendLocs.values)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        Log.d(TAG, "LocationViewModel init: server=${BuildConfig.SERVER_HTTP_URL}, userId=$userId")
        viewModelScope.launch {
            val savedFriends = this@LocationViewModel.e2eeStore.listFriends()
            _friends.value = savedFriends
            val initialLocations = mutableMapOf<String, UserLocation>()
            val initialLastPing = mutableMapOf<String, Long>()
            for (friend in savedFriends) {
                val lat = friend.lastLat
                val lng = friend.lastLng
                val ts = friend.lastTs
                if (lat != null && lng != null && ts != null) {
                    initialLocations[friend.id] = UserLocation(friend.id, lat, lng, ts)
                    initialLastPing[friend.id] = ts * 1000L
                }
            }
            friendLocations.value = initialLocations
            _friendLastPing.value = initialLastPing
        }

        if (startPolling) {
            viewModelScope.launch { pollLoop() }
            // Send location whenever FusedLocation delivers a new position.
            viewModelScope.launch {
                locationSource.lastLocation.collect { loc ->
                    if (loc != null && _isSharingLocation.value) {
                        sendLocationIfNeeded(loc.first, loc.second, isHeartbeat = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            _isSharingLocation.collect { sharing ->
                manageForegroundService(sharing)
            }
        }
    }

    fun stopPolling() {
        pollingStateInternal.update { it.copy(isPolling = false) }
    }

    private fun triggerRapidPoll() {
        pollingStateInternal.update { it.copy(lastRapidPollTrigger = clock()) }
        pollWakeSignal.trySend(Unit)
    }

    internal fun isRapidPolling(): Boolean {
        val now = clock()
        val isPairing = _inviteState.value is InviteState.Pending || _pendingInitPayload.value != null || _pendingQrForNaming.value != null
        val recentlyTriggered = now - pollingStateInternal.value.lastRapidPollTrigger < 5 * 60_000L
        return isPairing || recentlyTriggered
    }

    fun setDisplayName(name: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setDisplayName must be called on the main thread" }
        _displayName.value = name
        UserPrefs.setDisplayName(getApplication(), name)
    }

    fun toggleSharing() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "toggleSharing must be called on the main thread" }
        val new = !_isSharingLocation.value
        _isSharingLocation.value = new
        UserPrefs.setSharing(getApplication(), new)
    }

    fun togglePauseFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "togglePauseFriend must be called on the main thread" }
        val current = _pausedFriendIds.value
        val new = if (id in current) current - id else current + id
        _pausedFriendIds.value = new
        UserPrefs.setPausedFriends(getApplication(), new)
    }

    fun renameFriend(
        id: String,
        newName: String,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "renameFriend must be called on the main thread" }
        viewModelScope.launch {
            e2eeStore.renameFriend(id, newName)
            _friends.value = e2eeStore.listFriends()
        }
    }

    fun removeFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "removeFriend must be called on the main thread" }
        viewModelScope.launch {
            e2eeStore.deleteFriend(id)
            val updatedFriends = e2eeStore.listFriends()
            withContext(Dispatchers.Main.immediate) {
                _friends.value = updatedFriends
                friendLocations.value -= id
                if (id in _pausedFriendIds.value) {
                    val newPaused = _pausedFriendIds.value - id
                    _pausedFriendIds.value = newPaused
                    UserPrefs.setPausedFriends(getApplication(), newPaused)
                }
            }
        }
    }

    fun createInvite() {
        viewModelScope.launch {
            val qr = e2eeStore.createInvite(_displayName.value.ifEmpty { "Me" })
            _inviteState.value = InviteState.Pending(qr)
            triggerRapidPoll()
            pollPendingInvite()
        }
    }

    fun clearInvite() {
        val current = _inviteState.value
        if (current is InviteState.Pending) {
            viewModelScope.launch {
                e2eeStore.clearInvite()
            }
        }
        _inviteState.value = InviteState.None
    }

    fun processQrUrl(url: String): Boolean {
        Log.d(TAG, "processQrUrl: url=$url")
        val qr =
            QrUtils.urlToPayload(url) ?: run {
                Log.e(TAG, "processQrUrl: failed to parse URL")
                return false
            }
        Log.d(TAG, "processQrUrl: parsed qr, suggestedName=${qr.suggestedName}")
        _pendingQrForNaming.value = qr
        triggerRapidPoll()
        return true
    }

    fun cancelQrScan() {
        _pendingQrForNaming.value = null
    }

    fun confirmQrScan(
        qr: QrPayload,
        friendName: String,
    ) {
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        _pendingQrForNaming.value = null
        pollingStateInternal.update { it.copy(lastRapidPollTrigger = 0L) }
        val qrWithName = qr.copy(suggestedName = friendName)
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, _displayName.value.ifEmpty { "" })
                val sendToken = bobEntry.session.sendToken.toHex()
                Log.d(
                    TAG,
                    "confirmQrScan: processScannedQr succeeded, friendId=${bobEntry.id}, fingerprint=${bobEntry.id.take(
                        8,
                    )}, sendToken=$sendToken",
                )
                _friends.value = e2eeStore.listFriends()
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHex()
                    try {
                        Log.d(TAG, "confirmQrScan: posting KeyExchangeInit, discoveryHex=$discoveryHex")
                        E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                        Log.d(TAG, "confirmQrScan: mailbox post succeeded")
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                        updateStatus(e)
                        return@launch
                    }
                    locationClient.postOpkBundle(bobEntry.id)
                    if (_isSharingLocation.value) {
                        // Send our location directly to the new friend without going through the
                        // service. Using the same locationClient instance avoids ratchet divergence.
                        val loc = locationSource.lastLocation.value
                        if (loc != null) {
                            try {
                                Log.d(TAG, "confirmQrScan: force-sending location to ${bobEntry.id}")
                                locationClient.sendLocationToFriend(bobEntry.id, loc.first, loc.second)
                                pollingStateInternal.update { it.copy(lastSentLat = loc.first, lastSentLng = loc.second, lastSentTime = clock()) }
                            } catch (e: Exception) {
                                Log.e(TAG, "confirmQrScan: force send failed", e)
                                updateStatus(e)
                            }
                        } else {
                            // Location not available yet; wait inline so the outer finally
                            // covers this path and _isExchanging is always restored.
                            val deferred =
                                withTimeoutOrNull(30_000L) {
                                    locationSource.lastLocation.first { it != null }
                                }
                            if (deferred == null) {
                                Log.e(TAG, "confirmQrScan: timed out waiting for location")
                                updateStatus(Exception("Location unavailable for initial send"))
                            } else {
                                val (lat, lng) = deferred
                                try {
                                    Log.d(TAG, "confirmQrScan: deferred force-send to ${bobEntry.id}")
                                    locationClient.sendLocationToFriend(bobEntry.id, lat, lng)
                                    pollingStateInternal.update { it.copy(lastSentLat = lat, lastSentLng = lng, lastSentTime = clock()) }
                                } catch (e: Exception) {
                                    Log.e(TAG, "confirmQrScan: deferred force send failed", e)
                                    updateStatus(e)
                                }
                            }
                        }
                    }

                    _friends.value = e2eeStore.listFriends()
                    doPoll()
                } catch (e: Exception) {
                    Log.e(TAG, "confirmQrScan inner failure: ${e.message}")
                    updateStatus(e)
                } finally {
                    _isExchanging.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "confirmQrScan: processScannedQr failed", e)
                _isExchanging.value = false
            }
        }
    }

    fun confirmPendingInit(name: String) {
        val payload = _pendingInitPayload.value ?: return
        Log.d(TAG, "confirmPendingInit: name=$name")
        _pendingInitPayload.value = null
        val current = _inviteState.value
        _inviteState.value = InviteState.None
        pollingStateInternal.update { it.copy(lastRapidPollTrigger = 0L) }
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                if (current is InviteState.Pending) {
                    e2eeStore.clearInvite()
                }
                val entry = e2eeStore.processKeyExchangeInit(payload, name)
                if (entry != null) {
                    Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                    _friends.value = e2eeStore.listFriends()
                    try {
                        // Upload OPK bundle so Bob can decrypt our future location messages.
                        // (Bug 5: this was missing from confirmPendingInit, causing Alice's
                        // encrypted messages to be undecryptable until the next heartbeat.)
                        locationClient.postOpkBundle(entry.id)
                        if (_isSharingLocation.value) {
                            val loc = locationSource.lastLocation.value
                            if (loc != null) {
                                try {
                                    Log.d(TAG, "confirmPendingInit: force-sending location to ${entry.id}")
                                    locationClient.sendLocationToFriend(entry.id, loc.first, loc.second)
                                    pollingStateInternal.update {
                                        it.copy(
                                            lastSentLat = loc.first,
                                            lastSentLng = loc.second,
                                            lastSentTime = clock(),
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "confirmPendingInit: force send failed", e)
                                    updateStatus(e)
                                }
                            } else {
                                // Wait inline so the outer finally covers this path and
                                // _isExchanging is always restored.
                                val deferred =
                                    withTimeoutOrNull(30_000L) {
                                        locationSource.lastLocation.first { it != null }
                                    }
                                if (deferred == null) {
                                    Log.e(TAG, "confirmPendingInit: timed out waiting for location")
                                    updateStatus(Exception("Location unavailable for initial send"))
                                } else {
                                    val (lat, lng) = deferred
                                    try {
                                        Log.d(TAG, "confirmPendingInit: deferred force-send to ${entry.id}")
                                        locationClient.sendLocationToFriend(entry.id, lat, lng)
                                        pollingStateInternal.update { it.copy(lastSentLat = lat, lastSentLng = lng, lastSentTime = clock()) }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "confirmPendingInit: deferred force send failed", e)
                                        updateStatus(e)
                                    }
                                }
                            }
                        }
                        doPoll()
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmPendingInit: inner failure: ${e.message}")
                        updateStatus(e)
                    } finally {
                        _isExchanging.value = false
                    }
                } else {
                    Log.e(TAG, "confirmPendingInit: processKeyExchangeInit returned null")
                    _isExchanging.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "confirmPendingInit: processKeyExchangeInit failed", e)
                _isExchanging.value = false
            }
        }
    }

    fun cancelPendingInit() {
        if (_pendingInitPayload.value == null && _inviteState.value == InviteState.None) return
        viewModelScope.launch {
            e2eeStore.clearInvite()
        }
        _pendingInitPayload.value = null
        _inviteState.value = InviteState.None
    }

    private suspend fun pollLoop() {
        while (pollingStateInternal.value.isPolling) {
            val rapid = isRapidPolling()
            doPoll()
            // Heartbeat: ensure we send at least once every 5 minutes when stationary.
            locationSource.lastLocation.value?.let { (lat, lng) ->
                sendLocationIfNeeded(lat, lng, isHeartbeat = true)
            }
            val interval = if (rapid) 2_000L else 60_000L
            withTimeoutOrNull(interval) {
                pollWakeSignal.receive()
            }
        }
    }

    internal suspend fun doPoll() {
        try {
            Log.d(TAG, "Polling for location updates")
            val updates = locationClient.poll()
            Log.d(TAG, "Got ${updates.size} location updates")
            // Ensure all StateFlow writes go through the main dispatcher
            withContext(Dispatchers.Main) {
                for (update in updates) {
                    friendLocations.value += (update.userId to update)
                    val now = clock()
                    _friendLastPing.value += (update.userId to now)
                    e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, now / 1000L)
                }
                pollPendingInvite()
                _friends.value = e2eeStore.listFriends()
                updateStatus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    internal suspend fun pollPendingInvite() {
        val qr = e2eeStore.pendingQrPayload() ?: return
        try {
            val discoveryHex = qr.discoveryToken().toHex()
            Log.d(TAG, "pollPendingInvite: polling discoveryHex=$discoveryHex")
            val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, discoveryHex)
            if (messages.isNotEmpty()) {
                Log.d(TAG, "pollPendingInvite: got ${messages.size} messages")
            }
            updateStatus(null)
            val initPayload = messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull() ?: return

            Log.d(TAG, "pollPendingInvite: received KeyExchangeInit from ${initPayload.suggestedName}")
            val currentQr = (_inviteState.value as? InviteState.Pending)?.qr
            if (currentQr != null) {
                _inviteState.value = InviteState.Consumed(currentQr)
            }
            _pendingInitPayload.value = initPayload
        } catch (e: Exception) {
            updateStatus(e)
        }
    }

    // Sends our location to all non-paused friends, subject to throttling.
    // isHeartbeat=true uses a 5-minute minimum interval (called from poll loop);
    // isHeartbeat=false uses a 15-second minimum interval (called from location updates).
    internal suspend fun sendLocationIfNeeded(
        lat: Double,
        lng: Double,
        isHeartbeat: Boolean,
        force: Boolean = false,
    ) {
        if (!_isSharingLocation.value) return
        val now = clock()
        val state = pollingStateInternal.value
        val shouldSend =
            force || state.lastSentLat == null ||
                (!isHeartbeat && now - state.lastSentTime > 15_000L) ||
                (isHeartbeat && now - state.lastSentTime > 300_000L)
        if (!shouldSend) return
        try {
            locationClient.sendLocation(lat, lng, _pausedFriendIds.value)
            pollingStateInternal.update { it.copy(lastSentLat = lat, lastSentLng = lng, lastSentTime = now) }
            updateStatus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location: ${e.message}")
            updateStatus(e)
        }
    }

    private fun updateStatus(e: Throwable?) {
        if (e == null) {
            _connectionStatus.value = ConnectionStatus.Ok
        } else {
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
    }

    @MainThread
    private fun manageForegroundService(sharing: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val intent = Intent(getApplication(), LocationService::class.java)
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (sharing && hasLocationPermission) {
            getApplication<Application>().startForegroundService(intent)
        } else if (!sharing) {
            getApplication<Application>().stopService(intent)
        }
    }
}
