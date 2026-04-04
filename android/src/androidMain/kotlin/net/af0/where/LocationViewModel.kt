package net.af0.where

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

class LocationViewModel(app: Application) : AndroidViewModel(app) {
    private val locationSource: LocationSource = LocationRepository
    private val e2eeStore = E2eeStore(SharedPrefsE2eeStorage(app))
    private val locationClient = LocationClient(BuildConfig.SERVER_HTTP_URL, e2eeStore)

    private val sharingPrefs = app.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)

    val userId: String by lazy {
        sharingPrefs.getString("user_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            sharingPrefs.edit().putString("user_id", id).apply()
            id
        }
    }

    private val _isSharingLocation = MutableStateFlow(sharingPrefs.getBoolean("is_sharing", true))
    val isSharingLocation: StateFlow<Boolean> = _isSharingLocation

    private val _displayName = MutableStateFlow(sharingPrefs.getString("display_name", "") ?: "")
    val displayName: StateFlow<String> = _displayName

    private val _friends = MutableStateFlow(e2eeStore.listFriends())
    val friends: StateFlow<List<FriendEntry>> = _friends

    private val _pausedFriendIds = MutableStateFlow(
        sharingPrefs.getString("paused_friends", "")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    )
    val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds

    val friendLocations: StateFlow<Map<String, UserLocation>> = locationSource.friendLocations
    val friendLastPing: StateFlow<Map<String, Long>> = locationSource.friendLastPing
    val connectionStatus: StateFlow<ConnectionStatus> = locationSource.connectionStatus

    private val _pendingInviteQr = MutableStateFlow<QrPayload?>(null)
    val pendingInviteQr: StateFlow<QrPayload?> = _pendingInviteQr

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming

    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = locationSource.pendingInitPayload

    private var lastRapidPollTrigger = 0L
    private var autoClearedInvite = false

    val visibleUsers: StateFlow<List<UserLocation>> =
        combine(locationSource.lastLocation, _isSharingLocation, friendLocations) { myLoc, sharing, friendLocs ->
            buildList {
                if (myLoc != null && sharing) {
                    add(UserLocation(userId, myLoc.first, myLoc.second, System.currentTimeMillis() / 1000))
                }
                addAll(friendLocs.values)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        Log.d(TAG, "LocationViewModel init: server=${BuildConfig.SERVER_HTTP_URL}, userId=$userId")
        val savedFriends = e2eeStore.listFriends()
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
        LocationRepository.setInitialFriendLocations(initialLocations, initialLastPing)
        LocationRepository.setSharingLocation(_isSharingLocation.value)
        LocationRepository.setPausedFriends(_pausedFriendIds.value)

        viewModelScope.launch {
            var prevSharing = _isSharingLocation.value
            _isSharingLocation.collect { sharing ->
                LocationRepository.setSharingLocation(sharing)
                if (prevSharing != sharing) {
                    manageForegroundService(sharing)
                }
                prevSharing = sharing
            }
        }

        viewModelScope.launch {
            _pausedFriendIds.collect { ids ->
                LocationRepository.setPausedFriends(ids)
            }
        }

        viewModelScope.launch {
            pendingInitPayload.collect { payload ->
                if (payload != null && _pendingInviteQr.value != null) {
                    autoClearedInvite = true
                    _pendingInviteQr.value = null
                }
            }
        }

        // Always try to start service on init if sharing is on
        manageForegroundService(_isSharingLocation.value)
    }

    private fun triggerRapidPoll() {
        lastRapidPollTrigger = System.currentTimeMillis()
    }

    private fun isRapidPolling(): Boolean {
        val now = System.currentTimeMillis()
        val isPairing = _pendingInviteQr.value != null || pendingInitPayload.value != null || _pendingQrForNaming.value != null
        val recentlyTriggered = now - lastRapidPollTrigger < 5 * 60_000L
        return isPairing || recentlyTriggered
    }

    fun setDisplayName(name: String) {
        _displayName.value = name
        sharingPrefs.edit().putString("display_name", name).apply()
    }

    fun toggleSharing() {
        val new = !_isSharingLocation.value
        _isSharingLocation.value = new
        sharingPrefs.edit().putBoolean("is_sharing", new).apply()
    }

    fun togglePauseFriend(id: String) {
        val current = _pausedFriendIds.value
        val new = if (id in current) current - id else current + id
        _pausedFriendIds.value = new
        sharingPrefs.edit().putString("paused_friends", new.joinToString(",")).apply()
    }

    fun removeFriend(id: String) {
        e2eeStore.deleteFriend(id)
        _friends.value = e2eeStore.listFriends()
        LocationRepository.onFriendRemoved(id)
        if (id in _pausedFriendIds.value) {
            val newPaused = _pausedFriendIds.value - id
            _pausedFriendIds.value = newPaused
            sharingPrefs.edit().putString("paused_friends", newPaused.joinToString(",")).apply()
        }
    }

    fun createInvite() {
        autoClearedInvite = false
        _pendingInviteQr.value = e2eeStore.createInvite(_displayName.value.ifEmpty { "Me" })
        triggerRapidPoll()
    }

    fun clearInvite() {
        if (!autoClearedInvite) {
            e2eeStore.clearInvite()
        }
        _pendingInviteQr.value = null
        autoClearedInvite = false
        LocationRepository.onPendingInit(null)
    }

    fun processQrUrl(url: String): Boolean {
        Log.d(TAG, "processQrUrl: url=$url")
        val qr = QrUtils.urlToPayload(url) ?: run {
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

    fun confirmQrScan(qr: QrPayload, friendName: String) {
        _pendingQrForNaming.value = null
        triggerRapidPoll()
        val qrWithName = qr.copy(suggestedName = friendName)
        try {
            val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, _displayName.value.ifEmpty { "" })
            _friends.value = e2eeStore.listFriends()
            viewModelScope.launch {
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHex()
                    try {
                        E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                        LocationRepository.onConnectionError(e)
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
                        e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, System.currentTimeMillis() / 1000L)
                        LocationRepository.onFriendUpdate(update)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "confirmQrScan inner failure: ${e.message}")
                    LocationRepository.onConnectionError(e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirmQrScan: processScannedQr failed", e)
        }
    }

    fun confirmPendingInit(name: String) {
        val payload = pendingInitPayload.value ?: return
        LocationRepository.onPendingInit(null)
        _pendingInviteQr.value = null
        if (!autoClearedInvite) e2eeStore.clearInvite()
        autoClearedInvite = false
        triggerRapidPoll()
        try {
            val entry = e2eeStore.processKeyExchangeInit(payload, name)
            if (entry != null) {
                _friends.value = e2eeStore.listFriends()
                viewModelScope.launch {
                    locationSource.lastLocation.value?.let { (lat, lng) ->
                        locationClient.sendLocationToFriend(entry.id, lat, lng)
                    }
                    val updates = locationClient.poll()
                    for (update in updates) {
                        e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, System.currentTimeMillis() / 1000L)
                        LocationRepository.onFriendUpdate(update)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirmPendingInit: processKeyExchangeInit failed", e)
        }
    }

    fun cancelPendingInit() {
        if (pendingInitPayload.value == null && _pendingInviteQr.value == null) return
        if (!autoClearedInvite) e2eeStore.clearInvite()
        autoClearedInvite = false
        LocationRepository.onPendingInit(null)
        _pendingInviteQr.value = null
    }

    private fun manageForegroundService(sharing: Boolean) {
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
}
