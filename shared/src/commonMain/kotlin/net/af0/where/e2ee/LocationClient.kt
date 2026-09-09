package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.af0.where.model.UserLocation

/** Silence from a friend beyond this is treated as "not currently reading" - see sendLocation(). */
const val UNRESPONSIVE_THRESHOLD_SECONDS = 5 * 60L

/** Throttled send cadence to an unresponsive friend, instead of the normal 30s/heartbeat rate. */
const val UNRESPONSIVE_SEND_INTERVAL_SECONDS = 5 * 60L

/**
 * How long an actively-shared friend must go without ANY send (real or keepalive) before
 * pollFriend's automated keepalive treats it as a stalled heartbeat trigger and steps in - see
 * pollFriend(). Deliberately several multiples of UNRESPONSIVE_SEND_INTERVAL_SECONDS: doPoll()
 * (which runs pollFriend) always precedes the heartbeat's sendLocation() call within the same
 * service wake cycle, so if this used the same interval as sendLocation's own unresponsive-friend
 * retry throttle, the keepalive would win that race on every single cycle - resetting lastSentTs
 * moments before sendLocation's check runs, so the throttled retry looks "not due yet" forever and
 * a real Location never gets through to a friend who's merely unresponsive (not actually stalled).
 * This must stay well outside sendLocation's normal retry cadence so a functioning heartbeat
 * trigger always gets several clean chances before the backstop ever engages.
 */
const val AUTOMATED_KEEPALIVE_BACKSTOP_SECONDS = 3 * UNRESPONSIVE_SEND_INTERVAL_SECONDS

/**
 * Caps [LocationClient.crossCycleBackoffMultiplier] at 2^this (32x). The platform layer applies
 * its own absolute ceiling on top of this (e.g. staying under the 30min maintenance-tier poll
 * interval), so this only needs to bound the shift itself from growing unboundedly during a
 * very long outage.
 */
private const val MAX_BACKOFF_SHIFT = 5

/**
 * Orchestrates the end-to-end encrypted location sharing protocol.
 * Unifies polling, decryption, ratchet rotation, and sending for all platforms.
 */
