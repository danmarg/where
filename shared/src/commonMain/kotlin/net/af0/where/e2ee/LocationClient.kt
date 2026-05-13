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
 */
open class LocationClient(
    baseUrl: String,
    private val store: E2eeManager,
    mailboxClient: MailboxClient = KtorMailboxClient,
) {
    /** Secondary constructor for Swift/native compatibility. */
    constructor(baseUrl: String, store: E2eeManager) : this(baseUrl, store, KtorMailboxClient)

    private val service = MailboxService(baseUrl, mailboxClient)

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

    suspend fun pollPendingInvites(): List<PendingInviteResult> =
        coroutineScope {
            val pending = store.listPendingInvites().shuffled()
            pending.map { invite ->
                async {
                    try {
                        val discoveryHex = invite.qrPayload.discoveryToken().toHex()
                        val messages = service.poll(discoveryHex)
                        val inits = messages.filterIsInstance<KeyExchangeInitPayload>()
                        val last = inits.lastOrNull()
                        if (last != null) {
                            PendingInviteResult(last, inits.size > 1, invite.qrPayload.ekPub)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        println(
                            "[LocationClient] pollPendingInvite failed for token=${invite.qrPayload.discoveryToken().toHex().take(8)}: ${e.message}",
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
        val discoveryHex = qr.discoveryToken().toHex()
        service.post(discoveryHex, initPayload)
    }

    internal suspend fun pollFriend(
        friendId: String,
        isPaused: Boolean = false,
    ): List<UserLocation> {
        val resultLocations = mutableListOf<UserLocation>()
        val friendBefore = store.getFriend(friendId) ?: return emptyList()

        val pollQueue = mutableListOf<String>()
        pollQueue.add(friendBefore.session.recvToken.toHex())
        val prev = friendBefore.session.prevRecvToken.toHex()
        if (prev.isNotEmpty() && prev != friendBefore.session.recvToken.toHex()) {
            pollQueue.add(prev)
        }

        friendBefore.session.retiredRecvTokens.forEach { tokenBytes ->
            val token = tokenBytes.toHex()
            if (!pollQueue.contains(token)) {
                pollQueue.add(token)
            }
        }
        pollQueue.shuffle()

        val polledTokens = mutableSetOf<String>()

        while (pollQueue.isNotEmpty()) {
            val currentTokenToPoll = pollQueue.removeAt(0)
            if (polledTokens.contains(currentTokenToPoll)) continue
            polledTokens.add(currentTokenToPoll)

            val messages = service.poll(currentTokenToPoll)
            if (messages.isEmpty()) continue

            try {
                val result = store.processBatch(friendId, currentTokenToPoll, messages) ?: continue

                // ACK logic: only if progress was made
                if (result.anySuccess || result.anyReplay || result.hadStateUpdate) {
                    service.ack(currentTokenToPoll, messages.size)
                }

                resultLocations.addAll(
                    result.decryptedLocations.map { loc ->
                        UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                    },
                )

                // Follow rotation if session state changed
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
        val friendBefore = store.getFriend(friendId) ?: return

        // 7-day rotation pause check
        if (friendBefore.session.isSendTokenPending) {
            val staleSince = friendBefore.session.sendTokenPendingSinceMs
            val isStale = staleSince != null && (currentTimeMillis() - staleSince) > PENDING_TRANSITION_TIMEOUT_MS
            if (isStale) {
                println("[LocationClient] pending transition stale for ${friendId.take(8)}, abandoning")
                store.abandonPendingTransition(friendId)
                return
            }
        }

        // Atomically advance state BEFORE network attempt. This ensures nonce safety.
        // Even if the network fails, we've advanced our local ratchet so a retry
        // will use a new sequence number.
        val (message, nextSession) = store.encryptAndAdvance(friendId, payload)
        val tokenToUse = if (nextSession.isSendTokenPending) nextSession.prevSendToken else nextSession.sendToken
        val tokenHex = tokenToUse.toHex()

        println(
            "[LocationClient] send: friend=${friendId.take(8)} token=${tokenHex.take(8)} " +
                "seq=${nextSession.sendSeq} type=${payload::class.simpleName} " +
                "pending=${nextSession.isSendTokenPending}",
        )

        try {
            service.post(tokenHex, message)
            finalizeTokenTransition(friendId, clearingToken = tokenHex)
        } catch (e: Exception) {
            println("[LocationClient] send failed for ${friendId.take(8)}: ${e.message}")
            throw e
        }
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
                }
            }
        }
    }
}
