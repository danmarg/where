package net.af0.where.e2ee

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

    private val pollMutex = Mutex()

    /**
     * Poll all friends and all pending invites.
     *
     * Poll calls are serialized to prevent concurrent ratchet state mutations.
     *
     * @param isForeground Whether the app is currently in the foreground.
     * @param pausedFriendIds Set of friend IDs for whom location sharing is currently paused.
     * @return List of new [UserLocation] updates received since the last poll.
     */
    suspend fun poll(
        isForeground: Boolean = true,
        pausedFriendIds: Set<String> = emptySet(),
    ): List<UserLocation> =
        pollMutex.withLock {
            processOutboxes()
            // Periodic cleanup of expired invites
            try {
                store.cleanupExpiredInvites()
            } catch (_: Exception) {
            }

            val allUpdates = mutableListOf<UserLocation>()
            var successCount = 0
            var failCount = 0
            var lastError: Exception? = null

            val friends = store.listFriends()

            for (friend in friends) {
                try {
                    // RESTART RECOVERY (§5.5): If the session was regenerated on startup,
                    // force a keepalive now to establish a new memory-only DH epoch.
                    if (friend.session.needsRatchet) {
                        println("[LocationClient] poll: needsRatchet=true for ${friend.id.take(8)}, sending pre-poll keepalive")
                        try {
                            sendKeepalive(friend.id)
                        } catch (e: Exception) {
                            println("[LocationClient] poll: pre-poll keepalive failed for ${friend.id.take(8)}: ${e.message}")
                        }
                    }

                    val friendUpdates = pollFriend(friend.id, friend.id in pausedFriendIds)
                    allUpdates.addAll(friendUpdates)
                    successCount++
                } catch (e: Exception) {
                    lastError = e
                    failCount++
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
     */
    suspend fun pollPendingInvites(): List<PendingInviteResult> {
        val pending = store.listPendingInvites()
        val results = mutableListOf<PendingInviteResult>()
        for (invite in pending) {
            try {
                val discoveryHex = invite.qrPayload.discoveryToken().toHex()
                val messages = mailboxClient.poll(baseUrl, discoveryHex)
                val inits = messages.filterIsInstance<KeyExchangeInitPayload>()
                val last = inits.lastOrNull()
                if (last != null) {
                    results.add(PendingInviteResult(last, inits.size > 1, invite.qrPayload.ekPub))
                }
            } catch (e: Exception) {
                println("[LocationClient] pollPendingInvite failed for token=${invite.qrPayload.discoveryToken().toHex().take(8)}: ${e.message}")
            }
        }
        return results
    }

    /** Deprecated: use pollPendingInvites instead. Returns the first result found. */
    suspend fun pollPendingInvite(): PendingInviteResult? {
        return pollPendingInvites().firstOrNull()
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

                // ACK after durable save: tell the server to delete the messages we just processed.
                // Only ACK if at least one message succeeded — if all failed, the batch may contain
                // a legitimate ratchet message we can't yet decrypt, and deleting it would cause
                // a permanent token desync. Non-fatal: if ACK fails, messages remain and will be
                // re-processed idempotently.
                if (result.anySuccess) {
                    try {
                        mailboxClient.ack(baseUrl, currentTokenToPoll, messages.size)
                    } catch (e: Exception) {
                        println("[LocationClient] pollFriend: ACK failed for ${friendId.take(8)}: ${e.message}")
                    }
                }

                for (out in result.outgoing) {
                    mailboxClient.post(baseUrl, out.token, out.payload)
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

        // Automated keepalive (§5.3): send a keepalive message if we received a new DH key
        // but have not yet replied with our own new DH public key. This advances the peer's
        // recvToken.
        //
        // Crucially, we only do this immediately if we are NOT actively sharing location
        // (sharingEnabled=false or isPaused=true). If we ARE sharing, we wait for the next
        // location update to carry the ratchet, preventing a keepalive feedback loop
        // during active sessions.
        val friendAfter = store.getFriend(friendId)
        if (friendAfter != null && !friendBefore.session.remoteDhPub.contentEquals(friendAfter.session.remoteDhPub)) {
            if (!friendAfter.isStale && (!friendAfter.sharingEnabled || isPaused)) {
                println("[LocationClient] pollFriend: new remoteDhPub for ${friendId.take(8)}, sending keepalive (sharingEnabled=${friendAfter.sharingEnabled}, isPaused=$isPaused)")
                try {
                    sendKeepalive(friendId)
                } catch (_: Exception) {
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
    ) = pollMutex.withLock {
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
    ) = pollMutex.withLock {
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
            finalizeTokenTransition(friendId)
        }

        // PERSISTENCE HYGIENE (§5.4): We advance the session and persist the
        // encrypted message in an ATOMIC OUTBOX update BEFORE posting.
        // This ensures that if the app crashes during the post, we don't
        // reuse the same sequence number/nonce on restart, AND we have
        // the message ready to retry.
        val message = store.encryptAndStore(friendId, payload)

        // Use the outbox's token to ensure we post to the correct endpoint
        val friendAfter = store.getFriend(friendId) ?: return
        val tokenToUse = friendAfter.outbox?.token ?: throw Exception("Outbox missing after store")

        println(
            "[LocationClient] send: friend=${friendId.take(
                8,
            )} token=${tokenToUse.take(
                8,
            )} seq=${friendAfter.session.sendSeq} type=${payload::class.simpleName} pending=${friendBefore.session.isSendTokenPending}",
        )
        mailboxClient.post(baseUrl, tokenToUse, message)
        store.clearOutbox(friendId)

        if (payload is MessagePlaintext.Location) {
            store.updateLastSentTs(friendId, currentTimeSeconds())
        }

        // TOKEN TRANSITION (§5.4): If we were transitioning DH epochs, we have now
        // successfully flushed the final message of the old epoch. We must now
        // clear the pending flag and trigger the fresh keepalive.
        finalizeTokenTransition(friendId)
    }

    /**
     * Finalizes the token transition after a successful post.
     * Clears isSendTokenPending and sends a fresh keepalive if necessary.
     * This is called by both the main send path and the outbox recovery path.
     */
    private suspend fun finalizeTokenTransition(friendId: String) {
        // Tightened idempotency check: only act if transition is still marked pending in store.
        val updatedFriend = store.getFriend(friendId) ?: return
        if (updatedFriend.session.isSendTokenPending) {
            val finalSession = updatedFriend.session.copy(isSendTokenPending = false)
            store.updateSession(friendId, finalSession)

            // Only send fresh keepalive if we actually rotated tokens and haven't sent it.
            // We send this regardless of sharingEnabled to ensure the peer's recvToken advances (§5.3).
            val tokensRotated = !finalSession.sendToken.contentEquals(finalSession.prevSendToken)
            println(
                "[LocationClient] finalizeTokenTransition: friend=${friendId.take(
                    8,
                )} tokensRotated=$tokensRotated newToken=${finalSession.sendToken.toHex().take(8)}",
            )
            if (tokensRotated) {
                try {
                    sendKeepalive(friendId)
                } catch (e: Exception) {
                    // Swallow: if the fresh ratchet transition fails (e.g., transport error during flush),
                    // we'll try again next time we process the outbox.
                    println("[LocationClient] finalizeTokenTransition: keepalive failed for ${friendId.take(8)}: ${e.message}")
                }
            }
        }
    }

    /**
     * Recovery logic (§5.4): drain any pending outboxes that were persisted
     * but not successfully posted due to a crash or network failure.
     */
    private suspend fun processOutboxes() {
        for (friend in store.listFriends()) {
            val outbox = friend.outbox
            if (outbox == null) {
                // RECOVERY (§5.4): If we crashed between clearOutbox and finalizeTokenTransition,
                // we must still finalize the transition now.
                // We detect this by checking if isSendTokenPending is true and sendSeq > 0
                // (meaning the first message of the new epoch was already sent).
                if (friend.session.isSendTokenPending && friend.session.sendSeq > 0) {
                    finalizeTokenTransition(friend.id)
                }
                continue
            }
            try {
                mailboxClient.post(baseUrl, outbox.token, outbox.payload)
                store.clearOutbox(friend.id)
                // TRANSACTIONAL RECOVERY (§5.4): After recovering a lost post,
                // we must also trigger the token transition cleanup.
                finalizeTokenTransition(friend.id)
            } catch (e: Exception) {
                // Permanent failures (mailbox expired/deleted) should clear the outbox (§5.4).
                val statusCode = (e as? ServerException)?.statusCode
                if (statusCode == 404 || statusCode == 410) {
                    store.clearOutbox(friend.id)
                }
                // Otherwise: Network failure or other server error: leave in outbox for next poll
            }
        }
    }
}
