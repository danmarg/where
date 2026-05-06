package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.af0.where.model.UserLocation

/** Platform-specific Unicode normalization (NFKC) for homograph protection. */
internal expect fun normalizeName(name: String): String

/**
 * Storage interface for persistent E2EE state.
 * Actual implementations will wrap SharedPreferences/UserDefaults.
 */
interface E2eeStorage {
    fun getString(key: String): String?

    @Throws(Exception::class)
    fun putString(
        key: String,
        value: String,
    )
}

/**
 * Friend entry containing their session state.
 *
 * @param isInitiator     true if the local user was "Alice" (created the QR); false if "Bob" (scanned QR).
 */
data class FriendEntry(
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    val lastRecvTs: Long = currentTimeSeconds(),
    /**
     * True if the key exchange handshake is fully confirmed (both sides derived SK).
     * Alice is confirmed as soon as she processes KeyExchangeInit; Bob is confirmed
     * once he receives any valid message from Alice.
     */
    val isConfirmed: Boolean = false,
    val lastSentTs: Long = 0L,
    val lastPollTs: Long = 0L,
    /** Whether the local user is currently sharing their location with this friend. */
    val sharingEnabled: Boolean = true,
    /** Optional outbox for transactional recovery (§5.4). */
    val outbox: EncryptedOutboxMessage? = null,
    /** Set if the last poll for this friend resulted in decryption failures. */
    val lastDecryptFailed: Boolean = false,
    /** Tracks consecutive 429 errors for the current outbox. */
    val outbox429Count: Int = 0,
) {
    companion object {
        /** §12: Surface a "no recent location" warning after 7 days of silence. */
        const val ACK_TIMEOUT_SECONDS = 7 * 24 * 3600L
    }

    /**
     * Returns true if no messages have been received for [ACK_TIMEOUT_SECONDS].
     * This is a heuristic for UI to show a "not seen recently" warning.
     */
    val isStale: Boolean
        get() {
            val now = currentTimeSeconds()
            return lastRecvTs != Long.MAX_VALUE && (now - lastRecvTs) > ACK_TIMEOUT_SECONDS
        }

    /** Computed friend ID: hex(SHA-256(EK_A.pub)) — full 64 hex chars. */
    val id: String get() = session.aliceFp.toHex()

    /** Safety number (e.g., for display in UI). Stable for the lifetime of the session. */
    val safetyNumber: String get() = formatSafetyNumber(safetyNumber(session.aliceEkPub, session.bobEkPub))
}

/**
 * Alice's pending invite state.
 */
@Serializable
internal data class PendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
    val createdAt: Long = currentTimeSeconds(),
    val exportedAt: Long? = null,
)

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
 * Uses DoubleBufferedStorage to ensure atomic updates and prevent data loss.
 *
 * @param storage The underlying persistent storage.
 */
