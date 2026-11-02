package net.af0.where

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import net.af0.where.e2ee.LocationClient
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** When broadcast location sends happen. One policy for both platforms. */
data class SendPolicy(
    /** Minimum time between send attempts: every attempt is a mailbox write. */
    val minIntervalMs: Long = 30_000L,
    /** A location goes out at least this often, changed or not. */
    val heartbeatMs: Long = 300_000L,
    /** How early a wake may arm the heartbeat, to absorb timer jitter. */
    val heartbeatSlackMs: Long = 30_000L,
    /** A fix this far from the last delivered one is worth sending before the heartbeat. */
    val minDistanceMeters: Double = 200.0,
    /** Fixes less accurate than this are ignored (jitter could look like movement). */
    val maxAccuracyMeters: Double = 200.0,
    /** Delays before the retries of a failed send. */
    val retryDelaysMs: List<Long> = listOf(5_000L, 20_000L),
)

/** The broadcast half of [LocationClient] the scheduler drives (an interface so tests can fake it). */
interface BroadcastSender {
    fun sharingStamp(): Long

    suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String>,
        stationary: Boolean,
        stamp: Long,
    )
}

fun LocationClient.asBroadcastSender(): BroadcastSender {
    val client = this
    return object : BroadcastSender {
        override fun sharingStamp() = client.sharingStamp()

        override suspend fun sendLocation(
            lat: Double,
            lng: Double,
            pausedFriendIds: Set<String>,
            stationary: Boolean,
            stamp: Long,
        ) = client.sendLocation(lat, lng, pausedFriendIds, stationary = stationary, stamp = stamp)
    }
}

/** Platform state the scheduler reads at send time. Called on the scheduler's thread. */
interface SendGate {
    /** Sharing is on and location permission is held, so a Location may be broadcast. */
    fun canSend(): Boolean

    fun pausedFriendIds(): Set<String>

    /** Whether we're stationary right now ("here since"), read when each send goes out. */
    fun stationary(): Boolean
}

enum class SendReason { LOCATION, HEARTBEAT, FORCED }

/** Hears about each send once it's settled - after its retries, if any. */
fun interface SendListener {
    fun onSendSettled(
        reason: SendReason,
        success: Boolean,
        sinceLastSuccessMs: Long?,
        error: Throwable?,
    )
}

/**
 * The only sender of broadcast Locations. Platforms report events - a fix, a wake, a forced
 * request - and one worker coroutine decides when to send the latest fix. Because nothing else
 * sends, sends never overlap, the last fix isn't lost to the throttle (it's still the latest
 * when the window opens), and nothing is sent twice.
 *
 * Confined to [scope]'s thread, which must be single-threaded (the main thread on both
 * platforms): call every method from it. Per-friend sends (a new friend, an unpause) aren't
 * broadcasts and stay with the platform.
 */
