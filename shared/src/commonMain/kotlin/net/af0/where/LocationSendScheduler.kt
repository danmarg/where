package net.af0.where

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * A fix this far from the last delivered one is worth sending before the heartbeat: about a
     * block, so a walker's pin updates roughly once a minute. Not much lower: urban GPS jitter
     * of 20-50 m would then look like movement.
     */
    val minDistanceMeters: Double = 100.0,
    /** Fixes less accurate than this are ignored: their jitter could pass for movement. */
    val maxAccuracyMeters: Double = 100.0,
    /** Delays before the retries of a failed send. */
    val retryDelaysMs: List<Long> = listOf(5_000L, 20_000L),
)

/** The broadcast half of [LocationClient] the scheduler drives (an interface so tests can fake it). */
interface BroadcastSender {
    fun sharingStamp(): Long

    @Throws(Exception::class)
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

/** What [LocationSendScheduler.wakeAndDrain] left behind. */
sealed class DrainResult {
    /** A send was delivered during the drain (the latest fix, as of its last attempt). */
    data object Sent : DrainResult()

    /** A send ran during the drain and failed all its attempts; it backs off (see SendPolicy). */
    data object Failed : DrainResult()

    /** Something is due but held back by the send interval or a backoff, for [inMs] more. */
    data class Pending(val inMs: Long) : DrainResult()

    /** Nothing needs sending. */
    data object Idle : DrainResult()

    /** The caller's maxWaitMs ran out with a send still in flight; it carries on without them. */
    data object InFlight : DrainResult()
}

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
    private val onClose: () -> Unit = {},
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

    // Sends settled so far, and how the last one went (see wakeAndDrain).
    private var settled = 0L
    private var lastSettledOk = false

    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val busy = MutableStateFlow(false)
    private var worker: Job? = null
    private var closed = false

    /** Starts the worker; also restarts it if it ever ended (every event does this too). */
    fun start() {
        if (closed || worker?.isActive == true) return
        worker =
            scope.launch {
                try {
                    run()
                } finally {
                    // Nobody waiting in wakeAndDrain may wait on a worker that's gone.
                    busy.value = false
                }
            }
    }

    fun onFix(
        lat: Double,
        lng: Double,
        accuracyMeters: Double? = null,
    ) {
        // Negative accuracy means invalid (CoreLocation's convention).
        if (accuracyMeters != null && (accuracyMeters < 0 || accuracyMeters > policy.maxAccuracyMeters)) return
        latest = Fix(lat, lng)
        kick()
    }

    /** Send the latest fix now, skipping the interval but not waiting behind a send in flight. */
    fun requestForced() {
        forced = true
        kick()
    }

    /** True when a wake should arm the heartbeat (see [wakeAndDrain]); a platform may fetch a fresh fix first. */
    fun isHeartbeatDue(): Boolean {
        val now = clock()
        clampToNow(now)
        // Never attempted: due, whatever the clock's origin (a monotonic clock starts near 0 at boot).
        return lastAttempt == 0L || now - lastAttempt >= policy.heartbeatMs - policy.heartbeatSlackMs
    }

    /**
     * A wake: arm the heartbeat if it's due, then suspend until no send is in flight and none is
     * due *now* - so the caller can hold a wake lock or background task across the send. It
     * doesn't wait out the send interval: a send held back by it is reported as [DrainResult.Pending],
     * and goes out on the worker's own timer or the next wake.
     */
    suspend fun wakeAndDrain(maxWaitMs: Long? = null): DrainResult {
        if (isHeartbeatDue()) heartbeatArmed = true
        val settledBefore = settled
        kick()
        // A bounded wait doesn't cancel the send: a caller whose background time is running out
        // stops waiting, and the send carries on (or freezes with the app and retries on resume).
        val drained =
            if (maxWaitMs == null) {
                busy.first { !it || worker?.isActive != true }
            } else {
                withTimeoutOrNull(maxWaitMs) { busy.first { !it || worker?.isActive != true } }
            }
        // On timeout, a caller outside the scheduler's thread (Swift) may resume off it: return
        // without touching any state.
        if (drained == null) return DrainResult.InFlight
        if (settled != settledBefore) return if (lastSettledOk) DrainResult.Sent else DrainResult.Failed
        val wait = nextDueIn()
        return if (wait == null) DrainResult.Idle else DrainResult.Pending(maxOf(wait, 0L))
    }