class E2eeStore(
    private val storage: E2eeStorage,
) {
    private var friends = mutableMapOf<String, FriendEntry>()
    private var pendingInvites = mutableListOf<PendingInvite>()
    private var lastSavedTs: Long = 0L

    private val stateLock = Mutex()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    private val dbStorage = DoubleBufferedStorage(
        storage = storage,
        serializer = SerializedStore.serializer(),
        json = json,
        keyA = STORAGE_KEY_A,
        keyB = STORAGE_KEY_B,
        keyLegacy = STORAGE_KEY_LEGACY,
        timestampSelector = { it.lastSavedTs }
    )

    init {
        load()
    }

    private fun load() {
        val best = dbStorage.load()
        if (best != null) {
            applySerializedStore(best)
            lastSavedTs = best.lastSavedTs
            println("[E2eeStore] Loaded state (ts=${best.lastSavedTs})")
        } else if (storage.getString(STORAGE_KEY_A) != null || storage.getString(STORAGE_KEY_B) != null) {
            addDiagnosticEvent("CRITICAL: Storage exists but failed to parse. Using empty in-memory state to prevent data loss.")
        }
    }

    private fun applySerializedStore(serialized: SerializedStore) {
        friends =
            serialized.friends.associate { s ->
                val entry =
                    FriendEntry(
                        name = s.name,
                        session = s.session,
                        isInitiator = s.isInitiator,
                        lastLat = s.lastLat,
                        lastLng = s.lastLng,
                        lastTs = s.lastTs,
                        lastRecvTs = if (s.lastRecvTs == 0L) currentTimeSeconds() else s.lastRecvTs,
                        isConfirmed = s.isConfirmed,
                        lastSentTs = s.lastSentTs,
                        lastPollTs = s.lastPollTs,
                        sharingEnabled = s.sharingEnabled,
                        outbox = s.outbox,
                        lastDecryptFailed = s.lastDecryptFailed,
                        outbox429Count = s.outbox429Count,
                    )
                s.friendId to entry
            }.toMutableMap()

        pendingInvites = serialized.pendingInvites.toMutableList()
        if (serialized.diagnosticLog.isNotEmpty()) {
            _diagnosticLog.value = serialized.diagnosticLog
        }
    }

    private fun save(
        friendsOverride: Map<String, FriendEntry>? = null,
        pendingInvitesOverride: List<PendingInvite>? = null,
    ) {
        val friendsToSave = friendsOverride ?: friends
        val invitesToSave = pendingInvitesOverride ?: pendingInvites
        
        val now = currentTimeSeconds()
        // Ensure monotonicity even if system clock is slow or resolution is low (tests)
        val saveTs = if (now <= lastSavedTs) lastSavedTs + 1 else now

        val serialized =
            SerializedStore(
                friends =
                    friendsToSave.values.map { f ->
                        SerializedFriendEntry(
                            friendId = f.id,
                            name = f.name,
                            session = f.session,
                            isInitiator = f.isInitiator,
                            lastLat = f.lastLat,
                            lastLng = f.lastLng,
                            lastTs = f.lastTs,
                            lastRecvTs = f.lastRecvTs,
                            isConfirmed = f.isConfirmed,
                            lastSentTs = f.lastSentTs,
                            lastPollTs = f.lastPollTs,
                            sharingEnabled = f.sharingEnabled,
                            outbox = f.outbox,
                            lastDecryptFailed = f.lastDecryptFailed,
                            outbox429Count = f.outbox429Count,
                        )
                    },
                pendingInvites = invitesToSave,
                diagnosticLog = _diagnosticLog.value,
                lastSavedTs = saveTs,
            )

        dbStorage.save(serialized)
        
        // Update in-memory state
        if (friendsOverride != null) {
            friends = friendsOverride.toMutableMap()
        }
        if (pendingInvitesOverride != null) {
            pendingInvites = pendingInvitesOverride.toMutableList()
        }
        lastSavedTs = saveTs
    }

    fun addDiagnosticEvent(message: String) {
        val t = currentTimeSeconds()
        val s = (t % 86400).toInt()
        val entry = "${(s / 3600).toString().padStart(2, '0')}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')} $message"
        _diagnosticLog.update { current ->
            (listOf(entry) + current).take(MAX_DIAGNOSTIC_EVENTS)
        }
    }

    fun diagnosticLogSnapshot(): List<String> = _diagnosticLog.value

    suspend fun createInvite(suggestedName: String): QrPayload =
        stateLock.withLock {
            if (pendingInvites.size >= MAX_PENDING_INVITES) {
                throw IllegalStateException("Too many pending invites")
            }
            val (qr, priv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            pendingInvites.add(PendingInvite(qr, priv))
            save()
            qr
        }

    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun processKeyExchangeInit(
        payload: KeyExchangeInitPayload,
        bobName: String,
        aliceEkPub: ByteArray,
    ): FriendEntry? =
        stateLock.withLock {
            val pending =
                pendingInvites.find {
                    it.qrPayload.ekPub.contentEquals(aliceEkPub)
                } ?: return@withLock null

            val tokenBytes = payload.token.hexToByteArray()
            val msg =
                KeyExchangeInitMessage(
                    protocolVersion = payload.v,
                    token = tokenBytes,
                    ekPub = payload.ekPub,
                    keyConfirmation = payload.keyConfirmation,
                    suggestedName = payload.suggestedName,
                )

            try {
                val session =
                    KeyExchange.aliceProcessInit(
                        msg = msg,
                        aliceEkPriv = pending.aliceEkPriv,
                        aliceEkPub = pending.qrPayload.ekPub,
                    )
                val entry =
                    FriendEntry(
                        name = sanitizeName(bobName),
                        session = session,
                        isInitiator = true,
                        lastRecvTs = currentTimeSeconds(),
                        isConfirmed = true,
                    )

                val newFriends = friends + (entry.id to entry)
                val newInvites = pendingInvites.filter { it != pending }
                save(friendsOverride = newFriends, pendingInvitesOverride = newInvites)

                pending.aliceEkPriv.zeroize()
                entry
            } catch (e: AuthenticationException) {
                throw e
            } catch (e: Exception) {
                throw e
            }
        }

    suspend fun listPendingInvites(): List<PendingInviteView> = stateLock.withLock { pendingInvites.map { it.toView() } }

    suspend fun clearInvite(ekPub: ByteArray) {
        stateLock.withLock {
            val newInvites = pendingInvites.filterNot { it.qrPayload.ekPub.contentEquals(ekPub) }
            save(pendingInvitesOverride = newInvites)
        }
    }

    suspend fun markInviteExported(ekPub: ByteArray) {
        stateLock.withLock {
            val idx = pendingInvites.indexOfFirst { it.qrPayload.ekPub.contentEquals(ekPub) }
            if (idx != -1) {
                val invite = pendingInvites[idx]
                if (invite.exportedAt == null) {
                    val newInvites = pendingInvites.toMutableList()
                    newInvites[idx] = invite.copy(exportedAt = currentTimeSeconds())
                    save(pendingInvitesOverride = newInvites)
                }
            }
        }
    }

    suspend fun cleanupExpiredInvites(expirySeconds: Long = 48 * 3600L) {
        stateLock.withLock {
            val now = currentTimeSeconds()
            val newInvites =
                pendingInvites.filterNot {
                    val baseTime = it.exportedAt ?: it.createdAt
                    now - baseTime > expirySeconds
                }

            val toRemove =
                friends.values.filter {
                    !it.isConfirmed && (now - it.lastRecvTs > expirySeconds)
                }.map { it.id }

            if (newInvites.size != pendingInvites.size || toRemove.isNotEmpty()) {
                val newFriends = friends.toMutableMap()
                toRemove.forEach { newFriends.remove(it) }
                save(friendsOverride = newFriends, pendingInvitesOverride = newInvites)
            }
        }
    }

    suspend fun getFriend(id: String): FriendEntry? = stateLock.withLock { friends[id] }

    suspend fun listFriends(): List<FriendEntry> = stateLock.withLock { friends.values.toList() }

    suspend fun renameFriend(
        id: String,
        newName: String,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(name = sanitizeName(newName))
            save()
        }
    }

    suspend fun deleteFriend(id: String) {
        stateLock.withLock {
            if (friends.containsKey(id)) {
                val newFriends = friends.toMutableMap()
                newFriends.remove(id)
                save(friendsOverride = newFriends)
            }
        }
    }

    suspend fun updateSession(
        id: String,
        newSession: SessionState,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends = friends + (id to entry.copy(session = newSession))
            save(friendsOverride = newFriends)
        }
    }

    suspend fun updateFriend(
        id: String,
        transform: (FriendEntry) -> FriendEntry,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends = friends + (id to transform(entry))
            save(friendsOverride = newFriends)
        }
    }

    suspend fun processScannedQr(
        qr: QrPayload,
        bobSuggestedName: String = "",
    ): Pair<KeyExchangeInitPayload, FriendEntry> =
        stateLock.withLock {
            if (pendingInvites.any { it.qrPayload.ekPub.contentEquals(qr.ekPub) }) {
                throw IllegalArgumentException("Cannot pair with yourself")
            }
            val sanitizedRequestedName = sanitizeName(bobSuggestedName)
            val (initMsg, session) = KeyExchange.bobProcessQr(qr, sanitizedRequestedName)

            val entry =
                FriendEntry(
                    name = qr.suggestedName,
                    session = session,
                    isInitiator = false,
                    lastRecvTs = currentTimeSeconds(),
                    isConfirmed = false,
                )
            friends[entry.id] = entry
            save()

            val payload =
                KeyExchangeInitPayload(
                    v = initMsg.protocolVersion,
                    token = initMsg.token.toHex(),
                    ekPub = initMsg.ekPub,
                    keyConfirmation = initMsg.keyConfirmation,
                    suggestedName = sanitizedRequestedName,
                )
            payload to entry
        }

    suspend fun confirmFriend(id: String) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            if (entry.isConfirmed) return@withLock
            friends[id] = entry.copy(isConfirmed = true)
            save()
        }
    }

    suspend fun setSharingEnabled(
        id: String,
        enabled: Boolean,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(sharingEnabled = enabled)
            save()
        }
    }

    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val anySuccess: Boolean,
        val hadSilentDrops: Boolean,
    )

    suspend fun processBatch(
        friendId: String,
        recvToken: String,
        messages: List<MailboxPayload>,
    ): PollBatchResult? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null

            val decryptedLocations = mutableListOf<LocationPlaintext>()
            val encryptedMessages = messages.filterIsInstance<EncryptedMessagePayload>()
            val orderedMessages = BatchProcessor.decryptAndSort(entry.session, encryptedMessages)

            var currentSession = entry.session
            var anySuccess = false
            var failCount = 0

            for ((_, msg) in orderedMessages) {
                try {
                    val (newSession, pt) = Session.decryptMessage(currentSession, msg)
                    currentSession = newSession
                    anySuccess = true
                    if (pt is MessagePlaintext.Location) {
                        decryptedLocations.add(
                            LocationPlaintext(
                                lat = pt.lat,
                                lng = pt.lng,
                                acc = pt.acc,
                                ts = pt.ts,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    failCount++
                }
            }
            if (!anySuccess && failCount > 0) {
                addDiagnosticEvent("DECRYPT FAIL: $failCount msgs failed for ${friendId.take(8)}")
            }

            val silentDrops = encryptedMessages.size - orderedMessages.size
            val hadActivity = decryptedLocations.isNotEmpty() || (anySuccess && currentSession != entry.session)

            val totalProcessed = orderedMessages.size
            val isFailedBatch = (totalProcessed > 0 && !anySuccess && failCount > 0) || (silentDrops > 0 && !anySuccess)

            val lastLocation = decryptedLocations.lastOrNull()
            val newFriends = friends + (friendId to entry.copy(
                session = currentSession,
                lastLat = lastLocation?.lat ?: entry.lastLat,
                lastLng = lastLocation?.lng ?: entry.lastLng,
                lastTs = lastLocation?.ts ?: entry.lastTs,
                lastRecvTs = if (anySuccess) currentTimeSeconds() else entry.lastRecvTs,
                lastPollTs = currentTimeSeconds(),
                isConfirmed = entry.isConfirmed || anySuccess,
                lastDecryptFailed = if (encryptedMessages.isNotEmpty()) isFailedBatch else entry.lastDecryptFailed,
            ))
            save(friendsOverride = newFriends)

            PollBatchResult(decryptedLocations, anySuccess, silentDrops > 0)
        }

    suspend fun encryptAndStore(
        friendId: String,
        payload: MessagePlaintext,
    ): EncryptedMessagePayload =
        stateLock.withLock {
            val entry = friends[friendId] ?: throw Exception("Friend not found")
            check(entry.outbox == null) { "Outbox already pending" }

            val (newSession, message) = Session.encryptMessage(entry.session, payload)
            val tokenToUse = if (newSession.isSendTokenPending) newSession.prevSendToken else newSession.sendToken

            friends[friendId] =
                entry.copy(
                    session = newSession,
                    outbox = EncryptedOutboxMessage(token = tokenToUse.toHex(), payload = message),
                    lastSentTs = currentTimeSeconds(),
                )
            save()
            message
        }

    suspend fun clearOutbox(id: String) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(outbox = null, outbox429Count = 0)
            save()
        }
    }

    suspend fun clearOutboxAndUpdateSession(
        id: String,
        newSession: SessionState,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(session = newSession, outbox = null, outbox429Count = 0)
            save()
        }
    }

    suspend fun incrementOutbox429Count(id: String): Int =
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock 0
            val newCount = entry.outbox429Count + 1
            friends[id] = entry.copy(outbox429Count = newCount)
            save()
            newCount
        }

    suspend fun updateLastLocation(
        id: String,
        lat: Double,
        lng: Double,
        ts: Long,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends = friends + (id to entry.copy(lastLat = lat, lastLng = lng, lastTs = ts))
            save(friendsOverride = newFriends)
        }
    }

    suspend fun updateLastSentTs(id: String, ts: Long) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(lastSentTs = ts)
            save()
        }
    }

    suspend fun updateLastPollTs(id: String, ts: Long) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(lastPollTs = ts)
            save()
        }
    }

    suspend fun clearSendTokenPending(id: String, clearingToken: String? = null): Boolean =
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock false
            if (!entry.session.isSendTokenPending) return@withLock false
            if (clearingToken != null && entry.session.prevSendToken.toHex() != clearingToken) return@withLock false
            
            friends[id] = entry.copy(session = entry.session.copy(isSendTokenPending = false))
            save()
            true
        }

    private fun sanitizeName(name: String): String = normalizeName(name).take(32).filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    companion object {
        private const val STORAGE_KEY_A = "e2ee_store_a"
        private const val STORAGE_KEY_B = "e2ee_store_b"
        private const val STORAGE_KEY_LEGACY = "e2ee_store"
        const val MAX_PENDING_INVITES = 10
    }
}

@Serializable
internal data class SerializedFriendEntry(
    val friendId: String,
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    val lastRecvTs: Long = 0L,
    val isConfirmed: Boolean = false,
    val lastSentTs: Long = 0L,
    val lastPollTs: Long = 0L,
    val sharingEnabled: Boolean = true,
    val outbox: EncryptedOutboxMessage? = null,
    val lastDecryptFailed: Boolean = false,
    val outbox429Count: Int = 0,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvites: List<PendingInvite> = emptyList(),
    val diagnosticLog: List<String> = emptyList(),
    val lastSavedTs: Long = 0L,
)