open class LocationClient(
    baseUrl: String,
    private val store: E2eeManager,
    val mailbox: MailboxClient = KtorMailboxClient,
    var enableAutomatedKeepalives: Boolean = true,
) {
    /** Secondary constructor for Swift/native compatibility. */
    constructor(baseUrl: String, store: E2eeManager) : this(baseUrl, store, KtorMailboxClient)

    private val service = MailboxService(baseUrl, mailbox)

    // Shared by sendLocation(), sendStoppedSharing(), and sendRecoveryKeepalives() - a friend is
    // eligible for any outbound traffic unless individually paused or stale.
    private fun isActiveFriend(
        friend: FriendEntry,
        pausedFriendIds: Set<String>,
    ): Boolean = friend.id !in pausedFriendIds && !friend.isStale

    // Sending fresh GPS fixes every 30s is wasted radio/battery/server-write cost if this
    // friend's client isn't alive to read them - automated keepalives (§5.3) mean any live
    // client sends us *something* at least every UNRESPONSIVE_THRESHOLD_SECONDS regardless of
    // their own sharing state, so silence beyond that is a reasonable proxy for "not currently
    // reading." Throttle to UNRESPONSIVE_SEND_INTERVAL_SECONDS instead of stopping entirely -
    // self-heals the moment they send anything (lastRecvTs updates, next call sees it fresh, no
    // separate recovery/cooldown logic needed). Shared by sendLocation()'s activeFriends filter
    // and sendRecoveryKeepalives() so the two throttle cadences can't drift apart.
    private fun isSendDueForUnresponsiveFriend(
        friend: FriendEntry,
        now: Long,
    ): Boolean {
        val unresponsive = now - friend.lastRecvTs >= UNRESPONSIVE_THRESHOLD_SECONDS
        return !unresponsive || now - friend.lastSentTs >= UNRESPONSIVE_SEND_INTERVAL_SECONDS
    }

    private val friendMutexes = mutableMapOf<String, Mutex>()
    private val silentDropRetries = mutableMapOf<String, Int>()
    private val mutexLock = Mutex()

    // Cross-cycle backoff: a GLOBAL (not per-friend) consecutive-failure counter for
    // repeated send/poll failures across wake cycles, distinct from the per-friend
    // unresponsive-friend throttle above - a server outage hits every friend at once, so per-friend
    // state doesn't fit this failure mode. The platform layer (LocationService) reads
    // [crossCycleBackoffMultiplier] to stretch its wake/doze-alarm interval; this class only
    // tracks the counter and decides what counts toward it.
    private val backoffLock = Mutex()

    // Volatile: crossCycleBackoffMultiplier's getter reads this outside backoffLock (it's a cheap,
    // frequently-polled read from the platform scheduler) - writes are still serialized by the
    // lock, this only ensures a fresh value is visible across threads without acquiring it.
    @kotlin.concurrent.Volatile
    private var consecutiveCrossCycleFailures = 0

    // ConnectException (DNS failure/connection refused/no route) reads as "device has no path to
    // the server at all" - already handled faster by the platform's network-available callback
    // (syncNow()), and counting it here would just delay recovery once connectivity actually
    // returns. A 429 ServerException is the unresponsive-friend throttle's problem (distinct
    // per-friend semantic), not server health, so it's excluded too. Only 5xx is actually a
    // server-health signal - any other 4xx (400/404/413/etc.) is a client-side bug or bad payload
    // that backoff can never fix, and counting it here would throttle every device hitting that
    // bug while masking the real cause as a server outage. Timeouts are excluded entirely: they're
    // ambiguous between an overloaded server and a flaky connection that can't complete a request
    // to this server specifically, and there's no reliable device-side signal to tell those apart.
    private fun isBackoffWorthy(e: Throwable): Boolean =
        when (e) {
            is ServerException -> e.statusCode in 500..599
            else -> false
        }

    private suspend fun recordCrossCycleResult(
        success: Boolean,
        error: Throwable? = null,
    ) {
        backoffLock.withLock {
            if (success) {
                consecutiveCrossCycleFailures = 0
            } else if (error != null && isBackoffWorthy(error)) {
                consecutiveCrossCycleFailures = minOf(consecutiveCrossCycleFailures + 1, MAX_BACKOFF_SHIFT)
            }
        }
    }

    /**
     * Multiplier the platform layer should apply to its normal poll-loop wake/doze-alarm
     * interval - 1 means no backoff. Capping the underlying shift (not the multiplier itself)
     * lets the platform layer apply its own absolute cap without this racing ahead unboundedly.
     */
    val crossCycleBackoffMultiplier: Int
        get() = 1 shl consecutiveCrossCycleFailures

    /**
     * Resets the backoff counter immediately, without waiting for the next successful
     * send/poll. Intended for a strong "try now" signal - e.g. the platform's network-available
     * callback, which already triggers [syncNow] - so a real recovery isn't delayed by a stale
     * backoff computed before connectivity returned.
     */
    suspend fun resetCrossCycleBackoff() {
        backoffLock.withLock { consecutiveCrossCycleFailures = 0 }
    }

    // Lets pollFriend() report its outcome without immediately recording it - poll() and syncNow()
    // both pass an aggregating sink (via withAggregatedCrossCycleResult) so every friend's outcome
    // in one fan-out call collapses into a single recordCrossCycleResult call, instead of each of
    // N parallel friends independently incrementing/resetting the counter. Direct pollFriend()
    // callers that don't opt into aggregation (e.g. tests) get the default, which records
    // immediately per call.
    internal fun interface CrossCycleSink {
        suspend fun record(
            success: Boolean,
            error: Throwable?,
        )
    }

    private val directCrossCycleSink =
        CrossCycleSink { success, error -> recordCrossCycleResult(success, error) }

    /**
     * Runs [block] with a [CrossCycleSink] that buffers every reported outcome instead of
     * recording immediately, then makes exactly ONE [recordCrossCycleResult] call once [block]
     * completes - success wins if any outcome succeeded. Shared by [poll] and [syncNow] so a
     * single wake fanning out to N friends can't inflate/thrash the counter N-fold depending on
     * completion order.
     */
    private suspend fun <T> withAggregatedCrossCycleResult(block: suspend (CrossCycleSink) -> T): T {
        val aggregateMutex = Mutex()
        var aggregateSuccess = false
        var aggregateFailure: Throwable? = null
        val sink =
            CrossCycleSink { success, error ->
                aggregateMutex.withLock {
                    if (success) {
                        aggregateSuccess = true
                    } else if (error != null) {
                        aggregateFailure = error
                    }
                }
            }
        try {
            return block(sink)
        } finally {
            // Record whatever outcomes were aggregated before block() returned OR threw (e.g. a
            // CancellationException propagating out of awaitAll() when the app is backgrounded
            // mid-poll) - otherwise a cancelled cycle records nothing at all, leaving
            // consecutiveCrossCycleFailures stale relative to what actually happened. Run under
            // NonCancellable since recordCrossCycleResult's Mutex.withLock would otherwise throw
            // immediately in an already-cancelled coroutine before it could record anything.
            withContext(NonCancellable) {
                if (aggregateSuccess) {
                    recordCrossCycleResult(success = true)
                } else if (aggregateFailure != null) {
                    recordCrossCycleResult(success = false, error = aggregateFailure)
                }
            }
        }
    }

    private suspend fun getFriendMutex(id: String): Mutex {
        mutexLock.withLock {
            return friendMutexes.getOrPut(id) { Mutex() }
        }
    }

    private val inFlightPolls = mutableSetOf<String>()
    private val inFlightMutex = Mutex()

    private var lastCleanupTime = 0L

    /**
     * Poll all friends and all pending invites.
     */
    suspend fun poll(
        isForeground: Boolean = true,
        pausedFriendIds: Set<String> = emptySet(),
        sharingEnabled: Boolean = true,
    ): List<UserLocation> =
        coroutineScope {
            try {
                processOutboxes()
            } catch (e: Exception) {
                // Ignore
            }

            val now = currentTimeSeconds()
            if (now - lastCleanupTime > 3600) {
                try {
                    store.cleanupExpiredInvites()
                    lastCleanupTime = now
                } catch (e: Exception) {
                    // Ignore
                }
            }

            val friends =
                try {
                    store.listFriends()
                } catch (e: Exception) {
                    store.addDiagnosticEvent("listFriends() failed, skipping poll cycle: ${e.message}")
                    emptyList()
                }

            // Aggregates every friend's poll outcome (from pollFriend's crossCycleSink) into a
            // single recordCrossCycleResult call, instead of each of N parallel friends
            // independently incrementing/resetting the global counter.
            val allUpdates = mutableListOf<UserLocation>()
            withAggregatedCrossCycleResult { aggregatingSink ->
                val deferreds =
                    friends.map { friend ->
                        async {
                            try {
                                val mutex = getFriendMutex(friend.id)
                                mutex.withLock {
                                    val alreadyPolling =
                                        inFlightMutex.withLock {
                                            if (inFlightPolls.contains(friend.id)) {
                                                true
                                            } else {
                                                inFlightPolls.add(friend.id)
                                                false
                                            }
                                        }
                                    if (alreadyPolling) return@async Pair(emptyList<UserLocation>(), null)

                                    try {
                                        // "Paused" for pollFriend's purposes means "sendLocation
                                        // isn't sending this friend anything" - true if they're
                                        // individually paused, or if location sharing is off
                                        // entirely.
                                        val isPaused = !sharingEnabled || friend.id in pausedFriendIds
                                        val updates = pollFriend(friend.id, isPaused, aggregatingSink)
                                        Pair(updates, null)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Pair(emptyList<UserLocation>(), e)
                                    } finally {
                                        inFlightMutex.withLock {
                                            inFlightPolls.remove(friend.id)
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Pair(emptyList<UserLocation>(), e)
                            }
                        }
                    }

                val results = deferreds.awaitAll()
                for ((updates, _) in results) {
                    allUpdates.addAll(updates)
                }
            }

            // Ensure outboxes are processed even if some polls failed
            try {
                processOutboxes()
            } catch (e: Exception) {
                // Ignore
            }

            allUpdates
        }

    suspend fun pollPendingInvites(): List<PendingInviteResult> =
        coroutineScope {
            val pending = store.listPendingInvites()
            pending.map { invite ->
                async {
                    try {
                        val discoveryHex = invite.qrPayload.discoveryToken().toHex()
                        val messages = service.poll(discoveryHex)
                        val inits = messages.filterIsInstance<KeyExchangeInitPayload>()
                        val last = inits.lastOrNull()
                        if (last != null) {
                            val decryptedName =
                                store.decryptSuggestedName(
                                    aliceEkPub = invite.qrPayload.ekPub,
                                    bobEkPub = last.ekPub,
                                    encryptedName = last.encryptedName,
                                )
                            if (decryptedName == null) {
                                store.addDiagnosticEvent("Failed to decrypt suggested_name for invite from discovery=$discoveryHex")
                                PendingInviteResult(
                                    payload = last,
                                    scannerEkPub = last.ekPub,
                                    inviteEkPub = invite.qrPayload.ekPub,
                                    multipleScansDetected = inits.size > 1,
                                    pairingError = "Handshake failed: Cryptographic verification error.",
                                )
                            } else {
                                // Return a copy of the payload with the transient suggestedName field populated for UI consumption.
                                // Alice will use this to pre-fill her naming dialog.
                                val populatedPayload = last.copy(suggestedName = decryptedName)
                                PendingInviteResult(
                                    payload = populatedPayload,
                                    scannerEkPub = last.ekPub,
                                    inviteEkPub = invite.qrPayload.ekPub,
                                    multipleScansDetected = inits.size > 1,
                                )
                            }
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

    suspend fun postKeyExchangeInit(
        friendId: String,
        qr: QrPayload,
        initPayload: KeyExchangeInitPayload,
    ) {
        val discoveryHex = qr.discoveryToken().toHex()
        service.post(discoveryHex, initPayload)

        // WAL: Cleanup the outbox for the newly created friendship
        store.removeFromOutbox(friendId, initPayload.msgId)
    }

    internal suspend fun pollFriend(
        friendId: String,
        isPaused: Boolean = false,
        crossCycleSink: CrossCycleSink = directCrossCycleSink,
    ): List<UserLocation> =
        coroutineScope {
            val resultLocations = mutableListOf<UserLocation>()

            var totalMessagesProcessed = 0
            var tokenFollows = 0
            var stopPolling = false
            var caughtUp = false

            while (!stopPolling && totalMessagesProcessed < MAX_MESSAGES_PER_POLL) {
                val friend = store.getFriend(friendId) ?: break
                val currentToken = friend.session.recvToken.toHex()

                val pollStartMs = currentTimeMillis()

                suspend fun recordPollFailure(e: Throwable) {
                    val elapsedMs = currentTimeMillis() - pollStartMs
                    store.addDiagnosticEvent(
                        "poll($friendId) failed on $currentToken after ${elapsedMs}ms: ${e.message}",
                    )
                    stopPolling = true
                    crossCycleSink.record(false, e)
                }

                val messages =
                    try {
                        service.poll(currentToken).also { crossCycleSink.record(true, null) }
                    } catch (e: WallClockTimeoutCancellationException) {
                        // A wall-clock timeout is an ordinary, expected request failure (same role
                        // as any other network exception) even though it's implemented as a
                        // CancellationException - it must NOT propagate like a real structured-
                        // concurrency cancellation would, or every routine timeout would skip this
                        // friend's updateLastPollTs/processOutbox/keepalive-backstop check below.
                        recordPollFailure(e)
                        continue
                    } catch (e: CancellationException) {
                        // Any OTHER cancellation is a genuine outer shutdown signal - must propagate.
                        throw e
                    } catch (e: Exception) {
                        recordPollFailure(e)
                        continue
                    }

                if (messages.isEmpty()) {
                    stopPolling = true
                    caughtUp = true
                    continue
                }

                try {
                    val result = store.processBatch(friendId, currentToken, messages)
                    if (result == null) {
                        store.addDiagnosticEvent("processBatch($friendId) rejected ${messages.size} message(s) on $currentToken")
                        stopPolling = true
                        continue
                    }

                    var idsToAck = result.processedIds
                    if (idsToAck.isEmpty()) {
                        val retryKey = "$friendId:$currentToken"
                        val currentRetries = (silentDropRetries[retryKey] ?: 0) + 1
                        silentDropRetries[retryKey] = currentRetries

                        if (currentRetries >= MAX_SILENT_DROP_RETRIES) {
                            store.addDiagnosticEvent("force-ACK $friendId after $currentRetries silent drops on $currentToken")
                            idsToAck = messages.map { it.msgId }
                            silentDropRetries.remove(retryKey)
                        }
                        // If we couldn't process any messages and it's not a force-ACK, stop to avoid looping.
                        if (idsToAck.isEmpty()) {
                            stopPolling = true
                        }
                    } else {
                        silentDropRetries.remove("$friendId:$currentToken")
                    }

                    if (idsToAck.isNotEmpty()) {
                        try {
                            service.ackIds(currentToken, idsToAck)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }

                    resultLocations.addAll(
                        result.decryptedLocations.map { loc ->
                            UserLocation(
                                userId = friendId,
                                lat = loc.lat,
                                lng = loc.lng,
                                timestamp = loc.ts,
                            )
                        },
                    )

                    totalMessagesProcessed += messages.size

                    if (result.hadStateUpdate) {
                        val friendAfterUpdate = store.getFriend(friendId) ?: break
                        val nextToken = friendAfterUpdate.session.recvToken.toHex()
                        if (nextToken != currentToken) {
                            tokenFollows++
                            if (tokenFollows >= MAX_TOKEN_FOLLOWS_PER_POLL) {
                                stopPolling = true
                            }
                            // Continue to next loop iteration with new token
                        }
                    }
                } catch (e: Exception) {
                    store.addDiagnosticEvent("pollFriend($friendId) failed processing batch on $currentToken: ${e.message}")
                    stopPolling = true
                }
            }

            try {
                store.updateLastPollTs(friendId, currentTimeSeconds())
                store.updateIsCaughtUp(friendId, caughtUp)
            } catch (e: Exception) {
            }

            // Recovery: process any pending outbox messages for this friend. A message stuck
            // here blocks encryptAndAdvance() (see sendMessageToFriendInternal) indefinitely -
            // sendToken freezes while lastPollTs/lastRecvTs keep advancing normally via the
            // unrelated incoming path above, so this failure must be logged, not swallowed
            // silently: without it, a stuck outbox looks identical to a healthy friend in every
            // diagnostic except recvTok/sendTok visibly diverging, and the previous behavior gave
            // no signal at all that something needed a restart to unstick.
            try {
                processOutbox(friendId)
            } catch (e: Exception) {
                store.addDiagnosticEvent(
                    "pollFriend($friendId): outbox recovery flush failed: ${e.message}",
                    coalesceKey = "pollFriend($friendId): outbox recovery flush failed",
                )
            }

            val friendAfter = store.getFriend(friendId)
            if (friendAfter != null) {
                val now = currentTimeSeconds()

                // Automated Keepalive Rule: two cases, on two different clocks off the same
                // lastSentTs field.
                //  - Paused/not-sharing: sendLocation() never sends this friend anything, so this
                //    is the sole source of their traffic. Paced to UNRESPONSIVE_SEND_INTERVAL_SECONDS
                //    (same cadence as sendLocation's own unresponsive-friend retry) so a passive
                //    observer of send timing can't distinguish "sharing paused/off" from "sharing
                //    but friend unresponsive" (§7.4/line 114 of the protocol spec - both
                //    deliberately bucketed together to avoid a traffic-analysis leak). Also what
                //    lets a one-way listener (who only receives, never sends back) still keep
                //    hearing from us, so their session doesn't go stale after ACK_TIMEOUT_SECONDS.
                //  - Actively sharing: sendLocation() is normally the sole source of this friend's
                //    traffic, so the keepalive must stay out of its way entirely under normal
                //    operation - it only exists as a backstop for when the platform-side trigger
                //    that's supposed to call sendLocation() on a heartbeat cadence stalls or is
                //    delayed for multiple cycles (e.g. a stuck background wake source leaving a
                //    stationary device silent for hours - see the "here since" background-
                //    triggering investigation). This MUST use a materially longer threshold
                //    (AUTOMATED_KEEPALIVE_BACKSTOP_SECONDS) than sendLocation's own retry interval:
                //    doPoll() (which runs pollFriend) always precedes the heartbeat's
                //    sendLocation() call within the same service wake cycle, so if both used the
                //    same threshold, this keepalive would win that race on every single cycle -
                //    resetting lastSentTs moments before sendLocation's own throttle check runs,
                //    permanently starving a merely-unresponsive (not actually stalled) friend of
                //    real Location updates in favor of empty Keepalives, forever.
                val weAreSilent = now - friendAfter.lastSentTs >= UNRESPONSIVE_SEND_INTERVAL_SECONDS
                val heartbeatTriggerStalled = now - friendAfter.lastSentTs >= AUTOMATED_KEEPALIVE_BACKSTOP_SECONDS

                // Check outbox to avoid redundant keepalives.
                val dueForAutomatedKeepalive =
                    enableAutomatedKeepalives &&
                        (if (isPaused) weAreSilent else heartbeatTriggerStalled) &&
                        !friendAfter.isStale && friendAfter.isConfirmed &&
                        store.getOutbox(friendId).isEmpty()

                if (dueForAutomatedKeepalive) {
                    try {
                        sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }

            resultLocations
        }

    // Runs [block] for each friend in parallel, holding that friend's mutex, swallowing
    // per-friend failures (but not cancellation) so one friend's error can't block the rest.
    // Shared by syncNow(), processOutboxes(), sendStoppedSharing(), and sendRecoveryKeepalives().
    private suspend fun forEachFriendParallel(
        friends: List<FriendEntry>,
        block: suspend (FriendEntry) -> Unit,
    ) {
        coroutineScope {
            friends.map { friend ->
                async {
                    try {
                        val mutex = getFriendMutex(friend.id)
                        mutex.withLock { block(friend) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // ignore per-friend failures
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun syncNow(
        pausedFriendIds: Set<String> = emptySet(),
        sharingEnabled: Boolean = true,
    ) {
        // Aggregated the same way as poll() - syncNow() fans out to every friend in
        // parallel just like poll() does, so it's exposed to the same N-fold inflation risk, and
        // it's also the call the network-available callback makes right after resetting the
        // counter to 0 - re-inflating it to N in one shot on a flapping connection would defeat
        // that reset.
        withAggregatedCrossCycleResult { sink ->
            forEachFriendParallel(store.listFriends()) { friend ->
                processOutbox(friend.id)
                val isPaused = !sharingEnabled || friend.id in pausedFriendIds
                pollFriend(friend.id, isPaused, sink)
            }
        }
    }

    suspend fun processOutboxes() {
        val friends = runCatching { store.listFriends() }.getOrElse { return }
        forEachFriendParallel(friends) { friend -> processOutbox(friend.id) }
    }

    /**
     * Delivers everything queued for [friendId], stopping (and letting the exception propagate)
     * on the first failure to preserve delivery order - a later message must never be delivered
     * before an earlier one that's still stuck. Deliberately doesn't catch here: callers that
     * only care about best-effort delivery (e.g. [processOutboxes]) catch around their own call
     * site instead, so a caller that DOES care whether delivery actually happened (e.g.
     * [sendMessageToFriendInternal], and transitively `LocationService.sendLocationIfNeeded`'s
     * retry/backoff and connection-status reporting) can see real failures instead of a silent
     * false success. See https://github.com/danmarg/where/issues/343.
     */
    private suspend fun processOutbox(friendId: String) {
        val outbox = store.getOutbox(friendId)
        if (outbox.isEmpty()) return

        for (outboxMsg in outbox) {
            service.post(outboxMsg.token, outboxMsg.payload)
            store.removeFromOutbox(friendId, outboxMsg.msgId)
        }
    }

    // A friend whose outbox read fails is treated as not-stuck (falls through to the normal
    // throttle check) rather than the exception aborting sendLocation() for every friend - this
    // is per-friend I/O, so like the per-friend send path it must not take down the whole batch
    // over one friend's store error.
    private suspend fun isSendEligible(
        friend: FriendEntry,
        pausedFriendIds: Set<String>,
        now: Long,
    ): Boolean {
        if (!isActiveFriend(friend, pausedFriendIds)) return false
        // A message already stuck in this friend's outbox isn't "new" traffic to someone who
        // isn't reading - it's retrying delivery of something already committed
        // (encryptAndAdvance() already ran, ratchet already advanced). Always retry it at the
        // normal cadence; the throttle below only gates the DECISION to generate a fresh ping
        // for a quiet friend, not retries of one already generated. Without this, a single
        // failed send to an unresponsive friend - a very likely combination - would wait a full
        // UNRESPONSIVE_SEND_INTERVAL_SECONDS before even trying again, since lastSentTs is set
        // at generation time regardless of delivery success.
        val hasStuckOutbox = runCatching { store.getOutbox(friend.id) }.getOrNull()?.isNotEmpty() == true
        return hasStuckOutbox || isSendDueForUnresponsiveFriend(friend, now)
    }

    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
        stationary: Boolean = false,
        recordCrossCycleOutcome: Boolean = true,
    ) {
        coroutineScope {
            val now = currentTimeSeconds()
            val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = now, stationary = stationary)
            // Sequential: each friend's eligibility check is a local-DB read serialized behind
            // E2eeStore's single storeLock anyway, so fanning these out via async/awaitAll (like
            // the network send phase below) buys no real concurrency, just dispatch overhead.
            val active = store.listFriends().filter { isActiveFriend(it, pausedFriendIds) }
            val activeFriends = active.filter { isSendEligible(it, pausedFriendIds, now) }

            // Distinct from "no active friends" (paused/no friends yet - expected, not worth a
            // diagnostic event): every active friend was individually eligible for OUTBOUND
            // traffic but got filtered out by the unresponsive-friend throttle. sendLocation()
            // otherwise returns normally in this case (see below), which previously meant
            // LocationService.sendLocationIfNeeded logged "OK" and cleared its status for a send
            // that reached nobody - see #347.
            if (active.isNotEmpty() && activeFriends.isEmpty()) {
                store.addDiagnosticEvent(
                    "sendLocation: all ${active.size} active friend(s) throttled as unresponsive - nothing sent",
                    coalesceKey = "sendLocation: all",
                )
            }

            // Parallel send to all active friends to minimize radio wake time.
            // Exceptions are caught per-friend so one failure doesn't block updates to others.
            val deferreds =
                activeFriends.map { friend ->
                    async {
                        runCatching {
                            val mutex = getFriendMutex(friend.id)
                            mutex.withLock {
                                sendMessageToFriendInternal(friend.id, payload)
                            }
                        }.onFailure {
                            // A wall-clock timeout is an ordinary, expected request failure (same
                            // role as any other network exception) even though it's implemented as
                            // a CancellationException - see pollFriend's identical distinction.
                            // Rethrowing it here would cancel the whole coroutineScope (killing
                            // every OTHER friend's in-flight send too) and skip the aggregation
                            // below entirely, so a send-side hang would never count toward
                            // cross-cycle backoff. Any other CancellationException is a
                            // genuine outer shutdown signal and must still propagate.
                            if (it is CancellationException && it !is WallClockTimeoutCancellationException) throw it
                        }
                    }
                }

            val results = deferreds.awaitAll()
            if (recordCrossCycleOutcome) {
                // One recordCrossCycleResult call for the whole sendLocation() invocation, not one
                // per friend - otherwise a single wake with N friends inflates the counter N-fold
                // and the exact multiplier depends on parallel completion order. Any success at
                // all means the server is reachable, so it wins over a co-occurring per-friend
                // failure. Callers that make their own multi-attempt retry loop across several
                // sendLocation() calls (e.g. LocationService.sendLocationIfNeeded) instead pass
                // recordCrossCycleOutcome=false and aggregate across attempts themselves via
                // [recordCrossCycleAttempt], so N retries don't inflate the counter N-fold either.
                val anySuccess = results.any { it.isSuccess }
                val representativeFailure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                if (anySuccess) {
                    recordCrossCycleResult(success = true)
                } else if (representativeFailure != null) {
                    recordCrossCycleResult(success = false, error = representativeFailure)
                }
            }
            val successCount = results.count { it.isSuccess }
            val failCount = results.count { it.isFailure }

            // If we failed to send to ANYONE but had at least one target, propagate the last
            // error - converting a wall-clock timeout (still, at the type level, a
            // CancellationException - see above) to the public TimeoutException it already stands
            // in for, so a caller's `catch (e: CancellationException)` (a reasonable thing for
            // platform code to have, to avoid swallowing genuine structured-concurrency shutdown)
            // can't mistake it for one and skip its own failure handling - see
            // LocationService.sendLocationIfNeeded.
            if (successCount == 0 && failCount > 0) {
                val failure = results.first { it.isFailure }.exceptionOrNull()!!
                throw if (failure is WallClockTimeoutCancellationException) TimeoutException("Wall-clock timeout", failure) else failure
            }
        }
    }

    /**
     * Records a cross-cycle send/poll outcome on behalf of a caller running its own multi-attempt
     * retry loop across several [sendLocation] calls (each with `recordCrossCycleOutcome=false`) -
     * e.g. LocationService.sendLocationIfNeeded's in-wake retry loop. Exposed publicly
     * since that retry loop lives in platform code, outside this class.
     */
    suspend fun recordCrossCycleAttempt(
        success: Boolean,
        error: Throwable? = null,
    ) {
        recordCrossCycleResult(success, error)
    }

    suspend fun sendLocationToFriend(
        friendId: String,
        lat: Double,
        lng: Double,
        stationary: Boolean = false,
    ) {
        val mutex = getFriendMutex(friendId)
        mutex.withLock {
            val ts = currentTimeSeconds()
            val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts, stationary = stationary)
            sendMessageToFriendInternal(friendId, payload)
        }
    }

    /**
     * Enqueue a StoppedSharing message to every active (non-paused, non-stale) friend.
     * This only writes to the WAL outbox; the existing processOutboxes loop handles delivery.
     * Keepalives continue afterwards so the peer's session doesn't go stale.
     */
    open suspend fun sendStoppedSharing(pausedFriendIds: Set<String> = emptySet()) {
        val payload = MessagePlaintext.StoppedSharing(ts = currentTimeSeconds())
        val activeFriends = store.listFriends().filter { isActiveFriend(it, pausedFriendIds) }
        forEachFriendParallel(activeFriends) { friend -> sendMessageToFriendInternal(friend.id, payload) }
    }

    /**
     * Enqueue a StoppedSharing message to a single friend (used by the per-friend expiry watcher).
     * Same WAL-outbox semantics as [sendStoppedSharing]; Keepalives continue afterwards.
     */
    open suspend fun sendStoppedSharingToFriend(friendId: String) {
        val payload = MessagePlaintext.StoppedSharing(ts = currentTimeSeconds())
        val mutex = getFriendMutex(friendId)
        mutex.withLock {
            try {
                sendMessageToFriendInternal(friendId, payload)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    suspend fun sendKeepalive(friendId: String) {
        val mutex = getFriendMutex(friendId)
        mutex.withLock {
            sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
        }
    }

    /**
     * RECOVERY (§5.3): send a keepalive to every active (non-paused, non-stale) friend, e.g. when
     * we have no GPS fix but are still sharing. Uses the same unresponsive-friend throttle as
     * [sendLocation] so a silent friend doesn't get spammed every RECOVERY cycle, and skips
     * friends with a non-empty outbox - a pending Location/Keepalive already covers this cycle's
     * "let them know we're still there" duty, so generating a redundant Keepalive on top of it
     * would just be belt-and-suspenders traffic.
     */
    suspend fun sendRecoveryKeepalives(pausedFriendIds: Set<String> = emptySet()) {
        val now = currentTimeSeconds()
        val activeFriends =
            store.listFriends().filter {
                isActiveFriend(it, pausedFriendIds) && isSendDueForUnresponsiveFriend(it, now)
            }
        forEachFriendParallel(activeFriends) { friend ->
            if (store.getOutbox(friend.id).isEmpty()) {
                sendMessageToFriendInternal(friend.id, MessagePlaintext.Keepalive())
            }
        }
    }

    private suspend fun sendMessageToFriendInternal(
        friendId: String,
        payload: MessagePlaintext,
    ) {
        // WAL Safety: If the outbox is not empty, we MUST NOT generate a new message.
        // We instead retry the existing outbox. This bounds the queue and prevents nonce reuse.
        // processOutbox() now throws on failure instead of swallowing it (see its doc), so a
        // still-stuck message propagates straight out of this function - callers (ultimately
        // LocationService.sendLocationIfNeeded) see the real failure instead of a silent
        // false success, and we never reach encryptAndAdvance() below for a new payload.
        // This read is authoritative and must happen under getFriendMutex (held by our caller) -
        // deliberately NOT reused from sendLocation()'s filter, which runs unlocked and can race
        // with a concurrent poll()/keepalive enqueueing into this friend's outbox.
        if (store.getOutbox(friendId).isNotEmpty()) {
            processOutbox(friendId)
        }

        store.encryptAndAdvance(friendId, payload)

        // We trigger sequential outbox processing for this friend.
        // This ensures messages are sent in order (0, 1, 2...) even if multiple calls happen rapidly.
        processOutbox(friendId)
    }
}
