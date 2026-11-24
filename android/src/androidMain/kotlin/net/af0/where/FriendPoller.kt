package net.af0.where

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.UserStore
import net.af0.where.shared.MR

internal fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Whether [LocationService] can actually run. On API 34+ the OS refuses a `location` foreground
 * service without the permission, so it self-stops; before that it runs without it.
 */
internal fun Context.canRunLocationService(): Boolean =
    hasLocationPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Whether [LocationService] runs in the background: only while we share (where it can run, for
 * its warning when permission is missing). Otherwise [LocationServiceRestartWorker] polls (#481).
 */
internal fun Context.needsBackgroundService(userStore: UserStore): Boolean = userStore.isSharingLocation.value && canRunLocationService()

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
 * foreground service doesn't: from the app's foreground loop (no location permission) and from
 * [LocationServiceRestartWorker] (not sharing, or no permission).
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
                // Housekeeping must never starve the poll, ack and keepalive below; a failure is
                // retried in an hour like a success.
                gate.lastCleanupTime = now
                try {
                    e2eeManager.cleanupExpiredInvites()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Expired-invite cleanup failed: ${e.message}")
                }
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
                val reportedError = pollPendingInvites()
                locationSource.onFriendsUpdated(e2eeManager.listFriends())
                locationSource.onPendingInvitesUpdated(e2eeManager.listPendingInvites())
                // Don't mask an error pollPendingInvites just reported (e.g. a failed handshake).
                if (!reportedError) updateStatus(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}")
            updateStatus(e)
        }
    }

    /**
     * True while something needs a fast poll: the share sheet is open or Bob is on the naming
     * screen (while visible, and for [BACKGROUND_INVITE_WINDOW_MS] after the app is
     * backgrounded, since sharing the link leaves the app just as Bob scans), a fresh incoming
     * invite awaits Alice's confirmation, or rapid polling was recently requested.
     */
    fun isRapidPolling(): Boolean {
        val now = clock()
        // The repository keeps the background mark as a monotonic TimeMark; the policy wants a
        // timestamp on this poller's clock (the same one the other stamps use).
        val backgroundedAt = locationSource.appBackgroundedAt.value
        val setAt = locationSource.pendingInitPayloadSetAt.value
        val trigger = locationSource.lastRapidPollTrigger.value
        return PollPolicy.isRapid(
            PollPolicy.State(
                appForeground = locationSource.isAppInForeground.value,
                backgroundedAtMs = backgroundedAt?.let { now - it.elapsedNow().inWholeMilliseconds },
                inviteSheetShowing = uiStateStore.isInviteSheetShowing.value,
                pendingQrForNaming = uiStateStore.pendingQrForNaming.value != null,
                pendingInitSetAtMs = setAt.takeIf { locationSource.pendingInitPayload.value != null && it != 0L },
                lastRapidTriggerMs = trigger.takeIf { it != 0L },
            ),
            now,
        )
    }

    /** Surfaces incoming handshakes. Returns true if it reported an error to the UI. */
    private suspend fun pollPendingInvites(): Boolean {
        try {
            val results = locationClient.pollPendingInvites()
            if (results.isEmpty()) return false

            val pendingInvites = e2eeManager.listPendingInvites()
            val filteredResults =
                results.filter { result ->
                    pendingInvites.any { it.qrPayload.ekPub == result.inviteEkPub }
                }

            if (filteredResults.isEmpty()) {
                Log.d(TAG, "pollPendingInvites: received ${results.size} results, but none match active pending invites. Ignoring.")
                return false
            }

            // If we already have a naming dialog up, don't overwrite it, but the UI
            // will now be able to see all pending invites via allPendingInvites.
            if (locationSource.pendingInitPayload.value == null) {
                val result = filteredResults.first()
                if (result.pairingError != null) {
                    withContext(Dispatchers.Main) {
                        uiStateStore.setInviteSheetShowing(false)
                        locationSource.onConnectionStatus(ConnectionStatus.Error(StringDesc.Resource(MR.strings.pairing_failed)))
                    }
                    return true
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
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateStatus(e)
            return true
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
        internal const val PENDING_INIT_RAPID_TIMEOUT_MS = PollPolicy.PENDING_INIT_BOUND_MS
        internal const val BACKGROUND_INVITE_WINDOW_MS = PollPolicy.BACKGROUND_INVITE_WINDOW_MS
        internal const val RAPID_POLL_INTERVAL_MS = PollPolicy.RAPID_INTERVAL_MS
        internal const val FOREGROUND_POLL_INTERVAL_MS = PollPolicy.FOREGROUND_INTERVAL_MS
    }
}
