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

internal fun canonicalId(aliceFp: ByteArray, bobFp: ByteArray): String {
    val a = aliceFp.toHex()
    val b = bobFp.toHex()
    return if (a < b) "$a:$b" else "$b:$a"
}

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
    val lastDecryptFailed: Boolean = false,
) {
    companion object {
        const val ACK_TIMEOUT_SECONDS = 7 * 24 * 3600L
    }

    val isStale: Boolean
        get() {
            val now = currentTimeSeconds()
            return lastRecvTs != Long.MAX_VALUE && (now - lastRecvTs) > ACK_TIMEOUT_SECONDS
        }

    val id: String get() = canonicalId(session.aliceFp, session.bobFp)
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
    sqlDriver: app.cash.sqldelight.db.SqlDriver,
) {
    private val persistence = E2eeStore(net.af0.where.db.WhereDatabase(sqlDriver))


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
                    // Alice starts in Epoch 1 (eager ratchet), so isSendTokenPending is true.
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

    /**
     * Encrypts a message for a friend and atomically persists the advanced ratchet state.
     * This ensures that the sequence number is ALWAYS advanced locally before a network
     * attempt, preventing nonce reuse if the same message is retried later.
     */
    suspend fun encryptAndAdvance(
        friendId: String,
        payload: MessagePlaintext,
    ): Pair<EncryptedMessagePayload, SessionState> {
        return persistence.withFriendAndMetadataLock(friendId) { entry, _ ->
            if (entry == null) throw Exception("Friend not found: $friendId")

            val (newSession, message) = Session.encryptMessage(entry.session, payload)

            val seqAdvanced = newSession.sendSeq > entry.session.sendSeq ||
                !newSession.rootKey.contentEquals(entry.session.rootKey)
            check(seqAdvanced) { "Nonce safety violation: sequence number did not advance" }

            // Determine which token to use for THIS message.
            // If isSendTokenPending is true, it means THIS message is the transition message (e.g. keepalive)
            // and should be sent to the previous token.
            val tokenToUse = if (newSession.isSendTokenPending) newSession.prevSendToken else newSession.sendToken

            // Finalize state for the NEXT message.
            // We NO LONGER switch to the new token immediately. We keep isSendTokenPending=true
            // until we receive an acknowledgement from the peer (verified in processBatch).
            // This ensures DH ratchet liveness under packet loss (§6).
            val updatedSession = newSession

            val outboxMsg = EncryptedOutboxMessage(
                token = tokenToUse.toHex(),
                payload = message
            )

            val updatedEntry = entry.copy(
                session = updatedSession,
                lastSentTs = if (payload is MessagePlaintext.Location) currentTimeSeconds() else entry.lastSentTs,
            )
            
            persistence.insertOutboxInternal(
                msgId = outboxMsg.msgId,
                friendId = friendId,
                token = outboxMsg.token,
                payloadBlob = E2eeStore.json.encodeToString(MailboxPayload.serializer(), outboxMsg.payload).encodeToByteArray(),
                createdAt = outboxMsg.createdAt
            )
            
            PersistenceAction.Update(updatedEntry) to (message to updatedSession)
        }
    }

    /**
     * Atomically updates a friend's metadata.
     */
    suspend fun updateFriendMetadata(
        id: String,
        isConfirmed: Boolean? = null,
        sharingEnabled: Boolean? = null,
    ) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                val updated = entry.copy(
                    isConfirmed = isConfirmed ?: entry.isConfirmed,
                    sharingEnabled = sharingEnabled ?: entry.sharingEnabled,
                )
                PersistenceAction.Update(updated) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }


    suspend fun processScannedQr(
        qr: QrPayload,
        bobSuggestedName: String = "",
    ): Pair<KeyExchangeInitPayload, FriendEntry> {
        val sanitizedRequestedName = sanitizeName(bobSuggestedName)
        val (initMsg, session) = KeyExchange.bobProcessQr(qr, sanitizedRequestedName)

        val payload = KeyExchangeInitPayload(
            v = initMsg.protocolVersion,
            token = initMsg.token.toHex(),
            ekPub = initMsg.ekPub,
            keyConfirmation = initMsg.keyConfirmation,
            suggestedName = sanitizedRequestedName,
        )

        val entry = FriendEntry(
            name = qr.suggestedName,
            session = session,
            isInitiator = false,
            lastRecvTs = currentTimeSeconds(),
            isConfirmed = false,
        )

        return persistence.withFriendAndMetadataLock(entry.id) { _, metadata ->
            persistence.insertOutboxInternal(
                msgId = payload.msgId,
                friendId = entry.id,
                token = qr.discoveryToken().toHex(),
                payloadBlob = E2eeStore.json.encodeToString(MailboxPayload.serializer(), payload).encodeToByteArray(),
                createdAt = currentTimeMillis()
            )
            if (metadata.pendingInvites.any { it.qrPayload.ekPub.contentEquals(qr.ekPub) }) {
                throw SelfPairingException()
            }
            PersistenceAction.Update(entry) to (payload to entry)
        }
    }

    suspend fun removeFromOutbox(friendId: String, msgId: String) {
        persistence.deleteOutboxByMsgId(msgId)
    }

    suspend fun getOutbox(friendId: String): List<EncryptedOutboxMessage> = persistence.getOutbox(friendId)

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
                persistence.deleteOutboxByFriendIdInternal(friendId)
                PersistenceAction.Update(entry.copy(session = rolledBack)) to Unit
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
        val hadDhRatchet: Boolean = false,
        val shouldAck: Boolean = true,
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
            val lastLocation = result.decryptedLocations.lastOrNull()

            val hadStateUpdate = result.finalSession.recvSeq > entry.session.recvSeq ||
                !result.finalSession.rootKey.contentEquals(entry.session.rootKey)
            
            val currentRecvToken = if (result.anySuccess || hadStateUpdate) result.finalSession.recvToken else entry.session.recvToken

            val updatedSession = if (result.finalSession.isSendTokenPending) {
                if (entry.session.isSendTokenPending) {
                    // Preserve existing timestamp
                    result.finalSession.copy(
                        recvToken = currentRecvToken,
                        sendTokenPendingSinceMs = entry.session.sendTokenPendingSinceMs
                    )
                } else {
                    // New transition, set fresh timestamp
                    result.finalSession.copy(
                        recvToken = currentRecvToken,
                        sendTokenPendingSinceMs = currentTimeMillis()
                    )
                }
            } else {
                result.finalSession.copy(recvToken = currentRecvToken)
            }

            if (!updatedSession.isSendTokenPending && entry.session.isSendTokenPending) {
                addDiagnosticEvent("TRANSITION COMPLETE: ACK received for ${friendId.take(8)}")
            }

            val updatedEntry = entry.copy(
                session = updatedSession,
                isConfirmed = entry.isConfirmed || result.anySuccess,
                lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                lastLat = if (lastLocation != null && (lastLocation.ts >= (entry.lastTs ?: 0))) lastLocation.lat else entry.lastLat,
                lastLng = if (lastLocation != null && (lastLocation.ts >= (entry.lastTs ?: 0))) lastLocation.lng else entry.lastLng,
                lastTs = if (lastLocation != null && (lastLocation.ts >= (entry.lastTs ?: 0))) lastLocation.ts else entry.lastTs,
                lastPollTs = currentTimeSeconds(),
            )

            val shouldAck = result.anySuccess || hadStateUpdate || result.anyReplay || result.silentDrops > 0 || (messages.size > encryptedMessages.size)

            val hadDhRatchet = !result.finalSession.rootKey.contentEquals(entry.session.rootKey)

            PersistenceAction.Update(updatedEntry) to PollBatchResult(
                decryptedLocations = result.decryptedLocations,
                anySuccess = result.anySuccess,
                hadSilentDrops = result.silentDrops > 0 || (messages.size > encryptedMessages.size),
                anyReplay = result.anyReplay,
                failCount = failCount,
                hadStateUpdate = hadStateUpdate,
                hadDhRatchet = hadDhRatchet,
                shouldAck = shouldAck
            )

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

    suspend fun updateLastPollTs(id: String, ts: Long) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(lastPollTs = ts)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }


    private fun sanitizeName(name: String): String =
        normalizeName(name).take(32).filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    companion object {
        const val MAX_PENDING_INVITES = 10
    }
}
