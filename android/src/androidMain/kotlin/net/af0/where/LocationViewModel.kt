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

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

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
        friendLocations.value -= id
        if (id in _pausedFriendIds.value) {
            val newPaused = _pausedFriendIds.value - id
            _pausedFriendIds.value = newPaused
            sharingPrefs.edit().putString("paused_friends", newPaused.joinToString(",")).apply()
        }
    }

    fun createInvite() {
        _pendingInviteQr.value = e2eeStore.createInvite(_displayName.value.ifEmpty { "Me" })
    }

    fun clearInvite() {
        e2eeStore.clearInvite()
        _pendingInviteQr.value = null
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
        return true
    }

    fun cancelQrScan() {
        _pendingQrForNaming.value = null
    }

    fun confirmQrScan(qr: QrPayload, friendName: String) {
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        _pendingQrForNaming.value = null
        val qrWithName = qr.copy(suggestedName = friendName)
        try {
            val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, _displayName.value.ifEmpty { "" })
            val sendToken = bobEntry.session.sendToken.toHex()
            Log.d(TAG, "confirmQrScan: processScannedQr succeeded, friendId=${bobEntry.id}, fingerprint=${bobEntry.id.take(8)}, sendToken=$sendToken")
            _friends.value = e2eeStore.listFriends()
            viewModelScope.launch {
                try {
                    val discoveryHex = qrWithName.discoveryToken().toHex()
                    Log.d(TAG, "confirmQrScan: posting KeyExchangeInit, discoveryHex=$discoveryHex")
                    E2eeMailboxClient.post(BuildConfig.SERVER_HTTP_URL, discoveryHex, initPayload)
                    Log.d(TAG, "confirmQrScan: mailbox post succeeded")
                    updateStatus(null)
                } catch (e: Exception) {
                    Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                    updateStatus(e)
                }
                locationClient.postOpkBundle(bobEntry.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirmQrScan: processScannedQr failed", e)
        }
    }

    fun confirmPendingInit(name: String) {
        val payload = _pendingInitPayload.value ?: return
        _pendingInitPayload.value = null
        try {
            e2eeStore.processKeyExchangeInit(payload, name)
            _friends.value = e2eeStore.listFriends()
        } catch (_: Exception) {
        }
    }

    fun cancelPendingInit() {
        _pendingInitPayload.value = null
    }

    private suspend fun pollLoop() {
        while (true) {
            try {
                Log.d(TAG, "Polling for location updates")
                val updates = locationClient.poll()
                Log.d(TAG, "Got ${updates.size} location updates")
                for (update in updates) {
                    friendLocations.value += (update.userId to update)
                    _friendLastPing.value += (update.userId to System.currentTimeMillis())
                }
                pollPendingInvite()
                updateStatus(null)
            } catch (e: Exception) {
                Log.e(TAG, "Poll failed: ${e.message}")
                updateStatus(e)
            }
            delay(60_000)
        }
    }

    private suspend fun pollPendingInvite() {
        val qr = e2eeStore.pendingQrPayload ?: return
        try {
            val discoveryHex = qr.discoveryToken().toHex()
            val messages = E2eeMailboxClient.poll(BuildConfig.SERVER_HTTP_URL, discoveryHex)
            updateStatus(null)
            val initPayload = messages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull() ?: return

            // Found init payload! Show naming dialog instead of processing immediately.
            _pendingInitPayload.value = initPayload
            _pendingInviteQr.value = null
            e2eeStore.clearInvite()
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
