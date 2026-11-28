package net.af0.where

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import kotlinx.coroutines.CancellationException
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
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InviteAlreadyAcceptedException
import net.af0.where.e2ee.InviteExpiredException
import net.af0.where.e2ee.InvitePublicKey
import net.af0.where.e2ee.InviteState
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.PendingInviteView
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.UserStore
import net.af0.where.model.UserLocation
import net.af0.where.shared.MR
import java.util.concurrent.TimeUnit

private const val TAG = "LocationViewModel"
private const val KEY_STOPPED_FOR_MISSING_PERMISSION = "stopped_sharing_for_missing_permission"

class LocationViewModel(
    app: Application,
    e2eeManagerParam: E2eeManager? = null,
    userStoreParam: UserStore? = null,
    locationClientParam: LocationClient? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    locationSourceParam: LocationSource? = null,
    uiStateStoreParam: UiStateSource? = null,
) : AndroidViewModel(app) {
    // Secondary constructor for reflection-based instantiation in release builds.
    constructor(app: Application) : this(app, null, null, null)

    // Use the Application-level singletons so LocationService and this ViewModel share the same
    // E2EE state. Fall back to creating new instances when running under test (app is not
    // WhereApplication in unit tests).
    private val e2eeManager: E2eeManager =
        e2eeManagerParam
            ?: (app as? WhereApplication)?.e2eeManager
            ?: E2eeManager(
                AndroidSqliteDriver(net.af0.where.db.WhereDatabase.Schema, app, "where.db"),
            )
    private var foregroundPollJob: Job? = null
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
    private val friendPoller =
        FriendPoller(
            locationClient,
            e2eeManager,
            userStore,
            locationSource,
            uiStateStore,
            clock,
            gate = (app as? WhereApplication)?.pollGate ?: PollGate(),
            canShare = { app.hasLocationPermission() },
        )

    val isSharingLocation: StateFlow<Boolean> = userStore.isSharingLocation

    val friendExpiresAt: StateFlow<Map<String, Long>> = userStore.friendExpiresAt

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

    // Exchanges in flight (confirmQrScan, confirmPendingInit); _isExchanging (the spinner) is true while any runs.
    private var exchangesInFlight = 0

    // Invites whose confirmQrScan is running: blocks a double tap of the same invite only.
    private val confirmingInvites: MutableSet<QrPayload> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun beginExchange() {
        synchronized(this) {
            exchangesInFlight++
            _isExchanging.value = true
        }
    }

    private fun endExchange() {
        synchronized(this) {
            if (exchangesInFlight == 0) Log.w(TAG, "endExchange: no exchange in flight")
            exchangesInFlight = maxOf(0, exchangesInFlight - 1)
            _isExchanging.value = exchangesInFlight > 0
        }
    }

    val connectionStatus: StateFlow<ConnectionStatus> = locationSource.connectionStatus
    val diagnosticLog: StateFlow<List<String>> = e2eeManager.diagnosticLog

    private val _showBatteryOptimizationDialog = MutableStateFlow(false)
    val showBatteryOptimizationDialog: StateFlow<Boolean> = _showBatteryOptimizationDialog.asStateFlow()

    // Why an invite the user just opened or confirmed was refused. Shown in a dialog until
    // dismissed: the connection status would be cleared by the next successful poll, seconds
    // later, usually before the user has seen it.
    private val _inviteError = MutableStateFlow<StringDesc?>(null)
    val inviteError: StateFlow<StringDesc?> = _inviteError.asStateFlow()

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

    // Declared before init {}: viewModelScope runs on Main.immediate, so the init-block collectors
    // run during construction and can start the service before later properties are initialized.
    @VisibleForTesting
    internal var serviceStarter: (Intent) -> Unit = { getApplication<Application>().startForegroundService(it) }

    // Service starts Android refused because the app was not foreground-eligible, keyed by
    // (action, friend) so repeated refusals of the same request collapse to one retry.
    private val deferredServiceStarts = LinkedHashMap<Pair<String?, String?>, Intent>()

    // Set by reconcileSharingWithPermission(), which also runs from init {}.
    private var stoppedSharingInFlight = false

    init {
        Log.d(TAG, "LocationViewModel init: server=${BuildConfig.SERVER_HTTP_URL}")
        // Sync foreground state to manage the background service.
        viewModelScope.launch {
            locationSource.isAppInForeground.collect { inForeground ->
                manageForegroundService(isSharingLocation.value, inForeground)
                updateForegroundPolling(inForeground)
                if (inForeground) reconcileSharingWithPermission()
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
                    initialLastPing[friend.id] = (friend.lastSeenSeconds ?: ts) * 1000L
                }
            }
            locationSource.setInitialFriendLocations(initialLocations, initialLastPing)
        }

        // Per-friend timed share: when the soonest expiry passes, pause that friend (so the send
        // gate effectivelyPausedIds() locks in the new state) and enqueue a StoppedSharing message.
        // collectLatest re-arms when the map changes (user adds/clears a timer).
        viewModelScope.launch {
            friendExpiresAt.collectLatest { map ->
                if (map.isEmpty()) return@collectLatest
                val nowSec = clock() / 1000L
                val (friendId, expiresAt) = map.minByOrNull { it.value } ?: return@collectLatest
                val remainingSec = expiresAt - nowSec
                if (remainingSec > 0L) kotlinx.coroutines.delay(remainingSec * 1000L)
                // Re-check after the delay: the timer may have been cleared or reset.
                if (userStore.friendExpiresAt.value[friendId] != expiresAt) return@collectLatest
                fireFriendExpiry(friendId)
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

    /**
     * startForegroundService() throws ForegroundServiceStartNotAllowedException (Android 12+)
     * if the app is no longer foreground-eligible - e.g. a call made after a network await
     * while the user switched away. Uncaught in viewModelScope that crashed the app or
     * skipped the rest of the caller. Instead the request is kept and re-sent by
     * [retryDeferredServiceStarts] when the activity next resumes, so e.g. turning sharing on
     * still starts the service and broadcasts without the user toggling it again.
     */
    private fun startLocationService(intent: Intent) {
        try {
            serviceStarter(intent)
        } catch (e: IllegalStateException) {
            if (!isBackgroundStartNotAllowed(e)) throw e
            Log.w(TAG, "Could not start LocationService (${intent.action}); retrying on next resume: ${e.message}")
            deferredServiceStarts[intent.action to intent.getStringExtra(LocationService.EXTRA_FRIEND_ID)] = intent
        }
    }

    /** Called from MainActivity.onResume(), when a foreground-service start is permitted. */
    fun retryDeferredServiceStarts() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "retryDeferredServiceStarts must be called on the main thread" }
        if (deferredServiceStarts.isEmpty()) return
        val pending = deferredServiceStarts.values.toList()
        deferredServiceStarts.clear()
        // A request made stale by a later state change is harmless: LocationService ignores
        // ACTION_FORCE_PUBLISH while not sharing.
        pending.forEach { startLocationService(it) }
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
        setSharing(!isSharingLocation.value)
    }

    /**
     * Set the master sharing toggle. Off = no Locations to anyone (Keepalives continue);
     * a StoppedSharing fan-out is enqueued so peers see a positive "stopped" signal rather
     * than stale data.
     */
    fun setSharing(sharing: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setSharing must be called on the main thread" }
        // Sharing requires location permission; without it we're receive-only. The UI disables the
        // control, this keeps every other caller honest too.
        if (sharing && !getApplication<Application>().hasLocationPermission()) return
        val wasSharing = isSharingLocation.value
        userStore.setSharing(sharing)
        if (wasSharing && !sharing) {
            viewModelScope.launch {
                try {
                    locationClient.sendStoppedSharing(pausedFriendIds = userStore.effectivelyPausedIds())
                } catch (e: Exception) {
                    Log.w(TAG, "sendStoppedSharing failed: ${e.message}")
                }
            }
        } else if (!wasSharing && sharing) {
            // Symmetric to the stop fan-out: ask the service to broadcast our current location
            // to every (non-paused) friend immediately, so peers don't wait for the next
            // regular location tick (up to a heartbeat interval if stationary/backgrounded).
            forcePublish()
        }
    }

    /** Set or clear a per-friend share timer. null clears (= share indefinitely). */
    fun setFriendExpiry(
        friendId: String,
        expiresAt: Long?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "setFriendExpiry must be called on the main thread" }
        userStore.setFriendExpiry(friendId, expiresAt)
    }

    private fun fireFriendExpiry(friendId: String) {
        userStore.setFriendExpiry(friendId, null)
        // togglePauseFriend handles the StoppedSharing fan-out on a pause-transition.
        // If the friend is already paused, Bob already got the message — no double-send.
        if (friendId !in userStore.pausedFriendIds.value) {
            togglePauseFriend(friendId)
        }
    }

    fun togglePauseFriend(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "togglePauseFriend must be called on the main thread" }
        val wasPaused = id in userStore.pausedFriendIds.value
        userStore.togglePauseFriend(id)
        // On transition into paused, give the peer the same positive "stopped" signal
        // that master-off and per-friend-timer-expiry produce. On transition out of
        // paused, immediately broadcast our current location so the peer doesn't have
        // to wait up to a full heartbeat interval for the implicit "I'm back" update.
        if (!wasPaused) {
            viewModelScope.launch {
                try {
                    locationClient.sendStoppedSharingToFriend(id)
                } catch (e: Exception) {
                    Log.w(TAG, "sendStoppedSharingToFriend($id) failed: ${e.message}")
                }
            }
        } else {
            forcePublish(id)
        }
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
        val pausedIds = userStore.effectivelyPausedIds()
        // Same notion of "sharing" FriendPoller gives poll(): without location permission we're
        // receive-only, so there's nothing to say we stopped.
        val sharing = isSharingLocation.value && getApplication<Application>().hasLocationPermission()
        viewModelScope.launch {
            try {
                // Queues a StoppedSharing (if we were sharing with them) and deletes them locally;
                // it's delivered below, after the UI has updated, or by a later poll.
                locationClient.removeFriend(id, pausedFriendIds = pausedIds, sharingEnabled = sharing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "removeFriend($id) failed: ${e.message}")
                return@launch
            }
            userStore.removePausedFriend(id)
            val updatedFriends = e2eeManager.listFriends()
            withContext(Dispatchers.Main.immediate) {
                locationSource.onFriendRemoved(id)
                locationSource.onFriendsUpdated(updatedFriends)
            }
            try {
                locationClient.processOrphanedOutboxes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "processOrphanedOutboxes after removing $id failed: ${e.message}")
            }
        }
    }

    fun createInvite() {
        inviteJob?.cancel()
        if (locationSource.pendingInitPayload.value != null) return

        inviteJob =
            viewModelScope.launch {
                try {
                    // A name edit while an invite is showing renames it in place (same keys), so a
                    // scan of the QR as shown a moment ago still pairs and edits don't mint invites.
                    val shown = (_inviteState.value as? InviteState.Pending)?.qr?.ekPub
                    val qr =
                        shown?.let { e2eeManager.updateInviteName(it, displayName.value) }
                            ?: e2eeManager.createInvite(displayName.value)
                    _inviteState.value = InviteState.Pending(qr)
                    // Ensure the service is running so it polls the discovery mailbox.
                    // Only start if location permission is granted; without it the service
                    // would immediately enter the "permission missing" notification state.
                    val hasPermission = getApplication<Application>().hasLocationPermission()
                    if (hasPermission) {
                        val svcIntent = Intent(getApplication(), LocationService::class.java)
                        startLocationService(svcIntent)
                    }
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
        // The URL fragment carries the invite's discovery secret and key material; never log it.
        Log.d(TAG, "processQrUrl")
        val qr =
            QrPayload.fromUrl(url) ?: run {
                Log.e(TAG, "processQrUrl: failed to parse URL")
                showInviteError(MR.strings.invalid_qr_code)
                return false
            }
        if (qr.isExpired()) {
            Log.e(TAG, "processQrUrl: invite expired")
            showInviteError(MR.strings.invite_expired)
            return false
        }
        // Idempotent per invite: accepting one pairs with fresh keys, so a redelivered or
        // re-opened link for an invite we already accepted must not pair a second time. The
        // friend list is the cheap early check; E2eeManager.processScannedQr enforces it again
        // under its lock, together with the stored record of consumed invites (which also covers
        // a friend since removed) and reports it as InviteAlreadyAcceptedException. Consumed
        // records can't be imported from the old SharedPreferences store (hashed keys), so that
        // is consulted read-only until its entries lapse (the app's warm-up thread has loaded it).
        if (friends.value.any { it.isFromInvite(qr) }) {
            Log.w(TAG, "processQrUrl: invite already accepted")
            showInviteError(MR.strings.invite_already_used)
            return false
        }
        // Already showing the naming dialog for this exact invite: nothing to do.
        if (pendingQrForNaming.value == qr) return true
        Log.d(TAG, "processQrUrl: parsed qr, suggestedName=${qr.suggestedName}")

        // If we were showing our own invite sheet, dismiss it immediately to make room for the naming dialog.
        inviteJob?.cancel()
        _inviteState.value = InviteState.None
        uiStateStore.setInviteSheetShowing(false)

        uiStateStore.onPendingQrForNaming(qr)
        triggerRapidPoll()
        // The stored record (a friend since removed) needs the database: check it right away and
        // take the naming dialog back down if the invite was already used.
        viewModelScope.launch {
            val consumed =
                try {
                    e2eeManager.isInviteConsumed(qr) || LegacyConsumedInvites(getApplication()).contains(qr)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            if (consumed && pendingQrForNaming.value == qr) {
                Log.w(TAG, "processQrUrl: invite already accepted (consumed record)")
                uiStateStore.onPendingQrForNaming(null)
                locationSource.resetRapidPoll()
                showInviteError(MR.strings.invite_already_used)
            }
        }
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
        // A second confirm of the same invite while the first runs (a double tap) is ignored before
        // it can touch UI state; the first keeps the spinner. Other exchanges don't block this one.
        // It may run alongside confirmPendingInit: both go through E2eeManager's persistence locks
        // (withMetadataLock / withFriendAndMetadataLock), which serialize their writes.
        if (confirmingInvites.contains(qr)) return
        Log.d(TAG, "confirmQrScan: friendName=$friendName")
        uiStateStore.onPendingQrForNaming(null)
        locationSource.confirmQrScan()

        // Reset our own invite state immediately.
        inviteJob?.cancel()
        _inviteState.value = InviteState.None
        uiStateStore.setInviteSheetShowing(false)

        val qrWithName = qr.copy(suggestedName = friendName)
        if (!confirmingInvites.add(qr)) return
        beginExchange()
        viewModelScope.launch {
            try {
                // The shared manager knows nothing of invites accepted before the upgrade.
                if (LegacyConsumedInvites(getApplication()).contains(qr)) {
                    throw InviteAlreadyAcceptedException()
                }
                val (initPayload, bobEntry) = e2eeManager.processScannedQr(qrWithName, displayName.value)
                // Never log routing tokens: they are mailbox read/delete capabilities.
                Log.d(TAG, "confirmQrScan: processScannedQr succeeded, fingerprint=${bobEntry.id.take(8)}")
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
                        forcePublish(bobEntry.id)
                    }

                    expectFirstUpdate(bobEntry.id)
                } catch (e: Exception) {
                    Log.e(TAG, "confirmQrScan inner failure: ${e.message}")
                    updateStatus(e)
                }
            } catch (e: InviteAlreadyAcceptedException) {
                // Already paired from this invite (a duplicate confirm, or a friend since removed).
                Log.w(TAG, "confirmQrScan: invite already accepted")
                showInviteError(MR.strings.invite_already_used)
            } catch (e: Exception) {
                Log.e(TAG, "confirmQrScan: processScannedQr failed", e)
                // No pairing was created (the consumed record commits with it), so the invite may be tried again.
                if (e is InviteExpiredException) showInviteError(MR.strings.invite_expired) else updateStatus(e)
            } finally {
                confirmingInvites.remove(qr)
                endExchange()
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

        beginExchange()
        viewModelScope.launch {
            try {
                val entry = e2eeManager.processKeyExchangeInit(payload, name, aliceEkPub)
                if (entry != null) {
                    Log.d(TAG, "confirmPendingInit: processKeyExchangeInit succeeded, friendId=${entry.id}")
                    withContext(Dispatchers.Main.immediate) {
                        locationSource.onFriendsUpdated(e2eeManager.listFriends())
                        locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
                    }
                    expectFirstUpdate(entry.id)
                    try {
                        if (isSharingLocation.value) {
                            forcePublish(entry.id)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmPendingInit: inner failure: ${e.message}")
                        updateStatus(e)
                    }
                } else {
                    Log.e(TAG, "confirmPendingInit: processKeyExchangeInit returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "confirmPendingInit: processKeyExchangeInit failed", e)
            } finally {
                endExchange()
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

    fun cancelPendingInvite(ekPub: InvitePublicKey) {
        viewModelScope.launch {
            e2eeManager.clearInvite(ekPub)
        }
    }

    /** Tail of a new pairing: poll fast until the friend's first update arrives. */
    private fun expectFirstUpdate(friendId: String) {
        locationSource.markAwaitingFirstUpdate(friendId)
        locationSource.triggerRapidPoll()
        locationSource.wakePoll()
    }

    /** Asks the service to broadcast now: to one friend, or to every non-paused friend. */
    private fun forcePublish(friendId: String? = null) {
        startLocationService(
            Intent(getApplication(), LocationService::class.java).apply {
                action = LocationService.ACTION_FORCE_PUBLISH
                if (friendId != null) putExtra(LocationService.EXTRA_FRIEND_ID, friendId)
            },
        )
    }

    fun setInviteSheetShowing(showing: Boolean) {
        uiStateStore.setInviteSheetShowing(showing)
        if (showing) {
            locationSource.triggerRapidPoll()
        }
    }

    fun markInviteExported(ekPub: InvitePublicKey) {
        viewModelScope.launch {
            e2eeManager.markInviteExported(ekPub)
        }
    }

    /**
     * Closes the invite sheet without deleting the invite.
     *
     * Showing a QR in person never marks it exported, and the scan can arrive after the sheet is
     * gone (swiped away, or closed to scan the other person's QR). Deleting the invite here would
     * discard its private key and silently drop that scan, leaving the scanner "Pending" forever.
     * Unused invites are cleaned up by [E2eeManager.cleanupExpiredInvites].
     */
    fun dismissInvite() {
        uiStateStore.setInviteSheetShowing(false)
        locationSource.resetRapidPoll()
        _inviteState.value = InviteState.None
    }

    private fun showInviteError(message: StringResource) {
        _inviteError.value = StringDesc.Resource(message)
    }

    fun dismissInviteError() {
        _inviteError.value = null
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

    /**
     * Call when location permission may have changed while the app stays in the foreground (the
     * in-app grant flow doesn't flip [LocationSource.isAppInForeground]): hands polling between
     * this ViewModel and the service, and reconciles sharing state with peers.
     */
    @MainThread
    fun onLocationPermissionChanged() {
        val inForeground = locationSource.isAppInForeground.value
        manageForegroundService(isSharingLocation.value, inForeground)
        updateForegroundPolling(inForeground)
        reconcileSharingWithPermission()
    }

    /**
     * Keeps peers' view honest across permission changes. If the user had sharing on and lost
     * location permission (Settings, auto-revoke), we can't send locations: tell peers once so
     * they see "stopped sharing" instead of a stale pin. When permission returns, resume.
     * The "already told them" flag is persisted, and only set once the send succeeded, so it
     * fires once per loss but is retried after a failure.
     */
    private fun reconcileSharingWithPermission() {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
        val notified = prefs.getBoolean(KEY_STOPPED_FOR_MISSING_PERMISSION, false)
        val hasPermission = getApplication<Application>().hasLocationPermission()
        if (isSharingLocation.value && !hasPermission && !notified) {
            if (stoppedSharingInFlight) return
            stoppedSharingInFlight = true
            viewModelScope.launch {
                try {
                    locationClient.sendStoppedSharing(pausedFriendIds = userStore.effectivelyPausedIds())
                    prefs.edit().putBoolean(KEY_STOPPED_FOR_MISSING_PERMISSION, true).apply()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sendStoppedSharing (permission lost) failed, will retry: ${e.message}")
                } finally {
                    stoppedSharingInFlight = false
                }
                // Permission may have returned while the send was in flight, when the check above
                // saw the flag unset and did nothing: re-check now that it is set (C8).
                if (getApplication<Application>().hasLocationPermission()) reconcileSharingWithPermission()
            }
        } else if (hasPermission && notified) {
            prefs.edit().putBoolean(KEY_STOPPED_FOR_MISSING_PERMISSION, false).apply()
            if (isSharingLocation.value) {
                forcePublish()
            }
        }
    }

    /**
     * Where [LocationService] can't run (no location permission on API 34+), nothing would poll
     * for pairing handshakes or friend updates. While the app is visible, poll from here instead.
     * Ends when the app backgrounds, or when the service becomes runnable (permission granted):
     * the service is then (re)started to take over.
     */
    private fun updateForegroundPolling(inForeground: Boolean) {
        foregroundPollJob?.cancel()
        foregroundPollJob = null
        val app = getApplication<Application>()
        if (!inForeground || app.canRunLocationService()) return
        foregroundPollJob =
            viewModelScope.launch {
                while (locationSource.isAppInForeground.value) {
                    if (app.canRunLocationService()) {
                        manageForegroundService(isSharingLocation.value, inForeground = true)
                        return@launch
                    }
                    // Mirror the service's idle behavior: nothing to talk to, nothing to poll. Read the
                    // store, not the UI state, which is only populated by a poll.
                    if (e2eeManager.listFriends().isNotEmpty() || e2eeManager.listPendingInvites().isNotEmpty()) {
                        friendPoller.poll(WakeSource.TIMER)
                    }
                    val interval =
                        if (friendPoller.isRapidPolling()) {
                            FriendPoller.RAPID_POLL_INTERVAL_MS
                        } else {
                            FriendPoller.FOREGROUND_POLL_INTERVAL_MS
                        }
                    locationSource.awaitPollWake(interval)
                }
            }
    }

    @MainThread
    private fun manageForegroundService(
        sharing: Boolean,
        inForeground: Boolean,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val intent = Intent(getApplication(), LocationService::class.java)
        val hasRelationships = friends.value.isNotEmpty() || locationSource.allPendingInvites.value.isNotEmpty()
        // Always kept: in the background with sharing off it's the only poller (#481), and
        // friends may not be loaded into the state yet. With no one to talk to it only retries
        // a farewell to a removed friend.
        try {
            WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
                LocationServiceRestartWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LocationServiceRestartWorker>(15, TimeUnit.MINUTES).build(),
            )
        } catch (_: IllegalStateException) {
            Log.w(TAG, "WorkManager not available")
        }
        if ((getApplication<Application>().needsBackgroundService(userStore) && hasRelationships) || inForeground) {
            startLocationService(intent)
            if (sharing) {
                val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
                val pkg = getApplication<Application>().packageName
                val prefs = getApplication<Application>().getSharedPreferences("where_prefs", Context.MODE_PRIVATE)
                if (!pm.isIgnoringBatteryOptimizations(pkg) && !prefs.getBoolean("battery_opt_asked", false)) {
                    _showBatteryOptimizationDialog.value = true
                }
            }
        }
        // No stopService() here: the service stops itself once it's backgrounded with nothing to
        // send (see LocationService.pollLoop), after finishing whatever wake it's in.
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
