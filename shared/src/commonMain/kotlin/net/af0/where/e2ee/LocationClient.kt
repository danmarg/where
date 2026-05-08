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
    private val store: E2eeStore,
    private val mailboxClient: MailboxClient = KtorMailboxClient,
) {
    /** Secondary constructor for Swift/native compatibility (§9). */
    constructor(baseUrl: String, store: E2eeStore) : this(baseUrl, store, KtorMailboxClient)

    private val inFlightPolls = mutableSetOf<String>()
    private val inFlightMutex = Mutex()

    /**
     * Poll all friends and all pending invites.
     *
     * @param isForeground Whether the app is currently in the foreground.
     * @param pausedFriendIds Set of friend IDs for whom location sharing is currently paused.
     * @return List of new [UserLocation] updates received since the last poll.
     */
    suspend fun poll(
        isForeground: Boolean = true,
        pausedFriendIds: Set<String> = emptySet(),
    ): List<UserLocation> =
        coroutineScope {
            processOutboxes()
            // Periodic cleanup of expired invites
            try {
                store.cleanupExpiredInvites()
            } catch (e: Exception) {
                println("[LocationClient] cleanup expired invites failed: ${e.message}")
            }

            val friends = store.listFriends()
            val deferreds =
                friends.map { friend ->
                    async {
                        // Guard against concurrent polls for the same friend.
                        // Since we parallelized the top-level poll() loop, we must ensure
                        // that two calls to pollFriend(id) do not run concurrently for the
                        // same friendId, as that would cause redundant synchronization and
                        // waste battery/data.
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

            // Only throw if EVERY friend failed. If we got updates or at least one
            // success, return what we have to prevent data loss (§5.4).
            if (successCount == 0 && failCount > 0) {
                lastError?.let { throw it }
            }

            allUpdates
        }

    /**
     * Poll the discovery mailboxes for all pending invites.
     * returns a list of results for each invite where a message was found.
     *
     * Note: We randomize the order and parallelize requests to mitigate timing oracle attacks
     * and traffic analysis (Issue #222 security hardening).
     */
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

    /**
     * Post a KeyExchangeInit to the discovery mailbox.
     */
    suspend fun postKeyExchangeInit(
        qr: QrPayload,
        initPayload: KeyExchangeInitPayload,
    ) {
        val discoveryHex = qr.discoveryToken().toHex()
        mailboxClient.post(baseUrl, discoveryHex, initPayload)
    }

    /**
     * Poll a specific friend's mailbox.
     * @param isPaused Whether location sharing is currently paused for this friend.
     */
    internal suspend fun pollFriend(
        friendId: String,
        isPaused: Boolean = false,
    ): List<UserLocation> {
        val resultLocations = mutableListOf<UserLocation>()
        val friendBefore = store.getFriend(friendId) ?: return emptyList()
        var currentTokenToPoll = friendBefore.session.recvToken.toHex()

        // We follow token rotations up to MAX_TOKEN_FOLLOWS_PER_POLL to prevent infinite loops
        // caused by adversarial server or client code injecting transition messages (§9.2).
        var follows = 0
        while (follows < MAX_TOKEN_FOLLOWS_PER_POLL) {
            val messages = mailboxClient.poll(baseUrl, currentTokenToPoll)
            println("[LocationClient] pollFriend: friend=${friendId.take(8)} token=${currentTokenToPoll.take(8)} msgs=${messages.size}")
            if (messages.isEmpty()) break

            try {
                val result = store.processBatch(friendId, currentTokenToPoll, messages) ?: break

                // Update failure counters atomically in the store.
                // We track consecutive polls where header-parse failures or total decryption
                // failures blocked the ACK, per friend. Triggers a force-ACK at
                // MAX_SILENT_DROP_RETRIES to break a permanent livelock caused by an
                // unrecoverable corrupted message.
                val hasFailures = result.hadSilentDrops || (messages.isNotEmpty() && !result.anySuccess)
                
                val currentDropCount = if (hasFailures && !result.anyReplay) {
                    val nextCount = store.incrementConsecutiveSilentDrops(friendId)
                    store.addDiagnosticEvent(
                        "POLL FAILURES: ${friendId.take(8)} token=${currentTokenToPoll.take(8)} (retry $nextCount/$MAX_SILENT_DROP_RETRIES)",
                    )
                    nextCount
                } else {
                    store.resetConsecutiveSilentDrops(friendId)
                    0
                }

                val forceAck = currentDropCount >= MAX_SILENT_DROP_RETRIES
                if (forceAck) {
                    println(
                        "[LocationClient] pollFriend: force-ACKing after $MAX_SILENT_DROP_RETRIES consecutive failures for ${friendId.take(
                            8,
                        )} — messages are unrecoverable",
                    )
                    store.addDiagnosticEvent("FORCE ACK: ${friendId.take(8)} — unrecoverable failures, re-pair if desynced")
                    store.resetConsecutiveSilentDrops(friendId)
                }
                
                // ACK after durable save: tell the server to delete the messages we just processed.
                // Only ACK if at least one message succeeded AND no messages were silently dropped
                // at the header-parsing stage. A dropped message could be a ratchet transition
                // message; ACK-ing it would cause a permanent token desync.
                //
                // REPLAY ROBUSTNESS: We ALSO ACK if the batch consisted of PURE replays
                // (anyReplay=true AND failCount=0). This breaks the "lost-ACK" livelock where
                // the server keeps delivering messages the client has already ratcheted past.
                //
                // SAFETY VALVE: if failures have persisted for MAX_SILENT_DROP_RETRIES consecutive
                // polls, force-ACK to break a permanent livelock caused by an unrecoverable message.
                val safeToAck = (result.anySuccess && !result.hadSilentDrops) || 
                               (result.anyReplay && result.failCount == 0) || 
                               forceAck

                if (safeToAck) {
                    try {
                        mailboxClient.ack(baseUrl, currentTokenToPoll, messages.size)
                    } catch (e: Exception) {
                        println("[LocationClient] pollFriend: ACK failed for ${friendId.take(8)}: ${e.message}")
                    }
                }

                resultLocations.addAll(
                    result.decryptedLocations.map { loc ->
                        UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                    },
                )
            } catch (e: Exception) {
                println("[LocationClient] pollFriend: processBatch failed for ${friendId.take(8)}: ${e.message}")
                e.printStackTrace()
                throw e
            }

            // Did the recvToken change during processing? If so, follow it immediately.
            val updatedFriend = store.getFriend(friendId) ?: break
            val newToken = updatedFriend.session.recvToken.toHex()
            if (newToken != currentTokenToPoll) {
                println(
                    "[LocationClient] pollFriend: recvToken rotated for ${friendId.take(
                        8,
                    )}: ${currentTokenToPoll.take(8)} -> ${newToken.take(8)}",
                )
                currentTokenToPoll = newToken
                follows++
            } else {
                break // No token rotation, we've caught up
            }
        }

        if (follows >= MAX_TOKEN_FOLLOWS_PER_POLL) {
            println(
                "[LocationClient] pollFriend: TOKEN FOLLOW CAP HIT for ${friendId.take(
                    8,
                )} — may have missed messages beyond $follows rotations",
            )
            store.addDiagnosticEvent("TOKEN FOLLOW CAP: ${friendId.take(8)}")
        }

        store.updateLastPollTs(friendId, currentTimeSeconds())

        // Automated keepalive (§5.3): send a keepalive message if we received a new DH key
        // but have not yet replied with our own new DH public key. This advances the peer's
        // recvToken.
        //
        // Crucially, we only do this immediately if we are NOT actively sharing location
        // (sharingEnabled=false or isPaused=true). If we ARE sharing, we wait for the next
        // location update to carry the ratchet, preventing a keepalive feedback loop
        // during active sessions.
        val friendAfter = store.getFriend(friendId)
        if (friendAfter != null) {
            val remoteDhChanged = !friendBefore.session.remoteDhPub.contentEquals(friendAfter.session.remoteDhPub)
            if (friendAfter.session.needsRatchet || remoteDhChanged) {
                if (!friendAfter.isStale && (!friendAfter.sharingEnabled || isPaused)) {
                    println(
                        "[LocationClient] pollFriend: needsRatchet/new DH for ${friendId.take(8)}, sending automated keepalive",
                    )
                    try {
                        sendKeepalive(friendId)
                    } catch (e: Exception) {
                        // Keepalive post failed. If the failure was a network error, the outbox
                        // was durably written by encryptAndStore and processOutboxes will retry.
                        // If the failure was before encryptAndStore (e.g., storage write failure),
                        // needsRatchet=true is still persisted and will re-trigger on next poll.
                        println("[LocationClient] pollFriend: keepalive failed for ${friendId.take(8)}: ${e.message}")
                        store.addDiagnosticEvent("KEEPALIVE FAIL: ${friendId.take(8)}: ${e.message?.take(40)}")
                    }
                }
            }
        }

        return resultLocations
    }

    /**
     * Encrypt and send a location update to all active (non-paused) friends.
     */
    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
    ) {
        processOutboxes()
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        var successCount = 0
        var failCount = 0
        var lastError: Exception? = null
        val activeFriends = store.listFriends().filter { it.id !in pausedFriendIds && !it.isStale }

        for (friend in activeFriends) {
            try {
                sendMessageToFriendInternal(friend.id, payload)
                successCount++
            } catch (e: Exception) {
                lastError = e
                failCount++
            }
        }

        // Only throw if EVERY active friend failed. If we succeeded for at least
        // one, the failed ones will be retried on the next heartbeat (§5.4).
        if (successCount == 0 && failCount > 0) {
            lastError?.let { throw it }
        }
    }

    /**
     * Encrypt and send a location update to a single specific friend.
     */
    suspend fun sendLocationToFriend(
        friendId: String,
        lat: Double,
        lng: Double,
    ) {
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        sendMessageToFriendInternal(friendId, payload)
    }

    /**
     * Send a keepalive message to a friend.
     */
    suspend fun sendKeepalive(friendId: String) {
        sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
    }

    private suspend fun sendMessageToFriendInternal(
        friendId: String,
        payload: MessagePlaintext,
    ) {
        // RECOVERY (§5.4): If we crashed between clearOutbox and finalizeTokenTransition,
        // we must still finalize the transition now before sending the next message.
        // We detect this by checking if isSendTokenPending is true, outbox is null,
        // but sendSeq > 0 (meaning the first message of the new epoch was already sent).
        val friendBefore = store.getFriend(friendId) ?: return
        if (friendBefore.outbox == null && friendBefore.session.isSendTokenPending && friendBefore.session.sendSeq > 0) {
            println("[LocationClient] send: detected stale transition flag for ${friendId.take(8)}, finalizing now")
            finalizeTokenTransition(friendId)
        }

        // If a previous outbox is still pending (processOutboxes hasn't cleared it yet),
        // skip rather than overwrite — the pending message may contain a DH ratchet key
        // that the peer has not yet received.
        val friendCheck = store.getFriend(friendId) ?: return
        if (friendCheck.outbox != null) {
            println("[LocationClient] send: skipping send for ${friendId.take(8)} — outbox still pending (will retry)")
            store.addDiagnosticEvent("OUTBOX BLOCK: skipped send for ${friendId.take(8)}, pending outbox")
            return
        }

        // PERSISTENCE HYGIENE (§5.4): We advance the session and persist the
        // encrypted message in an ATOMIC OUTBOX update BEFORE posting.
        // This ensures that if the app crashes during the post, we don't
        // reuse the same sequence number/nonce on restart, AND we have
        // the message ready to retry.
        // encryptAndStore re-checks outbox == null atomically under friendLock,
        // closing the TOCTOU window between the early guard above and the store write.
        val message =
            try {
                store.encryptAndStore(friendId, payload)
            } catch (e: IllegalStateException) {
                println("[LocationClient] send: outbox race for ${friendId.take(8)}, skipping (will retry)")
                store.addDiagnosticEvent("OUTBOX BLOCK (race): ${friendId.take(8)}")
                return
            }

        // Use the outbox's token to ensure we post to the correct endpoint
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

        // TOKEN TRANSITION (§5.4): If we were transitioning DH epochs, we have now
        // successfully flushed the final message of the old epoch. We must now
        // clear the pending flag and trigger the fresh keepalive.
        finalizeTokenTransition(friendId, clearingToken = tokenToUse)
    }

    /**
     * Finalizes the token transition after a successful post.
     * Clears isSendTokenPending and sends a fresh keepalive if necessary.
     * This is called by both the main send path and the outbox recovery path.
     */
    private suspend fun finalizeTokenTransition(
        friendId: String,
        clearingToken: String? = null,
    ) {
        // Atomic check-and-clear to prevent TOCTOU race rollback (§5.4).
        if (store.clearSendTokenPending(friendId, clearingToken)) {
            println("[LocationClient] finalizeTokenTransition: clearing pending flag for ${friendId.take(8)}")

            val updatedFriend = store.getFriend(friendId) ?: return
            val session = updatedFriend.session

            // Only send fresh keepalive if we actually rotated tokens and haven't sent it.
            // We send this regardless of sharingEnabled to ensure the peer's recvToken advances (§5.3).
            val tokensRotated = !session.sendToken.contentEquals(session.prevSendToken)
            if (tokensRotated) {
                println(
                    "[LocationClient] finalizeTokenTransition: triggering fresh keepalive for ${friendId.take(8)} " +
                        "on new token ${session.sendToken.toHex().take(8)}",
                )
                try {
                    sendKeepalive(friendId)
                } catch (e: Exception) {
                    // Swallow: if the fresh ratchet transition fails (e.g., transport error during flush),
                    // we'll try again next time we process the outbox.
                    println("[LocationClient] finalizeTokenTransition: keepalive failed for ${friendId.take(8)}: ${e.message}")
                    store.addDiagnosticEvent("KEEPALIVE FAIL: ${friendId.take(8)}: ${e.message?.take(40)}")
                }
            }
        }
    }

    /**
     * Recovery logic (§5.4): drain any pending outboxes that were persisted
     * but not successfully posted due to a crash or network failure.
     */
    private suspend fun processOutboxes() = coroutineScope {
        val friends = store.listFriends()
        friends.map { friend ->
            async {
                // STALE TRANSITION RECOVERY: If a transition has been pending longer than the server TTL (7 days),
                // the transition message is gone from prevSendToken. Abandon the pending state and roll back.
                if (friend.session.isSendTokenPending) {
                    val staleSince = friend.session.sendTokenPendingSinceMs
                    val isStale = staleSince != null && (currentTimeMillis() - staleSince) > PENDING_TRANSITION_TIMEOUT_MS
                    if (isStale) {
                        println("[LocationClient] pending transition stale for ${friend.id.take(8)}, reverting to prevSendToken")
                        store.addDiagnosticEvent("STALE ROLLBACK: ${friend.id.take(8)}")
                        store.abandonPendingTransition(friend.id)
                        return@async
                    }
                }

                val outbox = friend.outbox
                if (outbox == null) {
                    // RECOVERY (§5.4): If we crashed between clearOutbox and finalizeTokenTransition,
                    // we must still finalize the transition now.
                    if (friend.session.isSendTokenPending && friend.session.sendSeq > 0) {
                        println("[LocationClient] recovery: detected stale transition flag for ${friend.id.take(8)}, finalizing now")
                        finalizeTokenTransition(friend.id)
                    }
                    return@async
                }

                try {
                    println("[LocationClient] recovery: retrying outbox for ${friend.id.take(8)} token=${outbox.token.take(8)}")
                    mailboxClient.post(baseUrl, outbox.token, outbox.payload)
                    store.clearOutbox(friend.id)
                    // TRANSACTIONAL RECOVERY (§5.4): After recovering a lost post,
                    // we must also trigger the token transition cleanup.
                    finalizeTokenTransition(friend.id, clearingToken = outbox.token)
                } catch (e: Exception) {
                    if (e is ProtocolGapException) {
                        println("[LocationClient] recovery: permanent cryptodesync (gap) for ${friend.id.take(8)}, clearing outbox")
                        store.clearOutbox(friend.id)
                        return@async
                    }
                    // Permanent failures (mailbox expired/deleted) should clear the outbox (§5.4).
                    val statusCode = (e as? ServerException)?.statusCode
                    if (statusCode == 404 || statusCode == 410) {
                        if (friend.session.isSendTokenPending &&
                            outbox.token == friend.session.prevSendToken.toHex()
                        ) {
                            println(
                                "[LocationClient] recovery: DESYNC: transition message permanently lost for ${friend.id.take(8)} " +
                                    "(prevSendToken ${outbox.token.take(8)} expired before peer received DH key) — re-pair required",
                            )
                            store.addDiagnosticEvent("DESYNC: transition lost for ${friend.id.take(8)}, re-pair required")
                            store.clearOutboxAndUpdateSession(friend.id, friend.session.copy(isSendTokenPending = false))
                        } else {
                            println("[LocationClient] recovery: clearing expired outbox for ${friend.id.take(8)} (status=$statusCode)")
                            store.clearOutbox(friend.id)
                        }
                    } else if (statusCode == 429) {
                        val count = store.incrementOutbox429Count(friend.id)
                        println("[LocationClient] recovery: outbox 429 for ${friend.id.take(8)} (retry $count)")
                    }
                }
            }
        }.awaitAll()
    }
}
