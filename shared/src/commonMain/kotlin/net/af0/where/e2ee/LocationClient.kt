package net.af0.where.e2ee

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.af0.where.model.UserLocation

/**
 * Orchestrates the end-to-end encrypted location sharing protocol.
 * Unifies polling, decryption, ratchet rotation, and sending for all platforms.
 *
 * @param baseUrl Server base URL (e.g. "http://localhost:8080").
 * @param store   Persistent E2EE state storage.
 */
open class LocationClient(
    private val baseUrl: String,
    private val store: E2eeManager,
    private val mailboxClient: MailboxClient = KtorMailboxClient,
) {
    /** Secondary constructor for Swift/native compatibility (§9). */
    constructor(baseUrl: String, store: E2eeManager) : this(baseUrl, store, KtorMailboxClient)

    private val friendMutexes = mutableMapOf<String, Mutex>()
    private val mutexLock = Mutex()

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
    ): List<UserLocation> =
        coroutineScope {
            processOutboxes()
            val now = currentTimeSeconds()
            if (now - lastCleanupTime > 3600) {
                try {
                    store.cleanupExpiredInvites()
                    lastCleanupTime = now
                } catch (e: Exception) {
                    println("[LocationClient] cleanup expired invites failed: ${e.message}")
                }
            }

            val friends = store.listFriends().shuffled()
            val deferreds =
                friends.map { friend ->
                    async {
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
                                val updates = pollFriend(friend.id, friend.id in pausedFriendIds)
                                Pair(updates, null)
                            } catch (e: Exception) {
                                println("[LocationClient] poll: failed for ${friend.id.take(8)}: ${e.message}")
                                Pair(emptyList<UserLocation>(), e)
                            } finally {
                                inFlightMutex.withLock {
                                    inFlightPolls.remove(friend.id)
                                }
                            }
                        }
                    }
                }

            val results = deferreds.awaitAll()
            val allUpdates = mutableListOf<UserLocation>()
            var successCount = 0
            var failCount = 0
            var lastError: Exception? = null

            for ((updates, error) in results) {
                if (error == null) {
                    allUpdates.addAll(updates)
                    successCount++
                } else {
                    failCount++
                    lastError = error
                }
            }

            if (successCount == 0 && failCount > 0) {
                lastError?.let { throw it }
            }

            allUpdates
        }

    suspend fun pollPendingInvites(): List<PendingInviteResult> = pollPendingInvitesInternal()

    internal suspend fun pollPendingInvitesInternal(): List<PendingInviteResult> =
        coroutineScope {
            val pending = store.listPendingInvites().shuffled()
            pending.map { invite ->
                async {
                    try {
                        val discoveryHex = invite.qrPayload.discoveryToken().toHex()
                        val messages = mailboxClient.poll(baseUrl, discoveryHex)
                        val inits = messages.filterIsInstance<KeyExchangeInitPayload>()
                        val last = inits.lastOrNull()
                        if (last != null) {
                            PendingInviteResult(last, inits.size > 1, invite.qrPayload.ekPub)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        println(
                            "[LocationClient] pollPendingInvite failed for token=${invite.qrPayload.discoveryToken().toHex().take(
                                8,
                            )}: ${e.message}",
                        )
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

    suspend fun postKeyExchangeInit(
        qr: QrPayload,
        initPayload: KeyExchangeInitPayload,
    ) {
        try {
            mailboxClient.post(baseUrl, qr.discoveryToken().toHex(), initPayload)
            // Finalize WAL record
            val friend = store.listFriends().find { it.pendingDiscoveryPost?.payload == initPayload }
            if (friend != null) {
                store.confirmDiscoveryPost(friend.id)
            }
        } catch (e: Exception) {
            println("[LocationClient] postKeyExchangeInit: eager post failed, will retry via processOutboxes: ${e.message}")
        }
    }

    internal suspend fun pollFriend(
        friendId: String,
        isPaused: Boolean = false,
    ): List<UserLocation> {
        val resultLocations = mutableListOf<UserLocation>()
        var friendBefore = store.getFriend(friendId) ?: return emptyList()

        // RECOVERY (§5.4): detect stale transition flag after crash
        if (friendBefore.outbox == null && friendBefore.session.isSendTokenPending && friendBefore.session.sendSeq >= 1) {
            println("[LocationClient] pollFriend: detected stale transition flag for ${friendId.take(8)}, finalizing now")
            finalizeTokenTransition(friendId)
            friendBefore = store.getFriend(friendId) ?: return emptyList()
        }

        // Phase 0: Retry pending ACKs
        friendBefore.pendingAcks.forEach { ack ->
            try {
                mailboxClient.ack(baseUrl, ack.token, ack.n)
                store.confirmAck(friendId, ack.token, ack.n)
            } catch (e: Exception) {
                println("[LocationClient] pollFriend: retry ACK failed for ${friendId.take(8)}: ${e.message}")
            }
        }

        // Multi-Token Polling (§5.3): if a transition is pending, we must poll BOTH the
        // current recvToken and the previous one, as the peer might still be sending to
        // the old epoch until they receive our first message from the new epoch.
        val pollQueue = mutableListOf<String>()
        pollQueue.add(friendBefore.session.recvToken.toHex())
        val prev = friendBefore.session.prevRecvToken.toHex()
        if (prev.isNotEmpty() && prev != friendBefore.session.recvToken.toHex()) {
            pollQueue.add(prev)
        }

        // Also poll retired tokens to catch up peers that are multiple epochs behind
        friendBefore.session.retiredRecvTokens.forEach { tokenBytes ->
            val token = tokenBytes.toHex()
            if (!pollQueue.contains(token)) {
                pollQueue.add(token)
            }
        }
        pollQueue.shuffle()

        val polledTokens = mutableSetOf<String>()
        var hasIncrementedFailure = false
        var follows = 0

        while (pollQueue.isNotEmpty()) {
            val currentTokenToPoll = pollQueue.removeAt(0)
            if (polledTokens.contains(currentTokenToPoll)) continue
            polledTokens.add(currentTokenToPoll)
            follows++

            val messages = mailboxClient.poll(baseUrl, currentTokenToPoll)
            println("[LocationClient] pollFriend: friend=${friendId.take(8)} token=${currentTokenToPoll.take(8)} msgs=${messages.size}")
            if (messages.isEmpty()) continue

            try {
                val result = store.processBatch(friendId, currentTokenToPoll, messages) ?: continue

                // Update failure counters
                val hasFailures = (result.failCount > 0 || result.hadSilentDrops) && !result.anySuccess && !result.hadStateUpdate
                val currentDropCount = if (hasFailures) {
                    if (!hasIncrementedFailure) {
                        hasIncrementedFailure = true
                        val nextCount = store.incrementConsecutiveSilentDrops(friendId)
                        store.addDiagnosticEvent(
                            "POLL FAILURES: ${friendId.take(8)} token=${currentTokenToPoll.take(8)} (retry $nextCount/$MAX_SILENT_DROP_RETRIES)",
                        )
                        nextCount
                    } else {
                        store.getFriend(friendId)?.consecutiveSilentDrops ?: 0
                    }
                } else {
                    store.resetConsecutiveSilentDrops(friendId)
                    0
                }

                val forceAck = currentDropCount >= MAX_SILENT_DROP_RETRIES || follows >= MAX_TOKEN_FOLLOWS_PER_POLL
                if (forceAck) {
                    if (follows >= MAX_TOKEN_FOLLOWS_PER_POLL) {
                        println("[LocationClient] pollFriend: force-ACKing due to TOKEN FOLLOW CAP for ${friendId.take(8)}")
                        store.addDiagnosticEvent("TOKEN FOLLOW CAP: ${friendId.take(8)}")
                    } else {
                        println(
                            "[LocationClient] pollFriend: force-ACKing after $MAX_SILENT_DROP_RETRIES consecutive failures for ${friendId.take(8)}",
                        )
                        store.addDiagnosticEvent("FORCE ACK: ${friendId.take(8)} — unrecoverable failures, re-pair if desynced")
                    }
                    store.resetConsecutiveSilentDrops(friendId)
                    store.recordPendingAck(friendId, currentTokenToPoll, messages.size)
                }

                // ACK logic: progress (success/replay/state) already recorded intent in processBatch.
                // We only need to check if we have a pending record to flush.
                val friendAfterBatch = store.getFriend(friendId)
                if (friendAfterBatch?.pendingAcks?.any { it.token == currentTokenToPoll && it.n == messages.size } == true) {
                    try {
                        mailboxClient.ack(baseUrl, currentTokenToPoll, messages.size)
                        store.confirmAck(friendId, currentTokenToPoll, messages.size)
                    } catch (e: Exception) {
                        println("[LocationClient] pollFriend: ACK failed for ${friendId.take(8)}: ${e.message}")
                    }
                }

                resultLocations.addAll(
                    result.decryptedLocations.map { loc ->
                        UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                    },
                )

                if (follows >= MAX_TOKEN_FOLLOWS_PER_POLL) {
                    break
                }

                // Follow rotation
                val updatedFriend = store.getFriend(friendId) ?: break
                val newToken = updatedFriend.session.recvToken.toHex()
                if (newToken != currentTokenToPoll) {
                    pollQueue.add(newToken)
                }
            } catch (e: Exception) {
                println("[LocationClient] pollFriend: processBatch failed for ${friendId.take(8)}: ${e.message}")
                throw e
            }
        }

        store.updateLastPollTs(friendId, currentTimeSeconds())

        val friendAfter = store.getFriend(friendId)
        if (friendAfter != null) {
            val remoteDhChanged = !friendBefore.session.remoteDhPub.contentEquals(friendAfter.session.remoteDhPub)
            if (friendAfter.session.needsRatchet || remoteDhChanged) {
                if (!friendAfter.isStale && (!friendAfter.sharingEnabled || isPaused)) {
                    println("[LocationClient] pollFriend: needsRatchet/new DH for ${friendId.take(8)}, sending automated keepalive")
                    try {
                        sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
                    } catch (e: Exception) {
                        println("[LocationClient] pollFriend: keepalive failed for ${friendId.take(8)}: ${e.message}")
                        store.addDiagnosticEvent("KEEPALIVE FAIL: ${friendId.take(8)}: ${e.message?.take(40)}")
                    }
                }
            }
        }

        return resultLocations
    }

    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
    ) {
        processOutboxes()
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        val activeFriends = store.listFriends().filter { it.id !in pausedFriendIds && !it.isStale }.shuffled()

        var successCount = 0
        var failCount = 0
        var lastError: Exception? = null
        for (friend in activeFriends) {
            try {
                val mutex = getFriendMutex(friend.id)
                mutex.withLock {
                    sendMessageToFriendInternal(friend.id, payload)
                }
                successCount++
            } catch (e: Exception) {
                lastError = e
                failCount++
            }
        }

        if (successCount == 0 && failCount > 0) {
            lastError?.let { throw it }
        }
    }

    suspend fun sendLocationToFriend(
        friendId: String,
        lat: Double,
        lng: Double,
    ) {
        val mutex = getFriendMutex(friendId)
        mutex.withLock {
            val ts = currentTimeSeconds()
            val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
            sendMessageToFriendInternal(friendId, payload)
        }
    }

    suspend fun sendKeepalive(friendId: String) {
        val mutex = getFriendMutex(friendId)
        mutex.withLock {
            sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
        }
    }

    private suspend fun sendMessageToFriendInternal(
        friendId: String,
        payload: MessagePlaintext,
    ) {
        val friendCheck = store.getFriend(friendId) ?: return
        if (friendCheck.outbox != null) {
            println("[LocationClient] send: skipping send for ${friendId.take(8)} — outbox still pending")
            store.addDiagnosticEvent("OUTBOX BLOCK: skipped send for ${friendId.take(8)}, pending outbox")
            return
        }

        val message =
            try {
                store.encryptAndStore(friendId, payload)
            } catch (e: IllegalStateException) {
                println("[LocationClient] send: outbox race for ${friendId.take(8)}, skipping")
                store.addDiagnosticEvent("OUTBOX BLOCK (race): ${friendId.take(8)}")
                return
            }

        val friendAfter = store.getFriend(friendId) ?: return
        val outbox = friendAfter.outbox ?: throw Exception("Outbox missing after store")
        val tokenToUse = outbox.token
        val isPending = friendAfter.session.isSendTokenPending

        println(
            "[LocationClient] send: friend=${friendId.take(8)} token=${tokenToUse.take(8)} " +
                "seq=${friendAfter.session.sendSeq} type=${payload::class.simpleName} " +
                "pending=$isPending" + (if (isPending) " (using prevSendToken)" else ""),
        )
        mailboxClient.post(baseUrl, tokenToUse, message)
        store.clearOutbox(friendId)

        if (payload is MessagePlaintext.Location) {
            store.updateLastSentTs(friendId, currentTimeSeconds())
        }

        finalizeTokenTransition(friendId, clearingToken = tokenToUse)
    }

    private suspend fun finalizeTokenTransition(
        friendId: String,
        clearingToken: String? = null,
    ) {
        if (store.clearSendTokenPending(friendId, clearingToken)) {
            println("[LocationClient] finalizeTokenTransition: clearing pending flag for ${friendId.take(8)}")

            val updatedFriend = store.getFriend(friendId) ?: return
            val session = updatedFriend.session

            val tokensRotated = !session.sendToken.contentEquals(session.prevSendToken)
            if (tokensRotated) {
                println(
                    "[LocationClient] finalizeTokenTransition: triggering fresh keepalive for ${friendId.take(8)} " +
                        "on new token ${session.sendToken.toHex().take(8)}",
                )
                try {
                    sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
                } catch (e: Exception) {
                    println("[LocationClient] finalizeTokenTransition: keepalive failed for ${friendId.take(8)}: ${e.message}")
                    store.addDiagnosticEvent("KEEPALIVE FAIL: ${friendId.take(8)}: ${e.message?.take(40)}")
                }
            }
        }
    }

    private suspend fun processOutboxes() = coroutineScope {
        val friends = store.listFriends().shuffled()
        friends.map { friend ->
            async {
                val mutex = getFriendMutex(friend.id)
                mutex.withLock {
                    if (friend.session.isSendTokenPending) {
                        val staleSince = friend.session.sendTokenPendingSinceMs
                        val isStale = staleSince != null && (currentTimeMillis() - staleSince) > PENDING_TRANSITION_TIMEOUT_MS
                        if (isStale) {
                            println("[LocationClient] pending transition stale for ${friend.id.take(8)}")
                            store.abandonPendingTransition(friend.id)
                            return@async
                        }
                    }

                    // Recovery for pending discovery posts
                    val pendingPost = friend.pendingDiscoveryPost
                    if (pendingPost != null) {
                        try {
                            println("[LocationClient] recovery: retrying pendingDiscoveryPost for ${friend.id.take(8)}")
                            mailboxClient.post(baseUrl, pendingPost.discoveryToken, pendingPost.payload)
                            store.confirmDiscoveryPost(friend.id)
                        } catch (e: Exception) {
                            println("[LocationClient] recovery: pendingDiscoveryPost failed for ${friend.id.take(8)}: ${e.message}")
                        }
                    }

                    val outbox = friend.outbox
                    if (outbox == null) {
                        return@async
                    }

                    try {
                        println("[LocationClient] recovery: retrying outbox for ${friend.id.take(8)} token=${outbox.token.take(8)}")
                        mailboxClient.post(baseUrl, outbox.token, outbox.payload)
                        store.clearOutbox(friend.id)
                        finalizeTokenTransition(friend.id, clearingToken = outbox.token)
                    } catch (e: Exception) {
                        if (e is ProtocolGapException) {
                            println("[LocationClient] recovery: permanent cryptodesync for ${friend.id.take(8)}")
                            store.clearOutbox(friend.id)
                            return@async
                        }
                        val statusCode = (e as? ServerException)?.statusCode
                        if (statusCode == 404 || statusCode == 410) {
                            val currentRetries = store.incrementOutboxRetryCount(friend.id)
                            if (currentRetries >= MAX_OUTBOX_PARK_RETRIES) {
                                val prevTokenHex = friend.session.prevSendToken.toHex()
                                if (prevTokenHex != outbox.token && prevTokenHex.isNotEmpty()) {
                                    println("[LocationClient] recovery: outbox token ${outbox.token.take(8)} returned 404/410, rerouting to prevSendToken ${prevTokenHex.take(8)}")
                                    store.updateOutboxToken(friend.id, prevTokenHex)
                                } else {
                                    println("[LocationClient] recovery: outbox token ${outbox.token.take(8)} returned 404/410, max retries reached. Clearing outbox.")
                                    store.clearOutbox(friend.id)
                                    if (friend.session.isSendTokenPending && outbox.token == friend.session.prevSendToken.toHex()) {
                                        finalizeTokenTransition(friend.id, clearingToken = outbox.token)
                                    }
                                }
                            }
                        } else if (statusCode == 429) {
                            store.incrementOutbox429Count(friend.id)
                        }
                    }
                }
            }
        }.awaitAll()
    }
}
