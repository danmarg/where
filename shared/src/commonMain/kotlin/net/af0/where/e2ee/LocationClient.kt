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
                            val decryptedName = store.decryptSuggestedName(
                                aliceEkPub = invite.qrPayload.ekPub,
                                bobEkPub = last.ekPub,
                                encryptedName = last.encryptedName
                            )
                            if (decryptedName == null) {
                                store.addDiagnosticEvent("Failed to decrypt suggested_name for invite from discovery=$discoveryHex")
                                PendingInviteResult(
                                    payload = last,
                                    scannerEkPub = last.ekPub,
                                    inviteEkPub = invite.qrPayload.ekPub,
                                    multipleScansDetected = inits.size > 1,
                                    pairingError = "Handshake failed: Cryptographic verification error."
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

                val messages =
                    try {
                        service.poll(currentToken)
                    } catch (e: Exception) {
                        emptyList()
                    }

                if (messages.isEmpty()) {
                    stopPolling = true
                    caughtUp = true
                    continue
                }

                try {
                    val result = store.processBatch(friendId, currentToken, messages)
                    if (result == null) {
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
                                stationary = loc.stationary,
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
                    stopPolling = true
                }
            }

            try {
                store.updateLastPollTs(friendId, currentTimeSeconds())
                store.updateIsCaughtUp(friendId, caughtUp)
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
        coroutineScope {
            val friends = store.listFriends()
            friends.map { friend ->
                async {
                    runCatching {
                        val mutex = getFriendMutex(friend.id)
                        mutex.withLock {
                            processOutbox(friend.id)
                            pollFriend(friend.id)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun processOutboxes() {
        coroutineScope {
            val friends =
                runCatching {
                    store.listFriends()
                }.getOrElse { return@coroutineScope }

            friends.map { friend ->
                async {
                    runCatching {
                        val mutex = getFriendMutex(friend.id)
                        mutex.withLock {
                            processOutbox(friend.id)
                        }
                    }
                }
            }.awaitAll()
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
        stationary: Boolean = false,
    ) {
        coroutineScope {
            val ts = currentTimeSeconds()
            val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts, stationary = stationary)
            val activeFriends = store.listFriends().filter { it.id !in pausedFriendIds && !it.isStale }

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
                        }
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
        coroutineScope {
            val ts = currentTimeSeconds()
            val payload = MessagePlaintext.StoppedSharing(ts = ts)
            val activeFriends = store.listFriends().filter { it.id !in pausedFriendIds && !it.isStale }
            val deferreds = activeFriends.map { friend ->
                async {
                    runCatching {
                        val mutex = getFriendMutex(friend.id)
                        mutex.withLock {
                            sendMessageToFriendInternal(friend.id, payload)
                        }
                    }
                }
            }
            deferreds.awaitAll()
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
