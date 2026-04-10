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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    object None : InviteState

    data class Pending(val qr: QrPayload) : InviteState
}

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

    val isSharingLocation: StateFlow<Boolean> = locationSource.isSharingLocation

    private val _displayName = MutableStateFlow(UserPrefs.getDisplayName(app))
    val displayName: StateFlow<String> = _displayName

    val friends: StateFlow<List<FriendEntry>> = locationSource.friends

    val pausedFriendIds: StateFlow<Set<String>> = locationSource.pausedFriendIds

    val friendLocations: StateFlow<Map<String, UserLocation>> = locationSource.friendLocations
    val friendLastPing: StateFlow<Map<String, Long>> = locationSource.friendLastPing

    private val _inviteState = MutableStateFlow<InviteState>(InviteState.None)
    val inviteState: StateFlow<InviteState> = _inviteState

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming

    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = locationSource.pendingInitPayload

    private val _isExchanging = MutableStateFlow(false)
    val isExchanging: StateFlow<Boolean> = _isExchanging

    val connectionStatus: StateFlow<ConnectionStatus> = locationSource.connectionStatus

    val ownLocation: StateFlow<UserLocation?> =
        combine(locationSource.lastLocation, isSharingLocation) { myLoc, sharing ->
            if (myLoc != null && sharing) {
                UserLocation("", myLoc.first, myLoc.second, clock() / 1000)
            } else {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val visibleUsers: StateFlow<List<UserLocation>> =
        friendLocations
            .map { it.values.toList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        Log.d(TAG, "LocationViewModel init: server=${BuildConfig.SERVER_HTTP_URL}")
        viewModelScope.launch {
            val savedFriends = this@LocationViewModel.e2eeStore.listFriends()
            locationSource.onFriendsUpdated(savedFriends)
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
            locationSource.setSharingLocation(UserPrefs.isSharing(app))
            locationSource.setPausedFriends(UserPrefs.getPausedFriends(app))
        }

        viewModelScope.launch {
            isSharingLocation.collect { sharing ->
                manageForegroundService(sharing)
            }
        }

        // When a friend response (init payload) arrives from the service, flip the invite
        // state to None so the UI shows the naming dialog (dismissing the QR sheet).
        viewModelScope.launch {
            pendingInitPayload.collect { payload ->
                if (payload != null) {
                    val current = _inviteState.value
                    if (current is InviteState.Pending) {
                        _inviteState.value = InviteState.None
                    }
                }
            }
        }
    }

    private fun triggerRapidPoll() {
        locationSource.triggerRapidPoll()
    }

    fun setDisplayName(name: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setDisplayName must be called on the main thread" }
        _displayName.value = name
        UserPrefs.setDisplayName(getApplication(), name)
        // If an invite is active, we should update it.
        if (_inviteState.value is InviteState.Pending) {
            createInvite()
        }
    }

    fun toggleSharing() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "toggleSharing must be called on the main thread" }
        val new = !isSharingLocation.value
        locationSource.setSharingLocation(new)
        UserPrefs.setSharing(getApplication(), new)
    }

    fun togglePauseFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "togglePauseFriend must be called on the main thread" }
        val current = pausedFriendIds.value
        val new = if (id in current) current - id else current + id
        locationSource.setPausedFriends(new)
        UserPrefs.setPausedFriends(getApplication(), new)
    }

    fun renameFriend(
        id: String,
        newName: String,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "renameFriend must be called on the main thread" }
        viewModelScope.launch {
            e2eeStore.renameFriend(id, newName)
            locationSource.onFriendsUpdated(e2eeStore.listFriends())
        }
    }

    fun removeFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "removeFriend must be called on the main thread" }
        viewModelScope.launch {
            e2eeStore.deleteFriend(id)
            val updatedFriends = e2eeStore.listFriends()
            withContext(Dispatchers.Main.immediate) {
                val currentPaused = pausedFriendIds.value
                val newPaused = if (id in currentPaused) currentPaused - id else null

                if (newPaused != null) {
                    locationSource.setPausedFriends(newPaused)
                    UserPrefs.setPausedFriends(getApplication(), newPaused)
                }
                locationSource.onFriendRemoved(id)
                locationSource.onFriendsUpdated(updatedFriends)
            }
        }
    }

    fun createInvite() {
        viewModelScope.launch {
            val qr = e2eeStore.createInvite(_displayName.value.ifEmpty { "Me" })
            _inviteState.value = InviteState.Pending(qr)
            triggerRapidPoll()
        }
    }

    fun clearInvite() {
        val current = _inviteState.value
        // If a peer already joined (pendingInitPayload is not null), do NOT clear the
        // persistent invite state yet, as we still need it to derive the session.
        if (current is InviteState.Pending && locationSource.pendingInitPayload.value == null) {
            viewModelScope.launch {
                e2eeStore.clearInvite()
            }
        }
        locationSource.resetRapidPoll()
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
        if (locationSource is LocationRepository) locationSource.onPendingQrForNaming(qr)
        triggerRapidPoll()
        return true
    }

    fun cancelQrScan() {
        _pendingQrForNaming.value = null
        if (locationSource is LocationRepository) locationSource.onPendingQrForNaming(null)
        locationSource.resetRapidPoll()
    }

    fun confirmQrScan(
        qr: QrPayload,
        friendName: String,
    ) {
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        _pendingQrForNaming.value = null
        if (locationSource is LocationRepository) locationSource.onPendingQrForNaming(null)
        val qrWithName = qr.copy(suggestedName = friendName)
        val currentInvite = _inviteState.value
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                if (currentInvite != InviteState.None) {
                    e2eeStore.clearInvite()
                    _inviteState.value = InviteState.None
                }
                val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, _displayName.value.ifEmpty { "" })
                val sendToken = bobEntry.session.sendToken.toHex()
                Log.d(
                    TAG,
                    "confirmQrScan: processScannedQr succeeded, friendId=${bobEntry.id}, fingerprint=${bobEntry.id.take(
                        8,
                    )}, sendToken=$sendToken",
                )
                withContext(Dispatchers.Main.immediate) {
                    locationSource.onFriendsUpdated(e2eeStore.listFriends())
                }
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHex()
                    try {
                        Log.d(TAG, "confirmQrScan: posting KeyExchangeInit, discoveryHex=$discoveryHex")
                        E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                        Log.d(TAG, "confirmQrScan: mailbox post succeeded")
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                        updateStatus(e)
                        return@launch
                    }
                    locationClient.postOpkBundle(bobEntry.id)
                    if (isSharingLocation.value) {
                        val intent =
                            Intent(getApplication(), LocationService::class.java).apply {
                                action = LocationService.ACTION_FORCE_PUBLISH
                                putExtra(LocationService.EXTRA_FRIEND_ID, bobEntry.id)
                            }
                        getApplication<Application>().startForegroundService(intent)
                    }

                    withContext(Dispatchers.Main.immediate) {
                        locationSource.onFriendsUpdated(e2eeStore.listFriends())
                    }
                    locationSource.markAwaitingFirstUpdate(bobEntry.id)
                    locationSource.triggerRapidPoll()
                    locationSource.wakePoll()
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
        val payload = pendingInitPayload.value ?: return
        Log.d(TAG, "confirmPendingInit: name=$name")
        locationSource.onPendingInit(null)
        _inviteState.value = InviteState.None
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                val entry = e2eeStore.processKeyExchangeInit(payload, name)
                if (entry != null) {
                    Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                    withContext(Dispatchers.Main.immediate) {
                        locationSource.onFriendsUpdated(e2eeStore.listFriends())
                    }
                    locationSource.markAwaitingFirstUpdate(entry.id)
                    locationSource.triggerRapidPoll()
                    locationSource.wakePoll()
                    try {
                        // Upload OPK bundle so Bob can decrypt our future location messages.
                        locationClient.postOpkBundle(entry.id)
                        Log.d(TAG, "confirmPendingInit: postOpkBundle succeeded")
                        if (isSharingLocation.value) {
                            val intent =
                                Intent(getApplication(), LocationService::class.java).apply {
                                    action = LocationService.ACTION_FORCE_PUBLISH
                                    putExtra(LocationService.EXTRA_FRIEND_ID, entry.id)
                                }
                            getApplication<Application>().startForegroundService(intent)
                        }
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
        if (pendingInitPayload.value == null && _inviteState.value == InviteState.None) return
        viewModelScope.launch {
            e2eeStore.clearInvite()
        }
        locationSource.onPendingInit(null)
        locationSource.resetRapidPoll()
        _inviteState.value = InviteState.None
    }

    private fun updateStatus(e: Throwable?) {
        if (e == null) {
            locationSource.onConnectionStatus(ConnectionStatus.Ok)
        } else {
            locationSource.onConnectionError(e)
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
