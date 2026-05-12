package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Platform-specific Unicode normalization (NFKC) for homograph protection. */
internal expect fun normalizeName(name: String): String

/**
 * Storage interface for persistent E2EE state.
 * Actual implementations will wrap SharedPreferences/UserDefaults.
 */
interface RawKeyValueStorage {
    fun getString(key: String): String?

    @Throws(Exception::class)
    fun putString(
        key: String,
        value: String,
    )
}

/**
 * Friend entry containing their session state.
 */
data class FriendEntry(
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    val lastRecvTs: Long = currentTimeSeconds(),
    val isConfirmed: Boolean = false,
    val lastSentTs: Long = 0L,
    val lastPollTs: Long = 0L,
    val sharingEnabled: Boolean = true,
    val outbox: EncryptedOutboxMessage? = null,
    val pendingDiscoveryPost: PendingDiscoveryPost? = null,
    val pendingAcks: List<PendingAck> = emptyList(),
    val outboxRetryCount: Int = 0,
    val lastDecryptFailed: Boolean = false,
    val outbox429Count: Int = 0,
    val consecutiveSilentDrops: Int = 0,
) {
    companion object {
        const val ACK_TIMEOUT_SECONDS = 7 * 24 * 3600L
    }

    val isStale: Boolean
        get() {
            val now = currentTimeSeconds()
            return lastRecvTs != Long.MAX_VALUE && (now - lastRecvTs) > ACK_TIMEOUT_SECONDS
        }

    val id: String get() = session.aliceFp.toHex()
    val safetyNumber: String get() = formatSafetyNumber(safetyNumber(session.aliceEkPub, session.bobEkPub))
}

/**
 * A public view of a pending invite, suitable for UI display.
 */
@Serializable
data class PendingInviteView(
    val qrPayload: QrPayload,
    val createdAt: Long,
    val exportedAt: Long? = null,
)

internal fun PendingInvite.toView() = PendingInviteView(qrPayload, createdAt, exportedAt)

/**
 * Durably manages the E2EE state, including friends, sessions, and outboxes.
 * Acts as a coordinator between persistence and protocol logic.
 */
