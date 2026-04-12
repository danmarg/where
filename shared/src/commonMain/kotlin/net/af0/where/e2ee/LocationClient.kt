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
    private val pollMutex = Mutex()

    /**
     * Poll all friends and the pending invite (if any).
     *
     * Poll calls are serialized to prevent concurrent ratchet state mutations.
     *
     * @param isForeground Whether the app is currently in the foreground.
     * @return List of new [UserLocation] updates received since the last poll.
     */
    suspend fun poll(isForeground: Boolean = true): List<UserLocation> =
        pollMutex.withLock {
            processOutboxes()
            val allUpdates = mutableListOf<UserLocation>()
            var lastError: Exception? = null

            val friends = store.listFriends()

            if (friends.isEmpty() && isForeground) {
                try {
                    mailboxClient.poll(baseUrl, "00000000000000000000000000000000")
                } catch (e: ServerException) {
                    if (e.statusCode != 404) lastError = e
                } catch (e: Exception) {
                    lastError = e
                }
            }

            for (friend in friends) {
                try {
                    // RESTART RECOVERY (§5.5): If the session was regenerated on startup, 
                    // force a keepalive now to establish a new memory-only DH epoch.
                    if (friend.session.needsRatchet) {
                        try { sendKeepalive(friend.id) } catch (_: Exception) { }
                    }

                    val friendUpdates = pollFriend(friend.id)
                    allUpdates.addAll(friendUpdates)
                } catch (e: Exception) {
                    lastError = e
                }
            }

            lastError?.let { throw it }
            allUpdates
        }

    /**
     * Poll a specific friend's mailbox.
     */
    internal suspend fun pollFriend(friendId: String): List<UserLocation> {
        val resultLocations = mutableListOf<UserLocation>()
        val friendBefore = store.getFriend(friendId) ?: return emptyList()
        var currentTokenToPoll = friendBefore.session.recvToken.toHex()

        // We follow token rotations up to MAX_TOKEN_FOLLOWS_PER_POLL to prevent infinite loops 
        // caused by adversarial server or client code injecting transition messages (§9.2).
        var follows = 0
        while (follows < MAX_TOKEN_FOLLOWS_PER_POLL) {
            val messages = mailboxClient.poll(baseUrl, currentTokenToPoll)
            if (messages.isEmpty()) break

            val result = store.processBatch(friendId, currentTokenToPoll, messages) ?: break

            for (out in result.outgoing) {
                mailboxClient.post(baseUrl, out.token, out.payload)
            }

            resultLocations.addAll(result.decryptedLocations.map { loc ->
                UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
            })

            // Did the recvToken change during processing? If so, follow it immediately.
            val updatedFriend = store.getFriend(friendId) ?: break
            val newToken = updatedFriend.session.recvToken.toHex()
            if (newToken != currentTokenToPoll) {
                currentTokenToPoll = newToken
                follows++
            } else {
                break // No token rotation, we've caught up
            }
        }

        // Automated keepalive (§5.3): send a keepalive message if we received a new DH key
        // but are currently not sharing location, and the session is not stale.
        val friendAfter = store.getFriend(friendId)
        if (friendAfter != null && !friendBefore.session.remoteDhPub.contentEquals(friendAfter.session.remoteDhPub)) {
            if (!friendAfter.sharingEnabled && !friendAfter.isStale) {
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
    ) {
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        var lastError: Exception? = null

        for (friend in store.listFriends()) {
            if (friend.id in pausedFriendIds) continue
            // Skip friends who have not yet confirmed the handshake, UNLESS we are the
            // initiator (Alice), in which case we must send the first message to Bob to
            // trigger confirmation on his side.
            if (!friend.isConfirmed && !friend.isInitiator) continue
            // Skip friends from whom Alice has not received a message in 7 days.
            if (friend.isStale) continue
            try {
                sendMessageToFriendInternal(friend.id, payload)
            } catch (e: Exception) {
                lastError = e
            }
        }

        lastError?.let { throw it }
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
    internal suspend fun sendKeepalive(friendId: String) {
        sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
    }

    private suspend fun sendMessageToFriendInternal(
        friendId: String,
        payload: MessagePlaintext,
    ) {
        // PERSISTENCE HYGIENE (§5.4): We advance the session and persist the 
        // encrypted message in an ATOMIC OUTBOX update BEFORE posting.
        // This ensures that if the app crashes during the post, we don't 
        // reuse the same sequence number/nonce on restart, AND we have 
        // the message ready to retry.
        val message = store.encryptAndStore(friendId, payload)
        
        // Use the outbox's token to ensure we post to the correct endpoint
        val friendAfter = store.getFriend(friendId) ?: return 
        val tokenToUse = friendAfter.outbox?.token ?: throw Exception("Outbox missing after store")
        
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
            
            // Only send fresh keepalive if we actually rotated tokens and haven't sent it,
            // AND ensure it is only sent if sharing is currently enabled (§5.3).
            if (!finalSession.sendToken.contentEquals(finalSession.prevSendToken) && updatedFriend.sharingEnabled) {
                try {
                    sendKeepalive(friendId)
                } catch (e: Exception) {
                    println("[DEBUG] Fresh keepalive transition failed: ${e.message}")
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
            val outbox = friend.outbox ?: continue
            try {
                mailboxClient.post(baseUrl, outbox.token, outbox.payload)
                store.clearOutbox(friend.id)
                // TRANSACTIONAL RECOVERY (§5.4): After recovering a lost post, 
                // we must also trigger the token transition cleanup.
                finalizeTokenTransition(friend.id)
            } catch (_: Exception) {
                // Network failure or server error: leave in outbox for next poll
            }
        }
    }
}
