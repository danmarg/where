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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InviteState
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.PendingInviteView
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.UserStore
import net.af0.where.e2ee.toHex
import net.af0.where.model.UserLocation

private const val TAG = "LocationViewModel"

class LocationViewModel(
    app: Application,
    e2eeStoreParam: E2eeStore? = null,
    userStoreParam: UserStore? = null,
    locationClientParam: LocationClient? = null,
    startPolling: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val locationSource: LocationSource = LocationRepository,
) : AndroidViewModel(app) {
    // Secondary constructor for reflection-based instantiation in release builds.
    constructor(app: Application) : this(app, null, null, null, true)

    // Use the Application-level singletons so LocationService and this ViewModel share the same
    // E2EE state. Fall back to creating new instances when running under test (app is not
    // WhereApplication in unit tests).
    private val e2eeStore: E2eeStore =
        e2eeStoreParam
            ?: (app as? WhereApplication)?.e2eeStore
            ?: E2eeStore(SharedPrefsE2eeStorage(app))
    private val userStore: UserStore =
        userStoreParam
            ?: (app as? WhereApplication)?.userStore
            ?: UserStore(SharedPrefsE2eeStorage(app))
    private val locationClient: LocationClient =
        locationClientParam
            ?: (app as? WhereApplication)?.locationClient
            ?: LocationClient(BuildConfig.SERVER_HTTP_URL, this.e2eeStore)

    val isSharingLocation: StateFlow<Boolean> = userStore.isSharingLocation

    val displayName: StateFlow<String> = userStore.displayName

    val friends: StateFlow<List<FriendEntry>> = locationSource.friends

    val pausedFriendIds: StateFlow<Set<String>> = userStore.pausedFriendIds

    val friendLocations: StateFlow<Map<String, UserLocation>> =
        combine(locationSource.friendLocations, friends) { locations, friendList ->
            val confirmedIds = friendList.filter { it.isConfirmed }.map { it.id }.toSet()
            locations.filterKeys { it in confirmedIds }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val friendLastPing: StateFlow<Map<String, Long>> =
        combine(locationSource.friendLastPing, friends) { pings, friendList ->
            val confirmedIds = friendList.filter { it.isConfirmed }.map { it.id }.toSet()
            pings.filterKeys { it in confirmedIds }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _inviteState = MutableStateFlow<InviteState>(InviteState.None)
    val inviteState: StateFlow<InviteState> = _inviteState
    private var inviteJob: Job? = null

    val pendingQrForNaming: StateFlow<QrPayload?> = locationSource.pendingQrForNaming
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = locationSource.pendingInitPayload
    val allPendingInvites: StateFlow<List<PendingInviteView>> = locationSource.allPendingInvites

    val multipleScansDetected: StateFlow<Boolean> = locationSource.multipleScansDetected

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

    val ownHeading: StateFlow<Double?> =
        locationSource.lastLocation.map { it?.third }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val visibleUsers: StateFlow<List<UserLocation>> =
        friendLocations
            .map { it.values.toList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        Log.d(TAG, "LocationViewModel init: server=${BuildConfig.SERVER_HTTP_URL}")
        // Sync sharing state and foreground state to manage the background service.
        viewModelScope.launch {
            combine(isSharingLocation, locationSource.isAppInForeground) { sharing, inForeground ->
                Pair(sharing, inForeground)
            }.collect { (sharing, inForeground) ->
                locationSource.setSharingLocation(sharing)
                manageForegroundService(sharing, inForeground)
            }
        }

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
        }

        viewModelScope.launch {
            pausedFriendIds.collect { ids ->
                locationSource.setPausedFriends(ids)
            }
        }

        // When a friend response (init payload) arrives from the service, flip the invite
        // state to None so the UI shows the naming dialog (dismissing the QR sheet).
        viewModelScope.launch {
            pendingInitPayload.collect { payload ->
                if (payload != null) {
                    inviteJob?.cancel()
                    _inviteState.value = InviteState.None
                }
            }
        }
    }

    private fun triggerRapidPoll() {
        locationSource.triggerRapidPoll()
    }

    fun setDisplayName(name: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setDisplayName must be called on the main thread" }
        userStore.setDisplayName(name)
        // If an invite is active, we should update it.
        if (_inviteState.value is InviteState.Pending) {
            createInvite()
        }
    }

    fun toggleSharing() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "toggleSharing must be called on the main thread" }
        userStore.setSharing(!isSharingLocation.value)
    }

    fun togglePauseFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "togglePauseFriend must be called on the main thread" }
        userStore.togglePauseFriend(id)
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
            userStore.removePausedFriend(id)
            val updatedFriends = e2eeStore.listFriends()
            withContext(Dispatchers.Main.immediate) {
                locationSource.onFriendRemoved(id)
                locationSource.onFriendsUpdated(updatedFriends)
            }
        }
    }

    fun createInvite() {
        inviteJob?.cancel()
        if (locationSource.pendingInitPayload.value != null) return

        inviteJob =
            viewModelScope.launch {
                try {
                    val qr = e2eeStore.createInvite(displayName.value)
                    _inviteState.value = InviteState.Pending(qr)
                    locationSource.onPendingInvitesUpdated(e2eeStore.listPendingInvites())
                    triggerRapidPoll()
                } finally {
                    if (inviteJob?.isCancelled == false) {
                        // We don't null it out because we might want to cancel it later
                    }
                }
            }
    }

    fun processQrUrl(url: String): Boolean {
        Log.d(TAG, "processQrUrl: url=$url")
        val qr =
            QrPayload.fromUrl(url) ?: run {
                Log.e(TAG, "processQrUrl: failed to parse URL")
                return false
            }
        Log.d(TAG, "processQrUrl: parsed qr, suggestedName=${qr.suggestedName}")
        locationSource.onPendingQrForNaming(qr)
        triggerRapidPoll()
        return true
    }

    fun cancelQrScan() {
        locationSource.onPendingQrForNaming(null)
        locationSource.resetRapidPoll()
    }

    fun confirmQrScan(
        qr: QrPayload,
        friendName: String,
    ) {
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        locationSource.onPendingQrForNaming(null)
        locationSource.confirmQrScan()
        val qrWithName = qr.copy(suggestedName = friendName)
        val currentInvite = _inviteState.value
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                if (currentInvite != InviteState.None) {
                    // Bob: clear his own outgoing invite state if he's currently showing one,
                    // but keep other persistent invites.
                    if (currentInvite is InviteState.Pending) {
                        e2eeStore.clearInvite(currentInvite.qr.ekPub)
                    }
                    _inviteState.value = InviteState.None
                }
                val (initPayload, bobEntry) = e2eeStore.processScannedQr(qrWithName, displayName.value)
                val sendToken = bobEntry.session.sendToken.toHex()
                Log.d(
                    TAG,
                    "confirmQrScan: processScannedQr succeeded, friendId=${bobEntry.id}, fingerprint=${bobEntry.id.take(
                        8,
                    )}, sendToken=$sendToken",
                )
                withContext(Dispatchers.Main.immediate) {
                    locationSource.onFriendsUpdated(e2eeStore.listFriends())
                    locationSource.onPendingInvitesUpdated(e2eeStore.listPendingInvites())
                }
                try {
                    try {
                        Log.d(TAG, "confirmQrScan: posting KeyExchangeInit")
                        locationClient.postKeyExchangeInit(qrWithName, initPayload)
                        Log.d(TAG, "confirmQrScan: mailbox post succeeded")
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmQrScan: mailbox post failed", e)
                        updateStatus(e)
                        return@launch
                    }
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
        val aliceEkPub = locationSource.pendingInitAliceEkPub.value ?: return
        Log.d(TAG, "confirmPendingInit: name=$name")
        locationSource.onPendingInit(null)
        _inviteState.value = InviteState.None
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                val entry = e2eeStore.processKeyExchangeInit(payload, name, aliceEkPub)
                if (entry != null) {
                    Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                    withContext(Dispatchers.Main.immediate) {
                        locationSource.onFriendsUpdated(e2eeStore.listFriends())
                        locationSource.onPendingInvitesUpdated(e2eeStore.listPendingInvites())
                    }
                    locationSource.markAwaitingFirstUpdate(entry.id)
                    locationSource.triggerRapidPoll()
                    locationSource.wakePoll()
                    try {
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
        val aliceEkPub = locationSource.pendingInitAliceEkPub.value
        viewModelScope.launch {
            if (aliceEkPub != null) {
                e2eeStore.clearInvite(aliceEkPub)
            } else {
                val last = e2eeStore.listPendingInvites().lastOrNull()
                if (last != null) {
                    e2eeStore.clearInvite(last.qrPayload.ekPub)
                }
            }
            locationSource.onPendingInvitesUpdated(e2eeStore.listPendingInvites())
            locationSource.onPendingInit(null)
        }
    }

    fun cancelPendingInvite(ekPub: ByteArray) {
        viewModelScope.launch {
            e2eeStore.clearInvite(ekPub)
            locationSource.onPendingInvitesUpdated(e2eeStore.listPendingInvites())
        }
    }

    fun clearInvite() {
        val current = _inviteState.value
        // If the user dismisses the "Share Invite" sheet, we clear that specific invite
        // from the store to match previous behavior (though it's now multi-invite).
        if (current is InviteState.Pending && locationSource.pendingInitPayload.value == null) {
            viewModelScope.launch {
                e2eeStore.clearInvite(current.qr.ekPub)
                locationSource.onPendingInvitesUpdated(e2eeStore.listPendingInvites())
            }
        }
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
    private fun manageForegroundService(
        sharing: Boolean,
        inForeground: Boolean,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val intent = Intent(getApplication(), LocationService::class.java)
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if ((sharing && hasLocationPermission) || inForeground) {
            getApplication<Application>().startForegroundService(intent)
        }
        // Intentionally no stopService() here: when sharing is paused the service must remain
        // alive for maintenance polls (ratchet keepalives, token ACKs) so the Double Ratchet
        // token state stays in sync with peers. The service manages its own reduced polling
        // interval (30 min) and GPS deregistration when sharing is off. See LocationService.
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