class E2eeManager(
    private val storage: RawKeyValueStorage,
) {
    private val persistence = E2eeStore(storage)


    val diagnosticLog: StateFlow<List<String>> = persistence.diagnosticLog

    fun addDiagnosticEvent(message: String) {
        persistence.addDiagnosticEvent(message)
    }

    fun diagnosticLogSnapshot(): List<String> = diagnosticLog.value

    suspend fun createInvite(suggestedName: String): QrPayload =
        persistence.withMetadataLock {
            if (pendingInvites.size >= MAX_PENDING_INVITES) {
                throw IllegalStateException("Too many pending invites")
            }
            val (qr, priv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            pendingInvites = pendingInvites + PendingInvite(qr, priv)
            qr
        }

    @Throws(IllegalArgumentException::class, CancellationException::class, SelfPairingException::class)
    suspend fun processKeyExchangeInit(
        payload: KeyExchangeInitPayload,
        bobName: String,
        aliceEkPub: ByteArray,
    ): FriendEntry? {
        val (pending, aliceEkPubBytes) = persistence.withMetadataLock {
            val p = pendingInvites.find { it.qrPayload.ekPub.contentEquals(aliceEkPub) }
            p to (p?.qrPayload?.ekPub)
        }
        if (pending == null || aliceEkPubBytes == null) return null

        val tokenBytes = payload.token.hexToByteArray()
        val msg =
            KeyExchangeInitMessage(
                protocolVersion = payload.v,
                token = tokenBytes,
                ekPub = payload.ekPub,
                keyConfirmation = payload.keyConfirmation,
                suggestedName = payload.suggestedName,
            )

        val session =
            try {
                KeyExchange.aliceProcessInit(
                    msg = msg,
                    aliceEkPriv = pending.aliceEkPriv,
                    aliceEkPub = aliceEkPubBytes,
                ).let {
                    if (it.isSendTokenPending) it.copy(sendTokenPendingSinceMs = currentTimeMillis()) else it
                }
            } finally {
                pending.aliceEkPriv.zeroize()
            }

        val entry = FriendEntry(
            name = sanitizeName(bobName),
            session = session,
            isInitiator = true,
            isConfirmed = true,
        )

        persistence.withFriendAndMetadataLock(entry.id) { _, metadata ->
            metadata.pendingInvites = metadata.pendingInvites.filterNot { it.qrPayload.ekPub.contentEquals(aliceEkPubBytes) }
            PersistenceAction.Update(entry) to Unit
        }

        return entry
    }

    suspend fun listPendingInvites(): List<PendingInviteView> =
        persistence.withMetadataLock {
            pendingInvites.map { it.toView() }
        }

    suspend fun clearInvite(ekPub: ByteArray) {
        persistence.withMetadataLock {
            pendingInvites = pendingInvites.filterNot { it.qrPayload.ekPub.contentEquals(ekPub) }
        }
    }

    suspend fun markInviteExported(ekPub: ByteArray) {
        persistence.withMetadataLock {
            pendingInvites = pendingInvites.map {
                if (it.qrPayload.ekPub.contentEquals(ekPub)) {
                    it.copy(exportedAt = currentTimeSeconds())
                } else {
                    it
                }
            }
        }
    }

    suspend fun confirmDiscoveryPost(id: String) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(pendingDiscoveryPost = null)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun confirmAck(id: String, token: String, n: Int) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val nextAcks = entry.pendingAcks.filterNot { it.token == token && it.n == n }
                PersistenceAction.Update(entry.copy(pendingAcks = nextAcks)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateOutboxToken(id: String, newToken: String) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null && entry.outbox != null) {
                val nextOutbox = entry.outbox.copy(token = newToken)
                PersistenceAction.Update(entry.copy(outbox = nextOutbox, outboxRetryCount = 0)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun incrementOutboxRetryCount(id: String): Int =
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val nextCount = entry.outboxRetryCount + 1
                PersistenceAction.Update(entry.copy(outboxRetryCount = nextCount)) to nextCount
            } else {
                PersistenceAction.None to 0
            }
        }

    suspend fun cleanupExpiredInvites(expirySeconds: Long = 48 * 3600L) {
        val now = currentTimeSeconds()
        val toRemove = persistence.withMetadataLock {
            val expired = pendingInvites.filter { 
                val baseTime = it.exportedAt ?: it.createdAt
                now - baseTime > expirySeconds 
            }
            if (expired.isNotEmpty()) {
                pendingInvites = pendingInvites.filter { 
                    val baseTime = it.exportedAt ?: it.createdAt
                    now - baseTime <= expirySeconds 
                }
            }
            
            // Also identify unconfirmed friends for cleanup
            val unconfirmedExpired = friends.filter { 
                !it.isConfirmed && (now - it.lastRecvTs > expirySeconds) 
            }
            expired to unconfirmedExpired
        }

        toRemove.second.forEach { friend ->
            persistence.withFriendAndMetadataLock(friend.id) { current, _ ->
                if (current != null && !current.isConfirmed && (now - current.lastRecvTs > expirySeconds)) {
                    PersistenceAction.Delete to Unit
                } else {
                    PersistenceAction.None to Unit
                }
            }
        }
    }

    suspend fun getFriend(id: String): FriendEntry? = persistence.getFriend(id)

    suspend fun listFriends(): List<FriendEntry> = persistence.listFriends()

    suspend fun renameFriend(id: String, newName: String) {
        val sanitized = sanitizeName(newName)
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(name = sanitized)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun deleteFriend(id: String) {
        persistence.withFriendAndMetadataLock(id) { _, _ ->
            PersistenceAction.Delete to Unit
        }
    }

    suspend fun encryptAndStore(
        friendId: String,
        payload: MessagePlaintext,
    ): EncryptedMessagePayload {
        return persistence.withFriendAndMetadataLock(friendId) { entry, _ ->
            if (entry == null) throw Exception("Friend not found: $friendId")
            if (entry.outbox != null) throw OutboxConflictException(friendId)

            val (newSession, message) = Session.encryptMessage(entry.session, payload)

            val seqAdvanced = newSession.sendSeq > entry.session.sendSeq ||
                !newSession.rootKey.contentEquals(entry.session.rootKey)
            check(seqAdvanced) { "Nonce safety violation: sequence number did not advance" }

            val tokenToUse = if (newSession.isSendTokenPending) newSession.prevSendToken else newSession.sendToken
            val updatedSession = if (newSession.isSendTokenPending && !entry.session.isSendTokenPending) {
                newSession.copy(sendTokenPendingSinceMs = currentTimeMillis())
            } else {
                newSession
            }

            val updatedEntry = entry.copy(
                session = updatedSession,
                outbox = EncryptedOutboxMessage(token = tokenToUse.toHex(), payload = message),
                lastSentTs = currentTimeSeconds(),
            )
            
            PersistenceAction.Update(updatedEntry) to message
        }
    }

    suspend fun updateSession(id: String, newSession: SessionState) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(session = newSession)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateFriend(id: String, transform: (FriendEntry) -> FriendEntry): FriendEntry? {
        return persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val next = transform(entry)
                PersistenceAction.Update(next) to next
            } else {
                PersistenceAction.None to null
            }
        }
    }

    suspend fun processScannedQr(
        qr: QrPayload,
        bobSuggestedName: String = "",
    ): Pair<KeyExchangeInitPayload, FriendEntry> {
        val sanitizedRequestedName = sanitizeName(bobSuggestedName)
        val (initMsg, session) = KeyExchange.bobProcessQr(qr, sanitizedRequestedName)

        val entry = FriendEntry(
            name = qr.suggestedName,
            session = session,
            isInitiator = false,
            lastRecvTs = currentTimeSeconds(),
            isConfirmed = false,
        )

        val payload = KeyExchangeInitPayload(
            v = initMsg.protocolVersion,
            token = initMsg.token.toHex(),
            ekPub = initMsg.ekPub,
            keyConfirmation = initMsg.keyConfirmation,
            suggestedName = sanitizedRequestedName,
        )

        return persistence.withFriendAndMetadataLock(entry.id) { _, metadata ->
            if (metadata.pendingInvites.any { it.qrPayload.ekPub.contentEquals(qr.ekPub) }) {
                throw SelfPairingException()
            }
            val updatedEntry = entry.copy(
                pendingDiscoveryPost = PendingDiscoveryPost(
                    discoveryToken = qr.discoveryToken().toHex(),
                    payload = payload,
                )
            )
            PersistenceAction.Update(updatedEntry) to (payload to updatedEntry)
        }
    }

    suspend fun confirmFriend(id: String) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null && !entry.isConfirmed) {
                PersistenceAction.Update(entry.copy(isConfirmed = true)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun setSharingEnabled(id: String, enabled: Boolean) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(sharingEnabled = enabled)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    internal suspend fun abandonPendingTransition(friendId: String) {
        persistence.withFriendAndMetadataLock(friendId) { entry, _ ->
            if (entry != null && entry.session.isSendTokenPending) {
                val rolledBack = entry.session.copy(
                    sendToken = entry.session.prevSendToken,
                    isSendTokenPending = false,
                    sendTokenPendingSinceMs = null,
                    needsRatchet = entry.session.needsRatchet,
                )
                PersistenceAction.Update(entry.copy(session = rolledBack, outbox = null)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val anySuccess: Boolean,
        val hadSilentDrops: Boolean,
        val anyReplay: Boolean = false,
        val failCount: Int = 0,
        val hadStateUpdate: Boolean = false,
    )

    suspend fun processBatch(
        friendId: String,
        recvToken: String,
        messages: List<MailboxPayload>,
    ): PollBatchResult? {
        return persistence.withFriendAndMetadataLock(friendId) { entry, _ ->
            if (entry == null) return@withFriendAndMetadataLock PersistenceAction.None to null

            val encryptedMessages = messages.filterIsInstance<EncryptedMessagePayload>()
            val orderedMessages = E2eeProtocol.decryptAndSort(entry.session, encryptedMessages)
            
            val result = E2eeProtocol.decryptBatch(entry.session, encryptedMessages.size, orderedMessages)
            
            val failCount = result.softFailCount + result.hardFailCount
            if (!result.anySuccess && failCount > 0) {
                addDiagnosticEvent("DECRYPT FAIL: $failCount msgs failed (${result.hardFailCount} hard, ${result.softFailCount} soft) for ${friendId.take(8)}")
            }

            val hadActivity = result.decryptedLocations.isNotEmpty() || (result.anySuccess && result.finalSession != entry.session)
            val isFailedBatch = (result.hardFailCount > 0 || result.silentDrops > 0) && !result.anySuccess
            val lastLocation = result.decryptedLocations.lastOrNull()

            val hadStateUpdate = result.finalSession.recvSeq > entry.session.recvSeq ||
                !result.finalSession.rootKey.contentEquals(entry.session.rootKey)
            
            val currentRecvToken = if (result.anySuccess || hadStateUpdate) result.finalSession.recvToken else entry.session.recvToken

            val updatedSession = if (result.finalSession.isSendTokenPending && !entry.session.isSendTokenPending) {
                result.finalSession.copy(recvToken = currentRecvToken, sendTokenPendingSinceMs = currentTimeMillis())
            } else {
                result.finalSession.copy(recvToken = currentRecvToken)
            }

            val safeToAck = (result.anySuccess && result.silentDrops == 0) ||
                (result.anyReplay && failCount == 0) ||
                hadStateUpdate

            val nextAcks = if (safeToAck) {
                entry.pendingAcks + PendingAck(token = recvToken, n = messages.size)
            } else {
                entry.pendingAcks
            }

            val updatedEntry = entry.copy(
                session = updatedSession,
                isConfirmed = entry.isConfirmed || result.anySuccess,
                lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                lastLat = lastLocation?.lat ?: entry.lastLat,
                lastLng = lastLocation?.lng ?: entry.lastLng,
                lastTs = lastLocation?.ts ?: entry.lastTs,
                lastPollTs = currentTimeSeconds(),
                lastDecryptFailed = if (encryptedMessages.isNotEmpty()) isFailedBatch else entry.lastDecryptFailed,
                pendingAcks = nextAcks,
            )

            PersistenceAction.Update(updatedEntry) to PollBatchResult(
                decryptedLocations = result.decryptedLocations,
                anySuccess = result.anySuccess,
                hadSilentDrops = result.silentDrops > 0 || (messages.size > encryptedMessages.size),
                anyReplay = result.anyReplay,
                failCount = failCount,
                hadStateUpdate = hadStateUpdate
            )

        }
    }

    suspend fun clearOutbox(id: String) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(outbox = null, outbox429Count = 0, outboxRetryCount = 0)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun clearOutboxAndUpdateSession(id: String, newSession: SessionState) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(session = newSession, outbox = null, outbox429Count = 0, outboxRetryCount = 0)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun incrementOutbox429Count(id: String): Int =
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val newCount = entry.outbox429Count + 1
                PersistenceAction.Update(entry.copy(outbox429Count = newCount)) to newCount
            } else {
                PersistenceAction.None to 0
            }
        }

    suspend fun incrementConsecutiveSilentDrops(id: String): Int =
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val nextCount = entry.consecutiveSilentDrops + 1
                PersistenceAction.Update(entry.copy(consecutiveSilentDrops = nextCount)) to nextCount
            } else {
                PersistenceAction.None to 0
            }
        }

    suspend fun resetConsecutiveSilentDrops(id: String) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null && entry.consecutiveSilentDrops != 0) {
                PersistenceAction.Update(entry.copy(consecutiveSilentDrops = 0)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateLastLocation(id: String, lat: Double, lng: Double, ts: Long) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(lastLat = lat, lastLng = lng, lastTs = ts)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateLastSentTs(id: String, ts: Long) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(lastSentTs = ts)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateLastPollTs(id: String, ts: Long) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(lastPollTs = ts)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun recordPendingAck(id: String, token: String, n: Int) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val nextAcks = entry.pendingAcks + PendingAck(token = token, n = n)
                PersistenceAction.Update(entry.copy(pendingAcks = nextAcks)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun clearSendTokenPending(id: String, clearingToken: String? = null): Boolean {
        return persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null && entry.session.isSendTokenPending) {
                if (clearingToken != null && entry.session.prevSendToken.toHex() != clearingToken) {
                    PersistenceAction.None to false
                } else {
                    val newSession = entry.session.copy(isSendTokenPending = false, sendTokenPendingSinceMs = null)
                    PersistenceAction.Update(entry.copy(session = newSession)) to true
                }
            } else {
                PersistenceAction.None to false
            }
        }
    }

    private fun sanitizeName(name: String): String =
        normalizeName(name).take(32).filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    companion object {
        const val MAX_PENDING_INVITES = 10
    }
}
