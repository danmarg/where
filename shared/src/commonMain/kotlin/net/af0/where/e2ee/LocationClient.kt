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
            try {
                processOutboxes()
            } catch (e: Exception) {
                println("[LocationClient] poll: initial processOutboxes failed: ${e.message}")
            }

            val now = currentTimeSeconds()
            if (now - lastCleanupTime > 3600) {
                try {
                    store.cleanupExpiredInvites()
                    lastCleanupTime = now
                } catch (e: Exception) {
                    println("[LocationClient] cleanup expired invites failed: ${e.message}")
                }
            }

            val friends = try { 
                store.listFriends() 
            } catch (e: Exception) {
                println("[LocationClient] poll: failed to list friends: ${e.message}")
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
                        } catch (e: Exception) {
                            println("[LocationClient] poll: mutex or setup failed for ${friend.id.take(8)}: ${e.message}")
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
                println("[LocationClient] poll: final processOutboxes failed: ${e.message}")
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
        
        // WAL: Cleanup the outbox for the newly created friendship
        val friendId = sha256(qr.ekPub).toHex()
        store.removeFromOutbox(friendId, initPayload.msgId)
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

        // println("DEBUG: Polling tokens for friend ${friendId.take(4)}: ${pollQueue.map { it.take(8) }}")

        val polledTokens = mutableSetOf<String>()

        while (pollQueue.isNotEmpty()) {
            val currentTokenToPoll = pollQueue.removeAt(0)
            if (polledTokens.contains(currentTokenToPoll)) continue
            polledTokens.add(currentTokenToPoll)

            try {
                val messages = service.poll(currentTokenToPoll)
                if (messages.isEmpty()) continue

                val result = store.processBatch(friendId, currentTokenToPoll, messages) ?: continue

                // Batch ACK logic
                if (result.shouldAck) {
                    try {
                        service.ackIds(currentTokenToPoll, result.processedIds)
                    } catch (e: Exception) {
                        println("[LocationClient] pollFriend: ackIds failed for ${currentTokenToPoll.take(8)}: ${e.message}")
                    }
                }
                val updatedFriend = store.getFriend(friendId) ?: break

                resultLocations.addAll(
                    result.decryptedLocations.map { loc ->
                        UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                    },
                )

                // Follow rotation if session state changed
                val newToken = updatedFriend.session.recvToken.toHex()
                if (newToken != currentTokenToPoll) {
                    pollQueue.add(newToken)
                }
            } catch (e: Exception) {
                println("[LocationClient] pollFriend: error for token ${currentTokenToPoll.take(8)}: ${e.message}")
                // Continue to next token in queue
            }
        }

        try {
            store.updateLastPollTs(friendId, currentTimeSeconds())
        } catch (e: Exception) {}

        // Recovery: process any pending outbox messages for this friend
        try {
            processOutbox(friendId)
        } catch (e: Exception) {
            println("[LocationClient] pollFriend: processOutbox failed for ${friendId.take(8)}: ${e.message}")
        }

        val friendAfter = store.getFriend(friendId)
        if (friendAfter != null) {
            val now = currentTimeSeconds()
            
            // Automated Keepalive Rules:
            // 1. We are pending a send token ACK (isSendTokenPending) for more than 10 seconds.
            // (We no longer eagerly respond to new DH keys with keepalives to avoid ping-pong loops).
            val retryPendingAck = friendAfter.session.isSendTokenPending && (now - friendAfter.lastSentTs >= 10)
            
            if (retryPendingAck) {
                if (!friendAfter.isStale) {
                    println("[LocationClient] pollFriend: retrying pending ACK with keepalive for ${friendId.take(8)}")
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

    suspend fun syncNow() {
        val friends = store.listFriends()
        friends.forEach { friend ->
            val mutex = getFriendMutex(friend.id)
            mutex.withLock {
                processOutbox(friend.id)
                try {
                    pollFriend(friend.id)
                } catch (e: Exception) {
                    println("[LocationClient] syncNow: poll failed for ${friend.id.take(8)}: ${e.message}")
                }
            }
        }
    }

    suspend fun processOutboxes() {
        val friends = try { 
            store.listFriends() 
        } catch (e: Exception) {
            println("[LocationClient] processOutboxes: failed to list friends: ${e.message}")
            return
        }
        
        friends.forEach { friend ->
            try {
                val mutex = getFriendMutex(friend.id)
                mutex.withLock {
                    processOutbox(friend.id)
                }
            } catch (e: Exception) {
                println("[LocationClient] processOutboxes: failed for ${friend.id.take(8)}: ${e.message}")
            }
        }
    }

    private suspend fun processOutbox(friendId: String) {
        val outbox = store.getOutbox(friendId)
        if (outbox.isEmpty()) return

        println("[LocationClient] processOutbox: retrying ${outbox.size} messages for ${friendId.take(8)}")
        
        for (outboxMsg in outbox) {
            try {
                service.post(outboxMsg.token, outboxMsg.payload)
                store.removeFromOutbox(friendId, outboxMsg.msgId)
            } catch (e: Exception) {
                println("[LocationClient] processOutbox: retry failed for ${friendId.take(8)}: ${e.message}")
                // Stop processing this outbox on first error to maintain ordering if needed, 
                // though the protocol handles out-of-order. 
                // However, if it's a network error, further attempts will likely fail too.
                break
            }
        }
    }

    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
    ) {
        processOutboxes()
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        val activeFriends = store.listFriends().filter { it.id !in pausedFriendIds && !it.isStale }

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


    private suspend fun sendMessageToFriendInternal(friendId: String, payload: MessagePlaintext) {
        val (message, session) = store.encryptAndAdvance(friendId, payload)
        
        
        // We trigger sequential outbox processing for this friend.
        // This ensures messages are sent in order (0, 1, 2...) even if multiple calls happen rapidly.
        processOutbox(friendId)
    }

}
