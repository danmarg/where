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

sealed interface InviteState {
    object None : InviteState

    data class Pending(val qr: QrPayload) : InviteState

    data class Consumed(val qr: QrPayload) : InviteState
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

    val userId: String by lazy { UserPrefs.getUserId(getApplication()) }

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

    val visibleUsers: StateFlow<List<UserLocation>> =
        combine(locationSource.lastLocation, isSharingLocation, friendLocations) { myLoc, sharing, friendLocs ->
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
    }

    fun stopPolling() {
        // Core polling logic has been moved to LocationService.
    }

    private fun triggerRapidPoll() {
        locationSource.triggerRapidPoll()
    }

    fun setDisplayName(name: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setDisplayName must be called on the main thread" }
        _displayName.value = name
        UserPrefs.setDisplayName(getApplication(), name)
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
                locationSource.onFriendsUpdated(e2eeStore.listFriends())
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
                        // Send our location directly to the new friend without going through the
                        // service. Using the same locationClient instance avoids ratchet divergence.
                        val loc = locationSource.lastLocation.value
                        if (loc != null) {
                            try {
                                Log.d(TAG, "confirmQrScan: force-sending location to ${bobEntry.id}")
                                locationClient.sendLocationToFriend(bobEntry.id, loc.first, loc.second)
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
                                } catch (e: Exception) {
                                    Log.e(TAG, "confirmQrScan: deferred force send failed", e)
                                    updateStatus(e)
                                }
                            }
                        }
                    }

                    locationSource.onFriendsUpdated(e2eeStore.listFriends())
                    triggerRapidPoll()
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
        val current = _inviteState.value
        _inviteState.value = InviteState.None
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                if (current is InviteState.Pending) {
                    e2eeStore.clearInvite()
                }
                val entry = e2eeStore.processKeyExchangeInit(payload, name)
                if (entry != null) {
                    Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                    locationSource.onFriendsUpdated(e2eeStore.listFriends())
                    triggerRapidPoll()
                    try {
                        // Upload OPK bundle so Bob can decrypt our future location messages.
                        locationClient.postOpkBundle(entry.id)
                        Log.d(TAG, "confirmPendingInit: postOpkBundle succeeded")
                        if (isSharingLocation.value) {
                            val loc = locationSource.lastLocation.value
                            if (loc != null) {
                                try {
                                    Log.d(TAG, "confirmPendingInit: force-sending location to ${entry.id}: lat=${loc.first}, lng=${loc.second}")
                                    locationClient.sendLocationToFriend(entry.id, loc.first, loc.second)
                                    Log.d(TAG, "confirmPendingInit: sendLocationToFriend succeeded")
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
                                        Log.d(TAG, "confirmPendingInit: deferred force-send to ${entry.id}: lat=$lat, lng=$lng")
                                        locationClient.sendLocationToFriend(entry.id, lat, lng)
                                        Log.d(TAG, "confirmPendingInit: deferred sendLocationToFriend succeeded")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "confirmPendingInit: deferred force send failed", e)
                                        updateStatus(e)
                                    }
                                }
                            }
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
