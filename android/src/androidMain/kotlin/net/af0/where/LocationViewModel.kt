package net.af0.where

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

sealed interface InviteState {
    data object None : InviteState

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

    val friendLocations: StateFlow<Map<String, UserLocation>> = locationSource.friendLocations
    val friendLastPing: StateFlow<Map<String, Long>> = locationSource.friendLastPing
    val connectionStatus: StateFlow<ConnectionStatus> = locationSource.connectionStatus

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming

    private val _pendingInviteQr = MutableStateFlow<QrPayload?>(null)
    val pendingInviteQr: StateFlow<QrPayload?> = _pendingInviteQr

    private val _isExchanging = MutableStateFlow(false)
    val isExchanging: StateFlow<Boolean> = _isExchanging

    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = locationSource.pendingInitPayload

    private val _inviteState = MutableStateFlow<InviteState>(InviteState.None)
    val inviteState: StateFlow<InviteState> = _inviteState

    init {
        viewModelScope.launch {
            combine(_pendingInviteQr, pendingInitPayload) { qr, init ->
                when {
                    qr != null && init != null -> InviteState.Consumed(qr)
                    qr != null -> InviteState.Pending(qr)
                    else -> InviteState.None
                }
            }.collect {
                _inviteState.value = it
            }
        }
    }

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
        viewModelScope.launch(Dispatchers.Main.immediate) {
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
            locationSource.setInitialFriendLocations(initialLocations, initialLastPing)
        }
        locationSource.setSharingLocation(_isSharingLocation.value)
        locationSource.setPausedFriends(_pausedFriendIds.value)

        viewModelScope.launch {
            var prevSharing = _isSharingLocation.value
            _isSharingLocation.collect { sharing ->
                locationSource.setSharingLocation(sharing)
                if (prevSharing != sharing) {
                    manageForegroundService(sharing)
                }
                prevSharing = sharing
            }
        }

        viewModelScope.launch {
            _pausedFriendIds.collect { ids ->
                locationSource.setPausedFriends(ids)
            }
        }

