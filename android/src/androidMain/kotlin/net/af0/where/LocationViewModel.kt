package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import net.af0.where.e2ee.EncryptedLocationPayload
import net.af0.where.e2ee.EpochRotationPayload
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationPlaintext
import net.af0.where.e2ee.PreKeyBundlePayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.RatchetAckPayload
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.discoveryToken
import net.af0.where.e2ee.toHex
import net.af0.where.e2ee.LocationClient
import net.af0.where.model.UserLocation

private const val TAG = "LocationViewModel"

sealed class ConnectionStatus {
    object Ok : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

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

    private val friendLocations = MutableStateFlow(emptyMap<String, UserLocation>())
    private val _friendLastPing = MutableStateFlow(emptyMap<String, Long>())
    val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing

    private val _pendingInviteQr = MutableStateFlow<QrPayload?>(null)
    val pendingInviteQr: StateFlow<QrPayload?> = _pendingInviteQr

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload

    private val _isExchanging = MutableStateFlow(false)
    val isExchanging: StateFlow<Boolean> = _isExchanging

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

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
        friendLocations.value = initialLocations
        _friendLastPing.value = initialLastPing

        viewModelScope.launch { pollLoop() }
        viewModelScope.launch {
            var prevSharing = _isSharingLocation.value
            combine(locationSource.lastLocation, _isSharingLocation) { loc, sharing ->
                loc to sharing
            }.collect { (loc, sharing) ->
                if (loc != null && sharing) {
                    sendEncryptedLocation(loc.first, loc.second)
                }
                if (prevSharing != sharing) {
                    manageForegroundService(sharing)
                }
                prevSharing = sharing
            }
        }
    }

    private fun triggerRapidPoll() {
        lastRapidPollTrigger = System.currentTimeMillis()
    }

    private fun isRapidPolling(): Boolean {
        val now = System.currentTimeMillis()
        // Alice is pairing if she has a pending invite QR or Bob's KeyExchangeInit waiting for naming
        // Bob is pairing if he has scanned a QR but not yet named the friend
        val isPairing = _pendingInviteQr.value != null || _pendingInitPayload.value != null || _pendingQrForNaming.value != null
        // Bob is pairing if he recently scanned a QR (within 5 minutes)
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

    fun renameFriend(id: String, newName: String) {
        e2eeStore.renameFriend(id, newName)
        _friends.value = e2eeStore.listFriends()
    }

    fun removeFriend(id: String) {
        e2eeStore.deleteFriend(id)
        _friends.value = e2eeStore.listFriends()
        friendLocations.value -= id
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
        viewModelScope.launch {
            pollPendingInvite()
        }
    }

    fun clearInvite() {
        if (!autoClearedInvite) {
            e2eeStore.clearInvite()
        }
        _pendingInviteQr.value = null
        autoClearedInvite = false
    }

    /** Bob: parse scanned URL, but wait for user to name the friend. */
    fun processQrUrl(url: String): Boolean {
        Log.d(TAG, "processQrUrl: url=$url")
        val qr = QrUtils.urlToPayload(url) ?: run {
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

    fun confirmQrScan(qr: QrPayload, friendName: String) {
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        _pendingQrForNaming.value = null
        triggerRapidPoll()
        val qrWithName = qr.copy(suggestedName = friendName)
        try {
            val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, _displayName.value.ifEmpty { "" })
            val sendToken = bobEntry.session.sendToken.toHex()
            Log.d(TAG, "confirmQrScan: processScannedQr succeeded, friendId=${bobEntry.id}, fingerprint=${bobEntry.id.take(8)}, sendToken=$sendToken")
            _friends.value = e2eeStore.listFriends()
            _isExchanging.value = true
            viewModelScope.launch {
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHex()
                    try {
                        Log.d(TAG, "confirmQrScan: posting KeyExchangeInit, discoveryHex=$discoveryHex")
                        E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                        Log.d(TAG, "confirmQrScan: mailbox post succeeded")
                        // Small delay to ensure Alice's poll sees it
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                        updateStatus(e)
                        _isExchanging.value = false
                        return@launch  // Alice never got the KeyExchangeInit; don't post OPKs or location
                    }
                    locationClient.postOpkBundle(bobEntry.id)
                    // Trigger immediate location sync so Alice sees us right away
                    locationSource.lastLocation.value?.let { (lat, lng) ->
                        locationClient.sendLocationToFriend(bobEntry.id, lat, lng)
                    }
                    _friends.value = e2eeStore.listFriends() // Refresh again to be sure
                    doPoll()
                } catch (e: Exception) {
                    Log.e(TAG, "confirmQrScan inner failure: ${e.message}")
                    updateStatus(e)
                } finally {
                    _isExchanging.value = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirmQrScan: processScannedQr failed", e)
            _isExchanging.value = false
        }
    }

    fun confirmPendingInit(name: String) {
        val payload = _pendingInitPayload.value ?: return
        Log.d(TAG, "confirmPendingInit: name=$name")
        _pendingInitPayload.value = null
        _pendingInviteQr.value = null
        if (!autoClearedInvite) e2eeStore.clearInvite()
        autoClearedInvite = false
        triggerRapidPoll()
        _isExchanging.value = true
        try {
            val entry = e2eeStore.processKeyExchangeInit(payload, name)
            if (entry != null) {
                Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                _friends.value = e2eeStore.listFriends()
                Log.d(TAG, "confirmPendingInit: friend list now has ${_friends.value.size} items")
                // Alice sends her location immediately after saving Bob
                viewModelScope.launch {
                    try {
                        locationSource.lastLocation.value?.let { (lat, lng) ->
                            locationClient.sendLocationToFriend(entry.id, lat, lng)
                        }
                        doPoll()
                    } finally {
                        _isExchanging.value = false
                    }
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

    fun cancelPendingInit() {
        // Guard: only act if Alice's invite flow is actually active.
        if (_pendingInitPayload.value == null && _pendingInviteQr.value == null) return
        if (!autoClearedInvite) e2eeStore.clearInvite()
        autoClearedInvite = false
        _pendingInitPayload.value = null
        _pendingInviteQr.value = null
    }

    private suspend fun pollLoop() {
        while (true) {
            val rapid = isRapidPolling()
            doPoll()
            // Poll every 2 seconds while pairing, 60s otherwise
            val interval = if (rapid) 2_000L else 60_000L
            
            // Check for rapid polling changes every 500ms to be more responsive
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < interval) {
                delay(500)
                if (!rapid && isRapidPolling()) break // Transition to rapid polling immediately
            }
        }
    }

    private suspend fun doPoll() {
        try {
            Log.d(TAG, "Polling for location updates")
            val updates = locationClient.poll()
            Log.d(TAG, "Got ${updates.size} location updates")
            for (update in updates) {
                friendLocations.value += (update.userId to update)
                val now = System.currentTimeMillis()
                _friendLastPing.value += (update.userId to now)
                e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, now / 1000L)
            }
            pollPendingInvite()
            // Ensure friends list is up to date in case it changed elsewhere
            _friends.value = e2eeStore.listFriends()
            updateStatus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    private suspend fun pollPendingInvite() {
        val qr = e2eeStore.pendingQrPayload ?: return
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
            // Found init payload! Show naming dialog instead of processing immediately.
            // Set _pendingInitPayload before clearing _pendingInviteQr so isRapidPolling()
            // never sees both as null between the two assignments.
            autoClearedInvite = true
            _pendingInitPayload.value = initPayload
            _pendingInviteQr.value = null
        } catch (e: Exception) {
            updateStatus(e)
        }
    }

    private suspend fun sendEncryptedLocation(
        lat: Double,
        lng: Double,
    ) {
        try {
            Log.d(TAG, "Sending location: $lat, $lng to server: ${BuildConfig.SERVER_HTTP_URL}")
            locationClient.sendLocation(lat, lng, _pausedFriendIds.value)
            Log.d(TAG, "Location sent successfully")
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
            val msg = when {
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "not resolved"
                e.message?.contains("timeout", ignoreCase = true) == true -> "timeout"
                e.message?.contains("ConnectException", ignoreCase = true) == true -> "no connection"
                e.message?.contains("Failed to post to mailbox: 500", ignoreCase = true) == true -> "server error 500"
                else -> e.message?.take(32) ?: "unknown error"
            }
            _connectionStatus.value = ConnectionStatus.Error(msg)
        }
    }

    private fun manageForegroundService(sharing: Boolean) {
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