class LocationSendScheduler(
    private val client: BroadcastSender,
    private val gate: SendGate,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val policy: SendPolicy = SendPolicy(),
    private val listener: SendListener? = null,
) {
    private data class Fix(val lat: Double, val lng: Double)

    private data class Sent(val fix: Fix, val stationary: Boolean)

    private var latest: Fix? = null
    private var delivered: Sent? = null
    private var forced = false
    private var heartbeatArmed = false

    // 0 = never. lastAttempt also counts keepalives, so the heartbeat isn't due right after one.
    private var lastAttempt = 0L
    private var lastSuccess = 0L

    // After a send fails all its retries, nothing but a forced request or network restore goes
    // before this - so being offline costs one round of attempts per heartbeat, not a loop.
    private var backoffUntil = 0L

    // After a send was skipped (no permission, sharing off), wait for the next event rather than
    // re-evaluating the same state.
    private var parked = false

    // Bumped by reset(): a send settling from before it doesn't touch the new session's state.
    private var epoch = 0

    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val busy = MutableStateFlow(false)
    private var worker: Job? = null

    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch { run() }
    }

    fun onFix(
        lat: Double,
        lng: Double,
        accuracyMeters: Double? = null,
    ) {
        if (accuracyMeters != null && accuracyMeters > policy.maxAccuracyMeters) return
        latest = Fix(lat, lng)
        kick()
    }

    /** Send the latest fix now, skipping the interval but not waiting behind a send in flight. */
    fun requestForced() {
        forced = true
        kick()
    }

    /** True when a wake should arm the heartbeat (see [wakeAndDrain]); a platform may fetch a fresh fix first. */
    fun isHeartbeatDue(): Boolean = clock() - lastAttempt >= policy.heartbeatMs - policy.heartbeatSlackMs

    /**
     * A wake: arm the heartbeat if it's due, then return once nothing is due now and no send is
     * in flight - so the caller can hold a wake lock or background task across the send.
     */
    suspend fun wakeAndDrain() {
        if (isHeartbeatDue()) heartbeatArmed = true
        kick()
        busy.first { !it }
    }

    /** The platform sent a keepalive instead (no fix to send): it counts as this heartbeat. */
    fun noteKeepalive() {
        val now = clock()
        lastAttempt = now
        lastSuccess = now
        heartbeatArmed = false
    }

    /** The network came back: a send that failed may retry now rather than after the backoff. */
    fun onNetworkRestored() {
        backoffUntil = 0L
        kick()
    }

    /** Sharing stopped: forget the session, including a send settling from before. */
    fun reset() {
        epoch++
        latest = null
        delivered = null
        forced = false
        heartbeatArmed = false
        backoffUntil = 0L
        parked = false
        kick()
    }

    private fun kick() {
        parked = false
        busy.value = true
        signal.trySend(Unit)
    }

    private suspend fun run() {
        while (true) {
            val wait = nextDueIn()
            if (wait == null || wait > 0) {
                busy.value = false
                if (wait == null) {
                    signal.receive()
                } else {
                    select {
                        signal.onReceive {}
                        onTimeout(wait) {}
                    }
                }
                continue
            }
            busy.value = true
            try {
                sendSequence()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Nothing may end the worker: that would silently stop all sharing. Treat it
                // like a failed send.
                backoffUntil = clock() + policy.heartbeatMs
                listener?.onSendSettled(SendReason.LOCATION, false, null, e)
            }
        }
    }

    /** Milliseconds until a send is due (0 or less: now), or null if none is. */
    private fun nextDueIn(): Long? {
        val fix = latest
        if (parked || fix == null) return null
        val now = clock()
        if (forced) return 0
        val gateOpens = maxOf(if (lastAttempt == 0L) 0L else lastAttempt + policy.minIntervalMs, backoffUntil)
        val sent = delivered
        val changed =
            sent == null ||
                sent.stationary != gate.stationary() ||
                distanceMeters(sent.fix, fix) >= policy.minDistanceMeters
        return if (heartbeatArmed || changed) gateOpens - now else null
    }

    private suspend fun sendSequence() {
        val reason =
            when {
                forced -> SendReason.FORCED
                heartbeatArmed -> SendReason.HEARTBEAT
                else -> SendReason.LOCATION
            }
        val sessionEpoch = epoch
        val sinceLastSuccess = if (lastSuccess == 0L) null else clock() - lastSuccess
        var lastError: Throwable? = null
        for (attempt in 0..policy.retryDelaysMs.size) {
            if (attempt > 0) delay(policy.retryDelaysMs[attempt - 1])
            if (epoch != sessionEpoch) return
            // Each attempt sends whatever is latest by now.
            val fix = latest ?: return
            val stationary = gate.stationary()
            // Before reading the sharing state: a stop issued after it drops this Location for
            // that friend rather than letting it follow the stop (LocationClient.sharingStamp).
            val stamp = client.sharingStamp()
            if (!gate.canSend()) {
                // Nothing written, so not an attempt: once allowed again, it goes straight out.
                parked = true
                return
            }
            forced = false
            heartbeatArmed = false
            lastAttempt = clock()
            try {
                client.sendLocation(fix.lat, fix.lng, gate.pausedFriendIds(), stationary = stationary, stamp = stamp)
                if (epoch != sessionEpoch) return
                delivered = Sent(fix, stationary)
                lastSuccess = clock()
                backoffUntil = 0L
                listener?.onSendSettled(reason, true, sinceLastSuccess, null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (epoch != sessionEpoch) return
        backoffUntil = lastAttempt + policy.heartbeatMs
        listener?.onSendSettled(reason, false, sinceLastSuccess, lastError)
    }

    private fun distanceMeters(
        a: Fix,
        b: Fix,
    ): Double {
        val toRad = PI / 180
        val dLat = (b.lat - a.lat) * toRad
        val dLng = (b.lng - a.lng) * toRad
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat * toRad) * cos(b.lat * toRad) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