        // Always try to start service on init if sharing is on
        manageForegroundService(_isSharingLocation.value)
    }

    private fun triggerRapidPoll() {
        pollingStateInternal.update { it.copy(lastRapidPollTrigger = clock()) }
        pollWakeSignal.trySend(Unit)
    }

    fun isRapidPolling(): Boolean {
        val now = clock()
        val isPairing = _pendingInviteQr.value != null || pendingInitPayload.value != null || _pendingQrForNaming.value != null
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
        viewModelScope.launch {
            e2eeStore.deleteFriend(id)
            _friends.value = e2eeStore.listFriends()
            locationSource.onFriendRemoved(id)
            if (id in _pausedFriendIds.value) {
                val newPaused = _pausedFriendIds.value - id
                _pausedFriendIds.value = newPaused
                UserPrefs.setPausedFriends(getApplication(), newPaused)
            }
        }
    }

    fun createInvite() {
        viewModelScope.launch {
            val qr = e2eeStore.createInvite(_displayName.value.ifEmpty { "Me" })
            withContext(Dispatchers.Main.immediate) {
                _pendingInviteQr.value = qr
                triggerRapidPoll()
            }
        }
    }

    fun clearInvite() {
        viewModelScope.launch {
            e2eeStore.clearInvite()
            _pendingInviteQr.value = null
            locationSource.onPendingInit(null)
        }
    }

    fun processQrUrl(url: String): Boolean {
        Log.d(TAG, "processQrUrl: url=$url")
        val qr =
            QrUtils.urlToPayload(url) ?: run {
                Log.e(TAG, "processQrUrl: failed to parse URL")
                return false
            }
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
        _pendingQrForNaming.value = null
        pollingStateInternal.update { it.copy(lastRapidPollTrigger = 0L) }
        val qrWithName = qr.copy(suggestedName = friendName)
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, _displayName.value.ifEmpty { "" })
                _friends.value = e2eeStore.listFriends()
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHex()
                    try {
                        E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                        locationSource.onConnectionError(e)
                        return@launch
                    }
                    locationClient.postOpkBundle(bobEntry.id)
                    locationSource.lastLocation.value?.let { (lat, lng) ->
                        locationClient.sendLocationToFriend(bobEntry.id, lat, lng)
                    }
                    _friends.value = e2eeStore.listFriends()
                    // Poll immediately after pairing
                    val updates = locationClient.poll()
                    for (update in updates) {
                        e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, clock() / 1000L)
                    }
                    for (update in updates) {
                        locationSource.onFriendUpdate(update)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "confirmQrScan inner failure: ${e.message}")
                    locationSource.onConnectionError(e)
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
        val payload = pendingInitPayload.value ?: return
        locationSource.onPendingInit(null)
        _pendingInviteQr.value = null
        triggerRapidPoll()
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                val entry = e2eeStore.processKeyExchangeInit(payload, name)
                if (entry != null) {
                    _friends.value = e2eeStore.listFriends()
                    try {
                        locationSource.lastLocation.value?.let { (lat, lng) ->
                            locationClient.sendLocationToFriend(entry.id, lat, lng)
                        }
                        val updates = locationClient.poll()
                        for (update in updates) {
                            e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, clock() / 1000L)
                        }
                        for (update in updates) {
                            locationSource.onFriendUpdate(update)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmPendingInit inner failure: ${e.message}")
                    } finally {
                        _isExchanging.value = false
                    }
                } else {
                    _isExchanging.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "confirmPendingInit failure: ${e.message}")
                _isExchanging.value = false
            }
        }
    }

    fun cancelPendingInit() {
        if (pendingInitPayload.value == null && _pendingInviteQr.value == null) return
        viewModelScope.launch {
            e2eeStore.clearInvite()
            locationSource.onPendingInit(null)
            _pendingInviteQr.value = null
        }
    }

    suspend fun pollPendingInvite() {
        val qr = _pendingInviteQr.value ?: return
        try {
            val discoveryHex = qr.discoveryToken().toHex()
            val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, discoveryHex)
            val initPayload = messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull()
            if (initPayload != null) {
                locationSource.onPendingInit(initPayload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollPendingInvite failure", e)
            locationSource.onConnectionError(e)
        }
    }

    suspend fun doPoll() {
        try {
            val updates = locationClient.poll()
            for (update in updates) {
                e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, clock() / 1000L)
            }
            for (update in updates) {
                locationSource.onFriendUpdate(update)
            }
            locationSource.onConnectionStatus(ConnectionStatus.Ok)
        } catch (e: Exception) {
            Log.e(TAG, "doPoll failure", e)
            locationSource.onConnectionError(e)
        }
    }

    suspend fun sendLocationIfNeeded(
        lat: Double,
        lng: Double,
        isHeartbeat: Boolean,
        force: Boolean = false,
    ) {
        if (!_isSharingLocation.value) return

        val now = clock()
        val lastSentTime = pollingStateInternal.value.lastSentTime
        val lastSentLat = pollingStateInternal.value.lastSentLat
        val lastSentLng = pollingStateInternal.value.lastSentLng

        val distance =
            if (lastSentLat != null && lastSentLng != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(lastSentLat, lastSentLng, lat, lng, results)
                results[0]
            } else {
                Float.MAX_VALUE
            }

        val throttleMs = if (isHeartbeat) 300_000L else 15_000L
        val shouldSend =
            force || (lastSentLat == null) || (distance > 10.0 && now - lastSentTime > throttleMs) || (now - lastSentTime > 300_000L)

        if (shouldSend) {
            try {
                locationClient.sendLocation(lat, lng, _pausedFriendIds.value)
                pollingStateInternal.update {
                    it.copy(
                        lastSentLat = lat,
                        lastSentLng = lng,
                        lastSentTime = now,
                    )
                }
                locationSource.onConnectionStatus(ConnectionStatus.Ok)
            } catch (e: Exception) {
                Log.e(TAG, "sendLocationIfNeeded failure", e)
                locationSource.onConnectionError(e)
            }
        }
    }

    fun stopPolling() {
        // No-op for now, managed by scope
    }

    private fun manageForegroundService(sharing: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val intent = Intent(getApplication(), LocationService::class.java)
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (sharing && hasLocationPermission) {
            getApplication<Application>().startForegroundService(intent)
        } else if (!sharing) {
            getApplication<Application>().stopService(intent)
        }
    }

    companion object {
        val Factory: androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T {
                    val application =
                        checkNotNull(extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    return LocationViewModel(application) as T
                }
            }
    }
}
