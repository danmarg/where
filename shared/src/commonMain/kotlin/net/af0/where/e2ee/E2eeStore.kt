package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val lastSavedTs: Long = 0L,
)

@Serializable
internal data class GlobalMetadata(
    val friendIds: List<String> = emptyList(),
    val pendingInvites: List<PendingInvite> = emptyList(),
    val diagnosticLog: List<String> = emptyList(),
    val lastSavedTs: Long = 0L,
)

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
    private var lastSavedGlobalTs: Long = 0L

    /** Monotonic clock to ensure absolute ordering of all saves (global and per-friend). */
    private var lastUsedTs: Long = 0L

    private val stateLock = Mutex()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    private val globalDb =
        DoubleBufferedStorage(
            storage = storage,
            serializer = GlobalMetadata.serializer(),
            json = json,
            timestampSelector = { it.lastSavedTs },
        )

    private val friendDb =
        DoubleBufferedStorage(
            storage = storage,
            serializer = SerializedFriendEntry.serializer(),
            json = json,
            timestampSelector = { it.lastSavedTs },
        )

    init {
        load()
    }

    private fun load() {
        // 1. Try to load new GlobalMetadata
        val global = globalDb.load(STORAGE_KEY_GLOBAL)

        // 2. Try to load legacy SerializedStore for migration
        val legacyStorage =
            DoubleBufferedStorage(
                storage = storage,
                serializer = SerializedStore.serializer(),
                json = json,
                timestampSelector = { it.lastSavedTs },
            )
        // Legacy keys were e2ee_store_a, e2ee_store_b, and e2ee_store (as the direct fallback)
        val legacy = legacyStorage.load(STORAGE_KEY_LEGACY_BASE, STORAGE_KEY_LEGACY_SINGLE)

        if (global != null && (legacy == null || global.lastSavedTs >= legacy.lastSavedTs)) {
            // New format is up to date
            lastSavedGlobalTs = global.lastSavedTs
            lastUsedTs = maxOf(lastUsedTs, global.lastSavedTs)
            pendingInvites = global.pendingInvites.toMutableList()
            _diagnosticLog.value = global.diagnosticLog

            // Load each friend
            friends.clear()
            global.friendIds.forEach { id ->
                val s = friendDb.load(friendKey(id))
                if (s != null) {
                    friends[id] = s.toEntry()
                    lastUsedTs = maxOf(lastUsedTs, s.lastSavedTs)
                } else {
                    addDiagnosticEvent("CRITICAL: Failed to load friend $id")
                }
            }
            println("[E2eeStore] Loaded granular state (ts=$lastUsedTs, friends=${friends.size})")
        } else if (legacy != null) {
            // Migrate from legacy format
            println("[E2eeStore] Migrating legacy state (ts=${legacy.lastSavedTs})")
            lastUsedTs = legacy.lastSavedTs
            applyLegacyStore(legacy)
            // Perform an immediate granular save to finalize migration
            saveAll()
            // Clear legacy keys
            storage.putString(STORAGE_KEY_LEGACY_SINGLE, "")
            storage.putString("${STORAGE_KEY_LEGACY_BASE}_a", "")
            storage.putString("${STORAGE_KEY_LEGACY_BASE}_b", "")
        } else if (storage.getString("${STORAGE_KEY_GLOBAL}_a") != null) {
            addDiagnosticEvent("CRITICAL: Global storage exists but failed to parse. Using empty state.")
        }
    }

    private fun applyLegacyStore(serialized: SerializedStore) {
        friends =
            serialized.friends.associate { s ->
                val entry = s.toEntry()
                s.friendId to entry
            }.toMutableMap()

        pendingInvites = serialized.pendingInvites.toMutableList()
        _diagnosticLog.value = serialized.diagnosticLog
        lastSavedGlobalTs = serialized.lastSavedTs
    }

    private fun nextTs(): Long {
        val now = currentTimeSeconds()
        lastUsedTs = if (now <= lastUsedTs) lastUsedTs + 1 else now
        return lastUsedTs
    }

    private fun SerializedFriendEntry.toEntry() =
        FriendEntry(
            name = name,
            session = session,
            isInitiator = isInitiator,
            lastLat = lastLat,
            lastLng = lastLng,
            lastTs = lastTs,
            lastRecvTs = if (lastRecvTs == 0L) currentTimeSeconds() else lastRecvTs,
            isConfirmed = isConfirmed,
            lastSentTs = lastSentTs,
            lastPollTs = lastPollTs,
            sharingEnabled = sharingEnabled,
            outbox = outbox,
            lastDecryptFailed = lastDecryptFailed,
            outbox429Count = outbox429Count,
        )

    private fun FriendEntry.toSerialized(ts: Long) =
        SerializedFriendEntry(
            friendId = id,
            name = name,
            session = session,
            isInitiator = isInitiator,
            lastLat = lastLat,
            lastLng = lastLng,
            lastTs = lastTs,
            lastRecvTs = lastRecvTs,
            isConfirmed = isConfirmed,
            lastSentTs = lastSentTs,
            lastPollTs = lastPollTs,
            sharingEnabled = sharingEnabled,
            outbox = outbox,
            lastDecryptFailed = lastDecryptFailed,
            outbox429Count = outbox429Count,
            lastSavedTs = ts,
        )

    /** Saves only the global metadata. Throws on failure. */
    private fun saveGlobalInternal(
        nextFriendIds: List<String>? = null,
        nextInvites: List<PendingInvite>? = null,
        nextLog: List<String>? = null,
    ) {
        val saveTs = nextTs()

        val metadata =
            GlobalMetadata(
                friendIds = nextFriendIds ?: friends.keys.toList(),
                pendingInvites = nextInvites ?: pendingInvites,
                diagnosticLog = nextLog ?: _diagnosticLog.value,
                lastSavedTs = saveTs,
            )
        globalDb.save(STORAGE_KEY_GLOBAL, metadata)

        // Update memory only after successful save
        lastSavedGlobalTs = saveTs
        if (nextInvites != null) pendingInvites = nextInvites.toMutableList()
        if (nextLog != null) _diagnosticLog.value = nextLog
    }

    /** Saves a single friend entry. Throws on failure. */
    private fun saveFriendInternal(
        friendId: String,
        entry: FriendEntry,
    ) {
        saveFriendInternalWithTs(friendId, entry, nextTs())
    }

    private fun saveFriendInternalWithTs(
        friendId: String,
        entry: FriendEntry,
        ts: Long,
    ) {
        friendDb.save(friendKey(friendId), entry.toSerialized(ts))
        // Update memory only after successful save
        friends[friendId] = entry
    }

    /** Saves everything (used for initialization/migration). */
    private fun saveAll() {
        saveGlobalInternal()
        friends.forEach { (id, entry) -> saveFriendInternal(id, entry) }
    }

    fun addDiagnosticEvent(message: String) {
        val t = currentTimeSeconds()
        val s = (t % 86400).toInt()
        val entry = "${(s / 3600).toString().padStart(
            2,
            '0',
        )}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')} $message"
        _diagnosticLog.value = (listOf(entry) + _diagnosticLog.value).take(MAX_DIAGNOSTIC_EVENTS)
    }

    fun diagnosticLogSnapshot(): List<String> = _diagnosticLog.value

    suspend fun createInvite(suggestedName: String): QrPayload =
        stateLock.withLock {
            if (pendingInvites.size >= MAX_PENDING_INVITES) {
                throw IllegalStateException("Too many pending invites")
            }
            val (qr, priv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            val nextInvites = pendingInvites + PendingInvite(qr, priv)
            saveGlobalInternal(nextInvites = nextInvites)
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
                    ).let {
                        if (it.isSendTokenPending) it.copy(sendTokenPendingSinceMs = currentTimeMillis()) else it
                    }
                val entry =
                    FriendEntry(
                        name = sanitizeName(bobName),
                        session = session,
                        isInitiator = true,
                        lastRecvTs = currentTimeSeconds(),
                        isConfirmed = true,
                    )

                val nextInvites = pendingInvites.filter { it != pending }
                val nextFriendIds = friends.keys.toList() + entry.id

                // Order matters: save friend first, then global index
                saveFriendInternal(entry.id, entry)
                saveGlobalInternal(nextFriendIds = nextFriendIds, nextInvites = nextInvites)

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
            val nextInvites = pendingInvites.filterNot { it.qrPayload.ekPub.contentEquals(ekPub) }
            saveGlobalInternal(nextInvites = nextInvites)
        }
    }

    suspend fun markInviteExported(ekPub: ByteArray) {
        stateLock.withLock {
            val idx = pendingInvites.indexOfFirst { it.qrPayload.ekPub.contentEquals(ekPub) }
            if (idx != -1) {
                val invite = pendingInvites[idx]
                if (invite.exportedAt == null) {
                    val nextInvites = pendingInvites.toMutableList()
                    nextInvites[idx] = invite.copy(exportedAt = currentTimeSeconds())
                    saveGlobalInternal(nextInvites = nextInvites)
                }
            }
        }
    }

    suspend fun cleanupExpiredInvites(expirySeconds: Long = 48 * 3600L) {
        stateLock.withLock {
            val now = currentTimeSeconds()
            val nextInvites =
                pendingInvites.filterNot {
                    val baseTime = it.exportedAt ?: it.createdAt
                    now - baseTime > expirySeconds
                }

            val toRemove =
                friends.values.filter {
                    !it.isConfirmed && (now - it.lastRecvTs > expirySeconds)
                }.map { it.id }

            if (nextInvites.size != pendingInvites.size || toRemove.isNotEmpty()) {
                val nextFriendIds = friends.keys.filterNot { toRemove.contains(it) }
                saveGlobalInternal(nextFriendIds = nextFriendIds, nextInvites = nextInvites)

                // Remove from memory and clear underlying storage slots
                toRemove.forEach { id ->
                    friends.remove(id)
                    storage.putString("${friendKey(id)}_a", "")
                    storage.putString("${friendKey(id)}_b", "")
                }
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
            saveFriendInternal(id, entry.copy(name = sanitizeName(newName)))
        }
    }

    suspend fun deleteFriend(id: String) {
        stateLock.withLock {
            if (friends.containsKey(id)) {
                val nextFriendIds = friends.keys.filter { it != id }
                saveGlobalInternal(nextFriendIds = nextFriendIds)
                // Remove from memory
                friends.remove(id)
                // Optionally clear the friend's storage keys here.
                storage.putString("${friendKey(id)}_a", "")
                storage.putString("${friendKey(id)}_b", "")
            }
        }
    }

    /**
     * Atomically encrypts a message and updates the session state in a single transaction.
     * Prevents TOCTOU races (§5.4) where concurrent sends could re-use sequence numbers.
     */
    suspend fun encryptAndStore(
        friendId: String,
        payload: MessagePlaintext,
    ): EncryptedMessagePayload =
        stateLock.withLock {
            val entry = friends[friendId] ?: throw Exception("Friend not found: $friendId")

            // Atomic guard: the caller checks outbox before acquiring this lock, but that
            // check has a TOCTOU window. Re-checking here under stateLock makes it safe.
            check(entry.outbox == null) {
                "Outbox already pending for ${friendId.take(8)} — refusing to overwrite"
            }

            val (newSession, message) = Session.encryptMessage(entry.session, payload)

            // NONCE SAFETY ASSERTION (§5.4): The sequence number MUST advance.
            // If we are transitioning tokens, the new root key ensures uniqueness even if
            // sendSeq resets; if we are not, sendSeq must be strictly greater.
            val seqAdvanced =
                newSession.sendSeq > entry.session.sendSeq ||
                    !newSession.rootKey.contentEquals(entry.session.rootKey)
            check(seqAdvanced) { "Nonce safety violation: sequence number did not advance" }

            // Determine which token to use for posting
            val tokenToUse = if (newSession.isSendTokenPending) newSession.prevSendToken else newSession.sendToken

            val updatedSession =
                if (newSession.isSendTokenPending && !entry.session.isSendTokenPending) {
                    newSession.copy(sendTokenPendingSinceMs = currentTimeMillis())
                } else {
                    newSession
                }

            val updatedEntry =
                entry.copy(
                    session = updatedSession,
                    outbox = EncryptedOutboxMessage(token = tokenToUse.toHex(), payload = message),
                    lastSentTs = currentTimeSeconds(),
                )
            saveFriendInternal(friendId, updatedEntry)
            message
        }

    suspend fun updateSession(
        id: String,
        newSession: SessionState,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, entry.copy(session = newSession))
        }
    }

    suspend fun updateFriend(
        id: String,
        transform: (FriendEntry) -> FriendEntry,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, transform(entry))
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

            val nextFriendIds = friends.keys.toList() + entry.id
            saveFriendInternal(entry.id, entry)
            saveGlobalInternal(nextFriendIds = nextFriendIds)

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
            saveFriendInternal(id, entry.copy(isConfirmed = true))
        }
    }

    suspend fun setSharingEnabled(
        id: String,
        enabled: Boolean,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, entry.copy(sharingEnabled = enabled))
        }
    }

    /** Roll back a stale pending transition... */
    internal suspend fun abandonPendingTransition(friendId: String) =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock
            if (!entry.session.isSendTokenPending) return@withLock

            val rolledBack =
                entry.session.copy(
                    sendToken = entry.session.prevSendToken,
                    isSendTokenPending = false,
                    sendTokenPendingSinceMs = null,
                    needsRatchet = entry.session.needsRatchet,
                )
            saveFriendInternal(friendId, entry.copy(session = rolledBack, outbox = null))
        }

    // -----------------------------------------------------------------------
    // Batch poll processing
    // -----------------------------------------------------------------------

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

            val updatedSession =
                if (currentSession.isSendTokenPending && !entry.session.isSendTokenPending) {
                    currentSession.copy(sendTokenPendingSinceMs = currentTimeMillis())
                } else {
                    currentSession
                }

            val updatedEntry =
                entry.copy(
                    session = updatedSession,
                    // Bob becomes confirmed once he receives any valid message from Alice
                    isConfirmed = entry.isConfirmed || anySuccess,
                    lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                    lastLat = lastLocation?.lat ?: entry.lastLat,
                    lastLng = lastLocation?.lng ?: entry.lastLng,
                    lastTs = lastLocation?.ts ?: entry.lastTs,
                    lastPollTs = currentTimeSeconds(),
                    lastDecryptFailed = if (encryptedMessages.isNotEmpty()) isFailedBatch else entry.lastDecryptFailed,
                )

            // The recvToken must only change if we had a successful decryption (§7.2).
            check(currentSession.recvToken.contentEquals(entry.session.recvToken) || anySuccess) {
                "recvToken changed without any successful decryption — invariant violated"
            }

            saveFriendInternal(friendId, updatedEntry)

            PollBatchResult(decryptedLocations, anySuccess, silentDrops > 0)
        }

    suspend fun clearOutbox(id: String) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, entry.copy(outbox = null, outbox429Count = 0))
        }
    }

    suspend fun clearOutboxAndUpdateSession(
        id: String,
        newSession: SessionState,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, entry.copy(session = newSession, outbox = null, outbox429Count = 0))
        }
    }

    suspend fun incrementOutbox429Count(id: String): Int =
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock 0
            val newCount = entry.outbox429Count + 1
            val updated = entry.copy(outbox429Count = newCount)
            saveFriendInternal(id, updated)
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
            saveFriendInternal(id, entry.copy(lastLat = lat, lastLng = lng, lastTs = ts))
        }
    }

    suspend fun updateLastSentTs(
        id: String,
        ts: Long,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, entry.copy(lastSentTs = ts))
        }
    }

    suspend fun updateLastPollTs(
        id: String,
        ts: Long,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            saveFriendInternal(id, entry.copy(lastPollTs = ts))
        }
    }

    suspend fun clearSendTokenPending(
        id: String,
        clearingToken: String? = null,
    ): Boolean =
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock false
            if (!entry.session.isSendTokenPending) return@withLock false
            if (clearingToken != null && entry.session.prevSendToken.toHex() != clearingToken) return@withLock false

            val newSession = entry.session.copy(isSendTokenPending = false, sendTokenPendingSinceMs = null)
            saveFriendInternal(id, entry.copy(session = newSession))
            true
        }

    private fun sanitizeName(name: String): String =
        normalizeName(
            name,
        ).take(32).filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    private fun friendKey(id: String) = "e2ee_friend_$id"

    companion object {
        private const val STORAGE_KEY_GLOBAL = "e2ee_global"

        /**
         * The base key for legacy double-buffered storage (e2ee_store_a, e2ee_store_b).
         * Used during migration to the new granular storage format.
         */
        private const val STORAGE_KEY_LEGACY_BASE = "e2ee_store"

        /**
         * The legacy non-buffered storage key. Before double-buffering was introduced,
         * the entire store was saved under this single key.
         */
        private const val STORAGE_KEY_LEGACY_SINGLE = "e2ee_store"

        const val MAX_PENDING_INVITES = 10
    }
}

/** Legacy monolithic store for migration. */
@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvites: List<PendingInvite> = emptyList(),
    val diagnosticLog: List<String> = emptyList(),
    val lastSavedTs: Long = 0L,
)
