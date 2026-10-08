package net.af0.where

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.UserStore

internal fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Whether [LocationService] can actually run. On API 34+ the OS refuses a `location` foreground
 * service without the permission, so it self-stops; before that it stays alive regardless
 * (maintenance polling while sharing is paused), so the service is already polling.
 */
internal fun Context.canRunLocationService(): Boolean =
    hasLocationPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Serializes poll cycles and holds the hourly invite-cleanup timestamp. One instance is shared by
 * every [FriendPoller] in the app (see [WhereApplication.pollGate]) so the service, worker and
 * foreground loop don't surface the same handshake twice or repeat cleanup; tests get their own.
 */
internal class PollGate {
    val mutex = Mutex()

    @Volatile
    var lastCleanupTime: Long = 0L
}

/**
 * One friend/invite poll cycle: fetch friend updates, surface incoming pairing handshakes, and
 * refresh the UI state. Independent of [LocationService] so it can also run where the
 * foreground service can't (no location permission): from the app's foreground loop and from
 * [LocationServiceRestartWorker]. Without it, pairing and ratchet maintenance would not happen
 * at all for a user who hasn't granted location permission.
 *
 * Cycles are serialized through a shared [PollGate] so the service, the worker and the foreground
 * loop can overlap without surfacing the same handshake twice.
 */
internal class FriendPoller(
    private val locationClient: LocationClient,
    private val e2eeManager: E2eeManager,
    private val userStore: UserStore,
    private val locationSource: LocationSource,
    private val uiStateStore: UiStateSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val gate: PollGate = PollGate(),
    // Sharing needs location permission; without it we're receive-only whatever the stored toggle says.
    private val canShare: () -> Boolean = { true },
) {
    suspend fun poll(source: WakeSource = WakeSource.TIMER) {
        gate.mutex.withLock { pollLocked(source) }
    }

    private suspend fun pollLocked(source: WakeSource) {
        try {
            Log.d(TAG, "Polling for location updates (source=${source.value})")
            val now = clock()
            if (now - gate.lastCleanupTime > 3600_000L) {
                e2eeManager.cleanupExpiredInvites(48 * 3600L)
                gate.lastCleanupTime = now
            }
            val updates =
                locationClient.poll(
                    isForeground = locationSource.isAppInForeground.value,
                    pausedFriendIds = userStore.effectivelyPausedIds(),
                    sharingEnabled = userStore.isSharingLocation.value && canShare(),
                )
            Log.d(TAG, "Got ${updates.size} location updates")
            withContext(Dispatchers.Main) {
                val updateTime = System.currentTimeMillis()
                for (update in updates) {
                    locationSource.onFriendUpdate(update, updateTime)
                    locationSource.onFriendLocationReceived(update.userId)
                    // Persistence: use the timestamp from the update payload.
                    e2eeManager.updateLastLocation(update.userId, update.lat, update.lng, update.timestamp)
                }
                pollPendingInvites()
                locationSource.onFriendsUpdated(e2eeManager.listFriends())
                locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
                updateStatus(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    /**
     * True while something needs a fast poll: the share sheet is open, Bob is on the naming
     * screen, a fresh incoming invite awaits Alice's confirmation, or rapid polling was recently
     * requested.
     */
    fun isRapidPolling(): Boolean {
        val now = clock()
        val recentlyTriggered = now - locationSource.lastRapidPollTrigger.value < 60_000L
        val isSheetShowing = uiStateStore.isInviteSheetShowing.value
        val isNaming = uiStateStore.pendingQrForNaming.value != null
        // Bounded: an incoming invite that's never confirmed or cancelled (app backgrounded,
        // notification ignored, etc.) must not pin the client at the 2s rapid interval forever.
        val setAt = locationSource.pendingInitPayloadSetAt.value
        val hasFreshPendingInit =
            locationSource.pendingInitPayload.value != null && setAt != 0L && now - setAt < PENDING_INIT_RAPID_TIMEOUT_MS
        return isSheetShowing || hasFreshPendingInit || recentlyTriggered || isNaming
    }

    private suspend fun pollPendingInvites() {
        try {
            val results = locationClient.pollPendingInvites()
            if (results.isEmpty()) return

            val pendingInvites = e2eeManager.listPendingInvites()
            val filteredResults =
                results.filter { result ->
                    pendingInvites.any { it.qrPayload.ekPub.contentEquals(result.inviteEkPub) }
                }

            if (filteredResults.isEmpty()) {
                Log.d(TAG, "pollPendingInvites: received ${results.size} results, but none match active pending invites. Ignoring.")
                return
            }

            // If we already have a naming dialog up, don't overwrite it, but the UI
            // will now be able to see all pending invites via allPendingInvites.
            if (locationSource.pendingInitPayload.value == null) {
                val result = filteredResults.first()
                if (result.pairingError != null) {
                    withContext(Dispatchers.Main) {
                        uiStateStore.setInviteSheetShowing(false)
                        updateStatus(Exception(result.pairingError))
                    }
                    return
                }
                val initPayload = result.payload
                Log.d(
                    TAG,
                    "pollPendingInvites: received KeyExchangeInit from ${initPayload.suggestedName} " +
                        "(multipleScans=${result.multipleScansDetected})",
                )
                withContext(Dispatchers.Main) {
                    uiStateStore.setMultipleScansDetected(result.multipleScansDetected)
                    uiStateStore.setInviteSheetShowing(false)
                    locationSource.onPendingInit(initPayload, result.inviteEkPub) // THE FIX: Pass our own EK
                    updateStatus(null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateStatus(e)
        }
    }

    private fun updateStatus(e: Throwable?) {
        if (e == null) {
            locationSource.onConnectionStatus(ConnectionStatus.Ok)
        } else {
            locationSource.onConnectionError(e)
        }
    }

    companion object {
        private const val TAG = "FriendPoller"

        /**
         * How long an unconfirmed incoming invite keeps the client in rapid (2s) polling.
         * Bounds the impact of an invite the user never acts on (see #336) - long enough
         * to notice and respond to a notification, short enough not to run rapid mode forever.
         */
        internal const val PENDING_INIT_RAPID_TIMEOUT_MS = 5 * 60 * 1000L
        internal const val RAPID_POLL_INTERVAL_MS = 2_000L
        internal const val FOREGROUND_POLL_INTERVAL_MS = 10_000L
    }
}
