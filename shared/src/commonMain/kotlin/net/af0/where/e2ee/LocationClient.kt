package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** A removed friend's undelivered messages are retried this long - the server's own retention. */
const val ORPHANED_OUTBOX_RETENTION_MILLIS = FriendEntry.ACK_TIMEOUT_SECONDS * 1000

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

    private suspend fun getFriendMutex(id: String): Mutex {
        mutexLock.withLock {
            return friendMutexes.getOrPut(id) { Mutex() }
        }
    }

    // Orders sendLocation calls against stops. The receiver takes the sharing state from send
    // order, so a Location that read the pause state before a stop but reached the friend's
    // mutex after it would otherwise land after the stop and show the friend as sharing again.
    private var sendSeq = 0L
    private val lastStopSeq = mutableMapOf<String, Long>()

    private suspend fun nextSendSeq(): Long = mutexLock.withLock { ++sendSeq }

    /** Call under [friendId]'s mutex, once its StoppedSharing is queued. */
    private suspend fun recordStop(friendId: String) = mutexLock.withLock { lastStopSeq[friendId] = ++sendSeq }

    /** Whether a stop to [friendId] was queued after the sendLocation call numbered [callSeq] began. */
    private suspend fun stoppedSince(
        friendId: String,
        callSeq: Long,
    ): Boolean = mutexLock.withLock { (lastStopSeq[friendId] ?: 0L) > callSeq }

    private val inFlightPolls = mutableSetOf<String>()
    private val inFlightMutex = Mutex()

    private var lastCleanupTime = 0L

    /**
     * Poll all friends and all pending invites.
     */
    @Throws(Exception::class)
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
                                    // "Paused" for pollFriend's purposes means "sendLocation isn't
                                    // sending this friend anything" - true if they're individually
                                    // paused, or if location sharing is off entirely.
                                    val isPaused = !sharingEnabled || friend.id in pausedFriendIds
                                    val updates = pollFriend(friend.id, isPaused)
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
            val allUpdates = mutableListOf<UserLocation>()

            for ((updates, _) in results) {
                allUpdates.addAll(updates)
            }

            // Ensure outboxes are processed even if some polls failed
            try {
                processOutboxes()
            } catch (e: Exception) {
                // Ignore
            }

            allUpdates
        }

    @Throws(Exception::class)
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

    @Throws(Exception::class)
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

                fun recordPollFailure(e: Throwable) {
                    val elapsedMs = currentTimeMillis() - pollStartMs
                    store.addDiagnosticEvent(
                        "poll($friendId) failed on $currentToken after ${elapsedMs}ms: ${e.message}",
                    )
                    stopPolling = true
                }

                val messages =
                    try {
                        service.poll(currentToken)
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

                    // The receive token rotated (RetireToken): the next iteration polls the new one.
                    if (result.retiredToken != null) {
                        tokenFollows++
                        if (tokenFollows >= MAX_TOKEN_FOLLOWS_PER_POLL) {
                            stopPolling = true
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

    @Throws(Exception::class)
    suspend fun syncNow(
        pausedFriendIds: Set<String> = emptySet(),
        sharingEnabled: Boolean = true,
    ) {
        forEachFriendParallel(store.listFriends()) { friend ->
            processOutbox(friend.id)
            val isPaused = !sharingEnabled || friend.id in pausedFriendIds
            pollFriend(friend.id, isPaused)
        }
    }

    @Throws(Exception::class)
    suspend fun processOutboxes() {
        runCatching { store.listFriends() }.onSuccess { friends ->
            forEachFriendParallel(friends) { friend -> processOutbox(friend.id) }
        }
        runCatching { processOrphanedOutboxes() }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Removes [friendId]. If we were still sharing with them, a StoppedSharing is queued first so
     * their app shows us as stopped rather than leaving our last pin to go stale. Local only: the
     * friend's outbox outlives the friend (see [E2eeManager.deleteFriendKeepingOutbox]) and is
     * delivered by [processOrphanedOutboxes] (also run by every poll), which callers should kick
     * off once their UI has updated.
     */
    @Throws(Exception::class)
    open suspend fun removeFriend(
        friendId: String,
        pausedFriendIds: Set<String> = emptySet(),
        sharingEnabled: Boolean = true,
    ) {
        getFriendMutex(friendId).withLock {
            val friend = store.getFriend(friendId) ?: return
            // Nothing follows the farewell, so it can safely be dated after everything before it.
            // Current receivers order a batch by send order, but older ones compare timestamps and
            // let a Location win a same-second tie, and a stuck Location from this second may
            // arrive in the same batch.
            val farewell =
                if (sharingEnabled && isActiveFriend(friend, pausedFriendIds)) {
                    MessagePlaintext.StoppedSharing(ts = maxOf(currentTimeSeconds(), friend.lastSentTs + 1))
                } else {
                    null
                }
            store.deleteFriendKeepingOutbox(friendId, farewell)
        }
        mutexLock.withLock {
            silentDropRetries.keys.removeAll { it.startsWith("$friendId:") }
            orphanedOutboxGeneration++
            mayHaveOrphanedOutbox = true
        }
    }

    // True at startup (a removal may have been left undelivered by a previous run), after a
    // removal, and until a drain finds nothing left - so routine polls skip the orphan queries.
    @kotlin.concurrent.Volatile
    private var mayHaveOrphanedOutbox = true

    // Bumped (under mutexLock) by each removal, so a drain already in progress - which a
    // concurrent caller's tryLock defers to - picks up a removal that landed after it started.
    @kotlin.concurrent.Volatile
    private var orphanedOutboxGeneration = 0L
    private val orphanedOutboxMutex = Mutex()

    /**
     * Delivers outbox rows left behind by removed friends, in order per friend (friends in
     * parallel), stopping a friend's queue at its first failure. Rows older than the server's
     * retention are dropped undelivered: the mailbox would have expired them anyway. One drain at
     * a time - concurrent ones could re-post a message the peer has already read and acked.
     */
    @Throws(Exception::class)
    suspend fun processOrphanedOutboxes() {
        if (!mayHaveOrphanedOutbox || !orphanedOutboxMutex.tryLock()) return
        try {
            do {
                val generation = orphanedOutboxGeneration
                mayHaveOrphanedOutbox = false
                drainOrphanedOutboxesOnce()
            } while (orphanedOutboxGeneration != generation)
        } catch (e: Throwable) {
            mayHaveOrphanedOutbox = true
            throw e
        } finally {
            orphanedOutboxMutex.unlock()
        }
    }

    private suspend fun drainOrphanedOutboxesOnce() {
        val cutoff = currentTimeMillis() - ORPHANED_OUTBOX_RETENTION_MILLIS
        val byFriend = store.getOrphanedOutbox().groupBy({ it.first }, { it.second })
        coroutineScope {
            byFriend.map { (friendId, messages) ->
                async {
                    // Expire a friend's queue as a whole once its head is past retention: posting
                    // the rest after dropping the head could send them to a token the peer only
                    // learns from the head (a DH-ratchet transition).
                    if (messages.first().createdAt < cutoff) {
                        store.deleteOutboxByFriendId(friendId)
                        return@async
                    }
                    try {
                        deliverInOrder(friendId, messages)
                    } catch (e: WallClockTimeoutCancellationException) {
                        // An ordinary request timeout, not a real cancellation (see pollFriend).
                        mayHaveOrphanedOutbox = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mayHaveOrphanedOutbox = true
                    }
                }
            }.awaitAll()
        }
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
        // A removed friend's queue belongs to processOrphanedOutboxes alone (a caller here may be
        // working from a friend list read before the removal); two senders could double-post.
        if (store.getFriend(friendId) == null) return
        deliverInOrder(friendId, store.getOutbox(friendId))
    }

    private suspend fun deliverInOrder(
        friendId: String,
        outbox: List<EncryptedOutboxMessage>,
    ) {
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

    @Throws(Exception::class)
    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
        stationary: Boolean = false,
    ) {
        coroutineScope {
            // Before the pause state is read: see sendSeq.
            val callSeq = nextSendSeq()
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
                                // Stopped while this call waited: the stop is the last word.
                                if (!stoppedSince(friend.id, callSeq)) sendMessageToFriendInternal(friend.id, payload)
                            }
                        }.onFailure { if (it is CancellationException) throw it }
                    }
                }

            val results = deferreds.awaitAll()
            val successCount = results.count { it.isSuccess }
            val failCount = results.count { it.isFailure }

            // If we failed to send to ANYONE but had at least one target, propagate the last error.
            if (successCount == 0 && failCount > 0) {
                throw results.first { it.isFailure }.exceptionOrNull()!!
            }
        }
    }

    @Throws(Exception::class)
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
    @Throws(Exception::class)
    open suspend fun sendStoppedSharing(pausedFriendIds: Set<String> = emptySet()) {
        val payload = MessagePlaintext.StoppedSharing(ts = currentTimeSeconds())
        val activeFriends = store.listFriends().filter { isActiveFriend(it, pausedFriendIds) }
        forEachFriendParallel(activeFriends) { friend ->
            sendMessageToFriendInternal(friend.id, payload, requireDrainedOutboxFirst = false)
            recordStop(friend.id)
        }
    }

    /**
     * Enqueue a StoppedSharing message to a single friend (used by the per-friend expiry watcher).
     * Same WAL-outbox semantics as [sendStoppedSharing]; Keepalives continue afterwards.
     */
    @Throws(Exception::class)
    open suspend fun sendStoppedSharingToFriend(friendId: String) {
        val payload = MessagePlaintext.StoppedSharing(ts = currentTimeSeconds())
        val mutex = getFriendMutex(friendId)
        mutex.withLock {
            try {
                sendMessageToFriendInternal(friendId, payload, requireDrainedOutboxFirst = false)
                recordStop(friendId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    @Throws(Exception::class)
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
    @Throws(Exception::class)
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
        requireDrainedOutboxFirst: Boolean = true,
    ) {
        // WAL Safety: by default (Location/Keepalive), a non-empty outbox blocks generating a new
        // message - without this, a stuck friend during an outage would accumulate a fresh
        // Location every send cycle instead of just retrying the one already queued.
        // requireDrainedOutboxFirst=false (StoppedSharing) instead best-effort-drains but always
        // proceeds to encryptAndAdvance, so a terminal signal isn't dropped just because an older
        // message is stuck (#369) - safe here specifically because StoppedSharing has no routine
        // cadence to flood the outbox with, and the receive side takes the sharing state from
        // whichever of a batch's Locations and stops was sent last (see E2eeManager.processBatch).
        if (store.getOutbox(friendId).isNotEmpty()) {
            if (requireDrainedOutboxFirst) {
                processOutbox(friendId)
            } else {
                runCatching { processOutbox(friendId) }
            }
        }

        store.encryptAndAdvance(friendId, payload)

        // We trigger sequential outbox processing for this friend.
        // This ensures messages are sent in order (0, 1, 2...) even if multiple calls happen rapidly.
        processOutbox(friendId)
    }
}
