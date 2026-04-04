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

class LocationViewModel(
    app: Application,
    e2eeStore: E2eeStore? = null,
    locationClient: LocationClient? = null,
    startPolling: Boolean = true,
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

    private val locationSource: LocationSource = LocationRepository

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

    private val _friends = MutableStateFlow(this.e2eeStore.listFriends())
    val friends: StateFlow<List<FriendEntry>> = _friends

    private val _pausedFriendIds =
        MutableStateFlow(
            sharingPrefs.getString("paused_friends", "")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
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
    private var isPolling = true

    // Throttle state for outbound location broadcasts.
    private var lastSentLat: Double? = null
    private var lastSentLng: Double? = null
    private var lastSentTime: Long = 0L

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
        val savedFriends = this.e2eeStore.listFriends()
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
        isPolling = false
    }

    private fun triggerRapidPoll() {
        lastRapidPollTrigger = System.currentTimeMillis()
    }

    private fun isRapidPolling(): Boolean {
        val now = System.currentTimeMillis()
        val isPairing = _pendingInviteQr.value != null || _pendingInitPayload.value != null || _pendingQrForNaming.value != null
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

    fun renameFriend(
        id: String,
        newName: String,
    ) {
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
        lastRapidPollTrigger = 0L
        val qrWithName = qr.copy(suggestedName = friendName)
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
            _isExchanging.value = true
            viewModelScope.launch {
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
                        _isExchanging.value = false
                        return@launch
                    }
                    locationClient.postOpkBundle(bobEntry.id)
                    if (_isSharingLocation.value) {
                        // Send our location directly to the new friend without going through the
                        // service. Using the same locationClient instance avoids ratchet divergence.
                        locationSource.lastLocation.value?.let { (lat, lng) ->
                            try {
                                Log.d(TAG, "confirmQrScan: force-sending location to ${bobEntry.id}")
                                locationClient.sendLocationToFriend(bobEntry.id, lat, lng)
                                lastSentLat = lat
                                lastSentLng = lng
                                lastSentTime = System.currentTimeMillis()
                            } catch (e: Exception) {
                                Log.e(TAG, "confirmQrScan: force send failed", e)
                                updateStatus(e)
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
        lastRapidPollTrigger = 0L
        _isExchanging.value = true
        try {
            val entry = e2eeStore.processKeyExchangeInit(payload, name)
            if (entry != null) {
                Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                _friends.value = e2eeStore.listFriends()
                viewModelScope.launch {
                    try {
                        // Upload OPK bundle so Bob can decrypt our future location messages.
                        // (Bug 5: this was missing from confirmPendingInit, causing Alice's
                        // encrypted messages to be undecryptable until the next heartbeat.)
                        locationClient.postOpkBundle(entry.id)
                        if (_isSharingLocation.value) {
                            locationSource.lastLocation.value?.let { (lat, lng) ->
                                try {
                                    Log.d(TAG, "confirmPendingInit: force-sending location to ${entry.id}")
                                    locationClient.sendLocationToFriend(entry.id, lat, lng)
                                    lastSentLat = lat
                                    lastSentLng = lng
                                    lastSentTime = System.currentTimeMillis()
                                } catch (e: Exception) {
                                    Log.e(TAG, "confirmPendingInit: force send failed", e)
                                    updateStatus(e)
                                }
                            }
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
        if (_pendingInitPayload.value == null && _pendingInviteQr.value == null) return
        e2eeStore.clearInvite()
        autoClearedInvite = false
        _pendingInitPayload.value = null
        _pendingInviteQr.value = null
    }

    private suspend fun pollLoop() {
        while (isPolling) {
            val rapid = isRapidPolling()
            doPoll()
            // Heartbeat: send location to all friends every 5 minutes even without movement.
            locationSource.lastLocation.value?.let { (lat, lng) ->
                sendLocationIfNeeded(lat, lng, isHeartbeat = true)
            }
            val interval = if (rapid) 2_000L else 60_000L
            val steps = (interval / 500L).toInt()
            for (i in 0 until steps) {
                if (!isPolling) break
                delay(500)
                if (!rapid && isRapidPolling()) break
            }
        }
    }

    internal suspend fun doPoll() {
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
            _friends.value = e2eeStore.listFriends()
            updateStatus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    internal suspend fun pollPendingInvite() {
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
            autoClearedInvite = true
            _pendingInitPayload.value = initPayload
            _pendingInviteQr.value = null
        } catch (e: Exception) {
            updateStatus(e)
        }
    }

    // Sends our location to all non-paused friends, subject to throttling.
    // isHeartbeat=true uses a 5-minute minimum interval (called from poll loop);
    // isHeartbeat=false uses a 15-second minimum interval (called from location updates).
    private suspend fun sendLocationIfNeeded(
        lat: Double,
        lng: Double,
        isHeartbeat: Boolean,
        force: Boolean = false,
    ) {
        if (!_isSharingLocation.value) return
        val now = System.currentTimeMillis()
        val shouldSend =
            force || lastSentLat == null ||
                (!isHeartbeat && now - lastSentTime > 15_000L) ||
                (isHeartbeat && now - lastSentTime > 300_000L)
        if (!shouldSend) return
        try {
            locationClient.sendLocation(lat, lng, _pausedFriendIds.value)
            lastSentLat = lat
            lastSentLng = lng
            lastSentTime = now
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
