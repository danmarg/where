package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
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
    e2eeManagerParam: E2eeManager? = null,
    userStoreParam: UserStore? = null,
    locationClientParam: LocationClient? = null,
    startPolling: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() },
    locationSourceParam: LocationSource? = null,
    uiStateStoreParam: UiStateSource? = null,
) : AndroidViewModel(app) {
    // Secondary constructor for reflection-based instantiation in release builds.
    constructor(app: Application) : this(app, null, null, null, true)

    // Use the Application-level singletons so LocationService and this ViewModel share the same
    // E2EE state. Fall back to creating new instances when running under test (app is not
    // WhereApplication in unit tests).
    private val e2eeManager: E2eeManager =
        e2eeManagerParam
            ?: (app as? WhereApplication)?.e2eeManager
            ?: E2eeManager(
                AndroidSqliteDriver(net.af0.where.db.WhereDatabase.Schema, app, "where.db"),
            )
    private val userStore: UserStore =
        userStoreParam
            ?: (app as? WhereApplication)?.userStore
            ?: UserStore(SharedPrefsRawKeyValueStorage(app))
    private val locationClient: LocationClient =
        locationClientParam
            ?: (app as? WhereApplication)?.locationClient
            ?: LocationClient(BuildConfig.SERVER_HTTP_URL, this.e2eeManager)
    private val locationSource: LocationSource =
        locationSourceParam
            ?: (app as? WhereApplication)?.locationSource
            ?: LocationRepository(userStore)
    private val uiStateStore: UiStateSource =
        uiStateStoreParam
            ?: (app as? WhereApplication)?.uiStateStore
            ?: UiStateStore()

    val isSharingLocation: StateFlow<Boolean> = userStore.isSharingLocation

    val sharingExpiresAt: StateFlow<Long?> = userStore.sharingExpiresAt

    val displayName: StateFlow<String> = userStore.displayName

    val friends: StateFlow<List<FriendEntry>> = locationSource.friends

    val pausedFriendIds: StateFlow<Set<String>> = userStore.pausedFriendIds

    private val confirmedFriendIds: StateFlow<Set<String>> =
        friends.map { list -> list.filter { it.isConfirmed }.map { it.id }.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val friendLocations: StateFlow<Map<String, UserLocation>> =
        combine(locationSource.friendLocations, confirmedFriendIds) { locations, ids ->
            locations.filterKeys { it in ids }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val friendLastPing: StateFlow<Map<String, Long>> =
        combine(locationSource.friendLastPing, confirmedFriendIds) { pings, ids ->
            pings.filterKeys { it in ids }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _inviteState = MutableStateFlow<InviteState>(InviteState.None)
    val inviteState: StateFlow<InviteState> = _inviteState
    private var inviteJob: Job? = null

    val pendingQrForNaming: StateFlow<QrPayload?> = uiStateStore.pendingQrForNaming
    val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = locationSource.pendingInitPayload
    val allPendingInvites: StateFlow<List<PendingInviteView>> = locationSource.allPendingInvites

    val multipleScansDetected: StateFlow<Boolean> = uiStateStore.multipleScansDetected

    val isInviteSheetShowing: StateFlow<Boolean> = uiStateStore.isInviteSheetShowing

    private val _isExchanging = MutableStateFlow(false)
    val isExchanging: StateFlow<Boolean> = _isExchanging

    val connectionStatus: StateFlow<ConnectionStatus> = locationSource.connectionStatus
    val diagnosticLog: StateFlow<List<String>> = e2eeManager.diagnosticLog

    private val _showBatteryOptimizationDialog = MutableStateFlow(false)
    val showBatteryOptimizationDialog: StateFlow<Boolean> = _showBatteryOptimizationDialog.asStateFlow()

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
        // Sync foreground state to manage the background service.
        viewModelScope.launch {
            locationSource.isAppInForeground.collect { inForeground ->
                manageForegroundService(isSharingLocation.value, inForeground)
            }
        }

        viewModelScope.launch {
            val savedFriends = this@LocationViewModel.e2eeManager.listFriends()
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

        // Time-limited share: enforce the persisted expiry.
        // On launch, if already expired, immediately stop. Otherwise schedule a stop at expiry.
        // collectLatest cancels the prior delay if the expiry changes (user re-picks duration).
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(sharingExpiresAt, isSharingLocation) { expiresAt, sharing ->
                expiresAt to sharing
            }.collectLatest { (expiresAt, sharing) ->
                if (expiresAt == null || !sharing) return@collectLatest
                val nowSec = clock() / 1000L
                val remainingSec = expiresAt - nowSec
                if (remainingSec <= 0L) {
                    setSharing(false, null)
                } else {
                    kotlinx.coroutines.delay(remainingSec * 1000L)
                    if (isSharingLocation.value && userStore.sharingExpiresAt.value == expiresAt) {
                        setSharing(false, null)
                    }
                }
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
        setSharing(!isSharingLocation.value, null)
    }

    /**
     * Set the sharing state, optionally with an automatic expiry.
     * - Turning sharing on: starts GPS; if [expiresAt] is non-null, schedules an automatic stop.
     * - Turning sharing off: enqueues a StoppedSharing message to every active friend so their
     *   UI gets a positive "stopped" signal (otherwise they'd see stale data for hours).
     *   Keepalives continue afterwards so the peer session doesn't go stale.
     */
    fun setSharing(sharing: Boolean, expiresAt: Long?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setSharing must be called on the main thread" }
        val wasSharing = isSharingLocation.value
        userStore.setSharing(sharing)
        userStore.setSharingExpiresAt(if (sharing) expiresAt else null)
        if (wasSharing && !sharing) {
            viewModelScope.launch {
                try {
                    locationClient.sendStoppedSharing(pausedFriendIds = pausedFriendIds.value)
                } catch (e: Exception) {
                    Log.w(TAG, "sendStoppedSharing failed: ${e.message}")
                }
            }
        }
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
            e2eeManager.renameFriend(id, newName)
        }
    }

    fun removeFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "removeFriend must be called on the main thread" }
        viewModelScope.launch {
            e2eeManager.deleteFriend(id)
            userStore.removePausedFriend(id)
            val updatedFriends = e2eeManager.listFriends()
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
                    val qr = e2eeManager.createInvite(displayName.value)
                    _inviteState.value = InviteState.Pending(qr)
                    triggerRapidPoll()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create invite", e)
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

        // If we were showing our own invite sheet, dismiss it immediately to make room for the naming dialog.
        inviteJob?.cancel()
        _inviteState.value = InviteState.None
        uiStateStore.setInviteSheetShowing(false)

        uiStateStore.onPendingQrForNaming(qr)
        triggerRapidPoll()
        return true
    }

    fun cancelQrScan() {
        uiStateStore.onPendingQrForNaming(null)
        locationSource.resetRapidPoll()
    }

    fun confirmQrScan(
        qr: QrPayload,
        friendName: String,
    ) {
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        uiStateStore.onPendingQrForNaming(null)
        locationSource.confirmQrScan()

        // Reset our own invite state immediately.
        inviteJob?.cancel()
        _inviteState.value = InviteState.None
        uiStateStore.setInviteSheetShowing(false)

        val qrWithName = qr.copy(suggestedName = friendName)
        val currentInvite = _inviteState.value
        _isExchanging.value = true
        viewModelScope.launch {
            try {
                // Bob: clear his own persistent outgoing invite from the store if he's currently showing one.
                if (currentInvite is InviteState.Pending) {
                    e2eeManager.clearInvite(currentInvite.qr.ekPub)
                }

                val (initPayload, bobEntry) = e2eeManager.processScannedQr(qrWithName, displayName.value)
                val sendToken = bobEntry.session.sendToken.toHex()
                Log.d(
                    TAG,
                    "confirmQrScan: processScannedQr succeeded, friendId=${bobEntry.id}, fingerprint=${bobEntry.id.take(
                        8,
                    )}, sendToken=$sendToken",
                )
                withContext(Dispatchers.Main.immediate) {
                    locationSource.onFriendsUpdated(e2eeManager.listFriends())
                    locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
                }
                try {
                    try {
                        Log.d(TAG, "confirmQrScan: posting KeyExchangeInit")
                        locationClient.postKeyExchangeInit(bobEntry.id, qrWithName, initPayload)
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

        inviteJob?.cancel()
        _inviteState.value = InviteState.None
        uiStateStore.setInviteSheetShowing(false)

        _isExchanging.value = true
        viewModelScope.launch {
            try {
                val entry = e2eeManager.processKeyExchangeInit(payload, name, aliceEkPub)
                if (entry != null) {
                    Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                    withContext(Dispatchers.Main.immediate) {
                        locationSource.onFriendsUpdated(e2eeManager.listFriends())
                        locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
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

        // Clear UI state immediately so background polls don't re-trigger while we are clearing the store.
        locationSource.onPendingInit(null)
        _inviteState.value = InviteState.None

        viewModelScope.launch {
            if (aliceEkPub != null) {
                e2eeManager.clearInvite(aliceEkPub)
            } else {
                val last = e2eeManager.listPendingInvites().lastOrNull()
                if (last != null) {
                    e2eeManager.clearInvite(last.qrPayload.ekPub)
                }
            }
        }
    }

    fun cancelPendingInvite(ekPub: ByteArray) {
        viewModelScope.launch {
            e2eeManager.clearInvite(ekPub)
        }
    }

    fun setInviteSheetShowing(showing: Boolean) {
        uiStateStore.setInviteSheetShowing(showing)
        if (showing) {
            locationSource.triggerRapidPoll()
        }
    }

    fun markCurrentInviteExported() {
        val current = _inviteState.value
        if (current is InviteState.Pending) {
            markInviteExported(current.qr.ekPub)
        }
    }

    fun markInviteExported(ekPub: ByteArray) {
        viewModelScope.launch {
            e2eeManager.markInviteExported(ekPub)
        }
    }

    fun clearInviteIfNotExported() {
        uiStateStore.setInviteSheetShowing(false)
        val current = _inviteState.value

        // Reset UI state immediately
        locationSource.resetRapidPoll()
        _inviteState.value = InviteState.None

        if (current is InviteState.Pending && locationSource.pendingInitPayload.value == null) {
            viewModelScope.launch {
                // Refresh list from store to check exportedAt
                val invites = e2eeManager.listPendingInvites()
                val match = invites.find { it.qrPayload.ekPub.contentEquals(current.qr.ekPub) }
                if (match != null && match.exportedAt == null) {
                    e2eeManager.clearInvite(current.qr.ekPub)
                }
            }
        }
    }

    fun clearInvite() {
        uiStateStore.setInviteSheetShowing(false)
        val current = _inviteState.value

        // Reset UI state immediately
        locationSource.resetRapidPoll()
        _inviteState.value = InviteState.None

        // If the user dismisses the "Share Invite" sheet, we clear that specific invite
        // from the store to match previous behavior (though it's now multi-invite).
        if (current is InviteState.Pending && locationSource.pendingInitPayload.value == null) {
            viewModelScope.launch {
                e2eeManager.clearInvite(current.qr.ekPub)
            }
        }
    }

    private fun updateStatus(e: Throwable?) {
        if (e == null) {
            locationSource.onConnectionStatus(ConnectionStatus.Ok)
        } else {
            locationSource.onConnectionError(e)
        }
    }

    fun dismissBatteryOptimizationDialog() {
        _showBatteryOptimizationDialog.value = false
        getApplication<Application>()
            .getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("battery_opt_asked", true).apply()
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
        val hasRelationships = friends.value.isNotEmpty() || locationSource.allPendingInvites.value.isNotEmpty()
        if ((sharing && hasLocationPermission && hasRelationships) || inForeground) {
            getApplication<Application>().startForegroundService(intent)
            try {
                WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
                    LocationServiceRestartWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<LocationServiceRestartWorker>(15, TimeUnit.MINUTES).build(),
                )
            } catch (_: IllegalStateException) {
                Log.w(TAG, "WorkManager not available")
            }
            if (sharing) {
                val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
                val pkg = getApplication<Application>().packageName
                val prefs = getApplication<Application>().getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
                if (!pm.isIgnoringBatteryOptimizations(pkg) && !prefs.getBoolean("battery_opt_asked", false)) {
                    _showBatteryOptimizationDialog.value = true
                }
            }
        } else if (!hasRelationships) {
            try { WorkManager.getInstance(getApplication()).cancelUniqueWork(LocationServiceRestartWorker.WORK_NAME) }
            catch (_: IllegalStateException) {}
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
