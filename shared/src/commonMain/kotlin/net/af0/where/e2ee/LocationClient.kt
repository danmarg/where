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
    val mailbox: MailboxClient = KtorMailboxClient,
    var enableAutomatedKeepalives: Boolean = true,
) {
    /** Secondary constructor for Swift/native compatibility. */
    constructor(baseUrl: String, store: E2eeManager) : this(baseUrl, store, KtorMailboxClient)

    private val service = MailboxService(baseUrl, mailbox)

    private val friendMutexes = mutableMapOf<String, Mutex>()
    private val silentDropRetries = mutableMapOf<String, Int>()
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
                                    Pair(emptyList<UserLocation>(), e)
                                } finally {
                                    inFlightMutex.withLock {
                                        inFlightPolls.remove(friend.id)
                                    }
                                }
                            }
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
                            PendingInviteResult(
                                payload = last,
                                scannerEkPub = last.ekPub,
                                inviteEkPub = invite.qrPayload.ekPub,
                                multipleScansDetected = inits.size > 1,
                            )
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
    ): List<UserLocation> =
        coroutineScope {
            val resultLocations = mutableListOf<UserLocation>()
            val friendBefore = store.getFriend(friendId) ?: return@coroutineScope emptyList()

            // --- Strict Single-Token Polling ---
            // We only poll the current recvToken. The WAL ensures that transition messages
            // are delivered to the old token before subsequent messages are sent to the new one.
            val currentToken = friendBefore.session.recvToken.toHex()

            val messages =
                try {
                    service.poll(currentToken)
                } catch (e: Exception) {
                    emptyList()
                }

            if (messages.isNotEmpty()) {
                try {
                    val result = store.processBatch(friendId, currentToken, messages)
                    if (result != null) {
                        var idsToAck = result.processedIds
                        if (idsToAck.isEmpty() && messages.isNotEmpty()) {
                            val retryKey = "$friendId:$currentToken"
                            val currentRetries = (silentDropRetries[retryKey] ?: 0) + 1
                            silentDropRetries[retryKey] = currentRetries

                            if (currentRetries >= MAX_SILENT_DROP_RETRIES) {
                                store.addDiagnosticEvent("force-ACK $friendId after $currentRetries silent drops on $currentToken")
                                idsToAck = messages.map { it.msgId }
                                silentDropRetries.remove(retryKey)
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
                                UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                            },
                        )

                        // If a state update occurred (e.g. token transition), poll again immediately
                        // to catch any messages sent to the new token.
                        if (result.hadStateUpdate) {
                            val friendAfterUpdate = store.getFriend(friendId)
                            if (friendAfterUpdate != null) {
                                val nextToken = friendAfterUpdate.session.recvToken.toHex()
                                if (nextToken != currentToken) {
                                    val extraMessages =
                                        try {
                                            service.poll(nextToken)
                                        } catch (e: Exception) {
                                            emptyList()
                                        }
                                    if (extraMessages.isNotEmpty()) {
                                        val extraResult = store.processBatch(friendId, nextToken, extraMessages)
                                        if (extraResult != null) {
                                            if (extraResult.processedIds.isNotEmpty()) {
                                                service.ackIds(nextToken, extraResult.processedIds)
                                            }
                                            resultLocations.addAll(
                                                extraResult.decryptedLocations.map { loc ->
                                                    UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            try {
                store.updateLastPollTs(friendId, currentTimeSeconds())
            } catch (e: Exception) {
            }

            // Recovery: process any pending outbox messages for this friend
            try {
                processOutbox(friendId)
            } catch (e: Exception) {
                // Ignore
            }

            val friendAfter = store.getFriend(friendId)
            if (friendAfter != null) {
                val now = currentTimeSeconds()

                // Automated Keepalive Rules:
                // 1. We haven't heard from them (lastRecvTs) for more than 30 seconds.
                // Safety: only send if we have nothing else pending for them (outbox is empty).
                val threshold = 30
                val isFriendSilent = (now - friendAfter.lastRecvTs >= threshold)

                if (enableAutomatedKeepalives && isFriendSilent) {
                    if (!friendAfter.isStale && friendAfter.isConfirmed) {
                        // Check outbox to avoid redundant keepalives
                        val outbox = store.getOutbox(friendId)
                        if (outbox.isEmpty()) {
                            try {
                                sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive())
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                }
            }

            resultLocations
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
                    // Ignore
                }
            }
        }
    }

    suspend fun processOutboxes() {
        val friends =
            try {
                store.listFriends()
            } catch (e: Exception) {
                return
            }

        friends.forEach { friend ->
            try {
                val mutex = getFriendMutex(friend.id)
                mutex.withLock {
                    processOutbox(friend.id)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private suspend fun processOutbox(friendId: String) {
        val outbox = store.getOutbox(friendId)
        if (outbox.isEmpty()) return

        for (outboxMsg in outbox) {
            try {
                service.post(outboxMsg.token, outboxMsg.payload)
                store.removeFromOutbox(friendId, outboxMsg.msgId)
            } catch (e: Exception) {
                break // Stop on first failure to preserve order
            }
        }
    }

    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
    ) {
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

    private suspend fun sendMessageToFriendInternal(
        friendId: String,
        payload: MessagePlaintext,
    ) {
        // WAL Safety: If the outbox is not empty, we MUST NOT generate a new message.
        // We instead retry the existing outbox. This bounds the queue and prevents nonce reuse.
        var currentOutbox = store.getOutbox(friendId)
        if (currentOutbox.isNotEmpty()) {
            try {
                processOutbox(friendId)
            } catch (e: Exception) {
                // Ignore
            }

            // Re-check: if still not empty (e.g. network fail), THEN we must stop to maintain order.
            if (store.getOutbox(friendId).isNotEmpty()) {
                return
            }
        }

        val (message, session) = store.encryptAndAdvance(friendId, payload)

        // We trigger sequential outbox processing for this friend.
        // This ensures messages are sent in order (0, 1, 2...) even if multiple calls happen rapidly.
        processOutbox(friendId)
    }
}