    /**
     * The platform sent a keepalive instead (no fix to send). It was a write, so it counts as this
     * heartbeat's attempt either way - the next wake mustn't force a GPS fix straight away.
     */
    fun noteKeepalive(success: Boolean) {
        val now = clock()
        lastAttempt = now
        if (success) lastSuccess = now
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

    /** Stops the worker for good (a platform that owns the scope may cancel that instead). */
    fun close() {
        closed = true
        worker?.cancel()
        busy.value = false
        onClose()
    }

    private fun kick() {
        parked = false
        start()
        busy.value = true
        signal.trySend(Unit)
    }

    // A clock that went backwards would leave these in the future, shutting the gate and the
    // heartbeat until it caught up.
    private fun clampToNow(now: Long) {
        if (lastAttempt > now) lastAttempt = now
        if (backoffUntil > now + policy.heartbeatMs) backoffUntil = now + policy.heartbeatMs
    }

    private fun settle(
        reason: SendReason,
        success: Boolean,
        sinceLastSuccessMs: Long?,
        error: Throwable?,
    ) {
        settled++
        lastSettledOk = success
        notify(reason, success, sinceLastSuccessMs, error)
    }

    private fun notify(
        reason: SendReason,
        success: Boolean,
        sinceLastSuccessMs: Long?,
        error: Throwable?,
    ) {
        // A listener that throws must not turn a delivered send into a failed one.
        runCatching { listener?.onSendSettled(reason, success, sinceLastSuccessMs, error) }
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
            } catch (e: Throwable) {
                // Only our own cancellation ends the worker; anything else would silently stop all
                // sharing, so treat it like a failed send.
                currentCoroutineContext().ensureActive()
                backoffUntil = clock() + policy.heartbeatMs
                settle(SendReason.LOCATION, false, null, e)
            }
        }
    }

    /** Milliseconds until a send is due (0 or less: now), or null if none is. */
    private fun nextDueIn(): Long? {
        val fix = latest
        if (parked || fix == null) return null
        val now = clock()
        clampToNow(now)
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
                if (attempt > 0) settle(reason, false, sinceLastSuccess, lastError)
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
                settle(reason, true, sinceLastSuccess, null)
                return
            } catch (e: Throwable) {
                // Only our own cancellation propagates. A CancellationException from below - a
                // wall-clock request timeout is one - is a failed attempt like any other.
                currentCoroutineContext().ensureActive()
                lastError = e
            }
        }
        if (epoch != sessionEpoch) return
        backoffUntil = lastAttempt + policy.heartbeatMs
        settle(reason, false, sinceLastSuccess, lastError)
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

/** Elapsed time that keeps counting while the device sleeps; platforms supply it. */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/** The policy everything uses unless a test says otherwise (exported for Swift, which can't see default arguments). */
val defaultSendPolicy = SendPolicy()

/**
 * A scheduler confined to the main thread, for platforms (iOS) that don't own a coroutine scope.
 * Call [LocationSendScheduler.close] when done with it.
 */
fun createMainSendScheduler(
    sender: BroadcastSender,
    gate: SendGate,
    clock: MonotonicClock,
    listener: SendListener?,
): LocationSendScheduler {
    val scope = MainScope()
    return LocationSendScheduler(sender, gate, { clock.nowMs() }, scope, defaultSendPolicy, listener, onClose = { scope.cancel() })
}
