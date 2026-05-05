package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

private fun Map<Int, ByteArray>.contentEquals(other: Map<Int, ByteArray>): Boolean {
    if (size != other.size) return false
    return all { (k, v) -> other[k]?.contentEquals(v) == true }
}

/**
 * Alice's pending invite state.
 *
 * SECURITY NOTE (§5.5): [aliceEkPriv] MUST be stored with the same protections as
 * the session root key (e.g., kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly on iOS,
 * StrongBox-backed EncryptedSharedPreferences on Android).
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
 * Does not contain private key material.
 */
@Serializable
data class PendingInviteView(
    val qrPayload: QrPayload,
    val createdAt: Long,
    val exportedAt: Long? = null,
)

internal fun PendingInvite.toView() = PendingInviteView(qrPayload, createdAt, exportedAt)

class E2eeStore(
    private val storage: E2eeStorage,
) {
    private var friends = mutableMapOf<String, FriendEntry>()
    private var pendingInvites = mutableListOf<PendingInvite>()

    // Mutex to serialize all access to friends and pendingInvites.
    // This prevents TOCTOU races where a key rotation and outgoing message
    // could concurrently access and update the same session state.
    private val stateLock = Mutex()

    // Diagnostic event log. Capped at MAX_DIAGNOSTIC_EVENTS entries, persisted across restarts.
    // Events are prepended (newest first) and stamped with UTC HH:mm:ss so they remain
    // readable after an app restart.
    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    fun addDiagnosticEvent(message: String) {
        val t = currentTimeSeconds()
        val s = (t % 86400).toInt()
        val entry = "${(s / 3600).toString().padStart(2, '0')}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')} $message"
        _diagnosticLog.update { current ->
            (listOf(entry) + current).take(MAX_DIAGNOSTIC_EVENTS)
        }
    }

    fun diagnosticLogSnapshot(): List<String> = _diagnosticLog.value

    /**
     * Atomically update a friend's metadata in the store.
     */

    init {
        load()
    }

    private fun load() {
        val jsonStr = storage.getString(STORAGE_KEY) ?: return
        try {
            val serialized = json.decodeFromString<SerializedStore>(jsonStr)
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
        } catch (e: Exception) {
            println("[E2eeStore] Error loading state: ${e.message}")
            e.printStackTrace()
            // Error loading state, possibly corrupted; reset
            friends = mutableMapOf()
            pendingInvites = mutableListOf()
        }
    }

    private fun save(
        friendsOverride: Map<String, FriendEntry>? = null,
        pendingInvitesOverride: List<PendingInvite>? = null,
    ) {
        val friendsToSave = friendsOverride ?: friends
        val invitesToSave = pendingInvitesOverride ?: pendingInvites

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
            )
        try {
            storage.putString(STORAGE_KEY, json.encodeToString(serialized))
            // Only update in-memory state if write succeeds
            if (friendsOverride != null) {
                friends = friendsOverride.toMutableMap()
            }
            if (pendingInvitesOverride != null) {
                pendingInvites = pendingInvitesOverride.toMutableList()
            }
        } catch (e: Exception) {
            addDiagnosticEvent("STORAGE WRITE FAILED: ${e.message}")
            throw e
        }
    }

    /**
     * Alice: Create a new invite QR payload and store the ephemeral private key.
     * Appends to the list of active invites.
     */
    suspend fun createInvite(suggestedName: String): QrPayload =
        stateLock.withLock {
            if (pendingInvites.size >= MAX_PENDING_INVITES) {
                throw IllegalStateException("Too many pending invites. Please cancel some before creating a new one.")
            }
            val (payload, ekPriv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            val newInvites = pendingInvites + PendingInvite(payload, ekPriv)
            save(pendingInvitesOverride = newInvites)
            payload
        }

    /** Alice: Discard all pending invites (e.g. user resets the app state). */
    suspend fun clearAllInvites() {
        stateLock.withLock {
            save(pendingInvitesOverride = emptyList())
        }
    }

    /** Alice: Discard a specific pending invite. */
    suspend fun clearInvite(ekPub: ByteArray) {
        stateLock.withLock {
            val newInvites = pendingInvites.filterNot { it.qrPayload.ekPub.contentEquals(ekPub) }
            save(pendingInvitesOverride = newInvites)
        }
    }

    /**
     * Alice: Mark a specific pending invite as exported (link shared/copied).
     * This makes it persistent for 48h.
     */
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

    /** Bob: Process Alice's scanned QR code.
     * Performs the single X25519 DH, creates the initial session, and saves the new friend.
     * Returns the wire payload ready to POST to [QrPayload.discoveryToken] and the new entry.
     */
    suspend fun processScannedQr(
        qr: QrPayload,
        bobSuggestedName: String = "",
    ): Pair<KeyExchangeInitPayload, FriendEntry> =
        stateLock.withLock {
            // SELF-PAIRING GUARD: reject if the scanned QR was generated by this device.
            // pendingInvites holds the ekPub of *our* current invites; if any matches the QR,
            // the user scanned their own code.
            if (pendingInvites.any { it.qrPayload.ekPub.contentEquals(qr.ekPub) }) {
                throw IllegalArgumentException("Cannot pair with yourself")
            }
            val sanitizedRequestedName = sanitizeName(bobSuggestedName)
            val (initMsg, session) = KeyExchange.bobProcessQr(qr, sanitizedRequestedName)

            val entry =
                FriendEntry(
                    name = qr.suggestedName,
                    session = session,
                    // Bob scanned Alice's QR
                    isInitiator = false,
                    lastRecvTs = currentTimeSeconds(),
                    isConfirmed = false,
                )
            val newFriends = friends + (entry.id to entry)
            save(friendsOverride = newFriends)
            val payload =
                KeyExchangeInitPayload(
                    v = initMsg.protocolVersion,
                    token = initMsg.token.toHex(),
                    ekPub = initMsg.ekPub,
                    keyConfirmation = initMsg.keyConfirmation,
                    suggestedName = initMsg.suggestedName,
                )
            payload to entry
        }

    /**
     * Alice: Process Bob's KeyExchangeInit payload received from the discovery inbox.
     * Verifies key_confirmation, recomputes the session, and saves the new friend.
     *
     * @param payload Wire payload received from GET /inbox/{discoveryToken}.
     * @param bobName The name Alice wants to call this friend.
     * @return The new [FriendEntry], or null if no invite is active or the payload is
     *         malformed in a non-security-relevant way.
     * @throws IllegalArgumentException if key_confirmation fails — this is a
     *         crypto failure that callers should surface (not silently discard).
     */
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
                        // Alice created the QR
                        isInitiator = true,
                        lastRecvTs = currentTimeSeconds(),
                        // Alice is confirmed as soon as she processes Bob's KeyExchangeInit
                        isConfirmed = true,
                    )

                val newFriends = friends + (entry.id to entry)
                val newInvites = pendingInvites.filter { it != pending }
                save(friendsOverride = newFriends, pendingInvitesOverride = newInvites)
                entry
            } catch (e: AuthenticationException) {
                throw e // key_confirmation or token mismatch failure — surface to caller
            } catch (e: Exception) {
                throw e // surface other unexpected exceptions
            }
        }

    /** Returns all active pending invites. */
    suspend fun listPendingInvites(): List<PendingInviteView> = stateLock.withLock { pendingInvites.map { it.toView() } }

    /**
     * Cleans up expired invites (both link invites and unconfirmed scanned friends).
     * @param expirySeconds The age after which an invite is considered expired (default 48h).
     */
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

                println(
                    "[E2eeStore] Cleaned up ${pendingInvites.size - newInvites.size} invites and ${friends.size - newFriends.size} unconfirmed friends",
                )
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
            val newFriends = friends + (id to entry.copy(name = newName))
            save(friendsOverride = newFriends)
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

            val newFriends =
                friends +
                    (
                        friendId to
                            entry.copy(
                                session = newSession,
                                outbox = EncryptedOutboxMessage(token = tokenToUse.toHex(), payload = message),
                                lastSentTs = currentTimeSeconds(),
                            )
                    )
            save(friendsOverride = newFriends)
            message
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

    /**
     * Atomically updates a friend's session and populates their outbox for transactional retry.
     */
    suspend fun updateSessionWithOutbox(
        id: String,
        newSession: SessionState,
        message: MailboxPayload,
        token: String,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends =
                friends +
                    (
                        id to
                            entry.copy(
                                session = newSession,
                                outbox = EncryptedOutboxMessage(token = token, payload = message),
                            )
                    )
            save(friendsOverride = newFriends)
        }
    }

    /** Clears the outbox after successful delivery or abandonment. */
    suspend fun clearOutbox(id: String) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            if (entry.outbox != null || entry.outbox429Count > 0) {
                val newFriends = friends + (id to entry.copy(outbox = null, outbox429Count = 0))
                save(friendsOverride = newFriends)
            }
        }
    }

    /** Increments the consecutive 429 error count for the current outbox. */
    suspend fun incrementOutbox429Count(id: String): Int =
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock 0
            val newCount = entry.outbox429Count + 1
            val newFriends = friends + (id to entry.copy(outbox429Count = newCount))
            save(friendsOverride = newFriends)
            newCount
        }

    /** Atomically clears the outbox AND updates the session in a single save (H10). */
    suspend fun clearOutboxAndUpdateSession(
        id: String,
        newSession: SessionState,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends = friends + (id to entry.copy(session = newSession, outbox = null, outbox429Count = 0))
            save(friendsOverride = newFriends)
        }
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

    suspend fun updateLastSentTs(
        id: String,
        ts: Long,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends = friends + (id to entry.copy(lastSentTs = ts))
            save(friendsOverride = newFriends)
        }
    }

    suspend fun updateLastPollTs(
        id: String,
        ts: Long,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val newFriends = friends + (id to entry.copy(lastPollTs = ts))
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

    // -----------------------------------------------------------------------
    // Batch poll processing
    // -----------------------------------------------------------------------

    /**
     * Result of [processBatch].
     *
     * @property decryptedLocations Locations decrypted from this batch, in receive order.
     */
    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val anySuccess: Boolean,
        val hadSilentDrops: Boolean,
    )

    /**
     * Process one batch of mailbox messages for [friendId] in the correct order.
     *
     * @param friendId   ID of the friend whose messages are being processed.
     * @param recvToken  The token used to fetch this batch.
     * @param messages   Batch of payloads from the mailbox.
     * @return [PollBatchResult], or null if [friendId] is not found.
     */
    suspend fun processBatch(
        friendId: String,
        recvToken: String,
        messages: List<MailboxPayload>,
    ): PollBatchResult? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null

            val decryptedLocations = mutableListOf<LocationPlaintext>()

            // With Sealed Envelopes (#186), we must first decrypt headers to sort
            val encryptedMessages = messages.filterIsInstance<EncryptedMessagePayload>()
            val sortedMessagesWithHeaders =
                encryptedMessages.mapNotNull { msg ->
                    try {
                        val header =
                            try {
                                Session.decryptHeader(entry.session.headerKey, msg.envelope)
                            } catch (_: Exception) {
                                try {
                                    Session.decryptHeader(entry.session.nextHeaderKey, msg.envelope)
                                } catch (_: Exception) {
                                    // Last resort: try skipped epoch header keys (#212-followup)
                                    var found: Session.DecryptedHeader? = null
                                    for ((_, hk) in entry.session.skippedEpochHeaderKeys) {
                                        try {
                                            found = Session.decryptHeader(hk, msg.envelope)
                                            break
                                        } catch (_: Exception) {}
                                    }
                                    found ?: throw Exception("All header keys failed")
                                }
                            }
                        header to msg
                    } catch (_: Exception) {
                        null // Un-decryptable header — could be a ratchet message; do not ACK
                    }
                }
            val orderedMessages = sortedMessagesWithHeaders.sortedWith { (h1, _), (h2, _) ->
                    val b1 =
                        when {
                            h1.dhPub.contentEquals(entry.session.remoteDhPub) -> 0
                            h1.dhPub.contentEquals(entry.session.lastRemoteDhPub) -> 1
                            else -> 2
                        }
                    val b2 =
                        when {
                            h2.dhPub.contentEquals(entry.session.remoteDhPub) -> 0
                            h2.dhPub.contentEquals(entry.session.lastRemoteDhPub) -> 1
                            else -> 2
                        }
                    if (b1 != b2) {
                        b1.compareTo(b2)
                    } else {
                        h1.seq.compareTo(h2.seq)
                    }
                }

            var currentSession = entry.session
            var anySuccess = false
            var failCount = 0

            for ((_, msg) in orderedMessages) {
                try {
                    val prevRemoteDh = currentSession.remoteDhPub
                    val (newSession, pt) = Session.decryptMessage(currentSession, msg)

                    if (!newSession.remoteDhPub.contentEquals(prevRemoteDh)) {
                        println(
                            "[E2eeStore] processBatch: friend=${friendId.take(8)} ratcheted DH " +
                                "(${prevRemoteDh.toHex().take(8)} -> ${newSession.remoteDhPub.toHex().take(8)})",
                        )
                    }

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
                    // Skip individually bad messages to prevent head-of-line blocking
                    failCount++
                }
            }
            if (!anySuccess && failCount > 0) {
                addDiagnosticEvent("DECRYPT FAIL: $failCount msgs all failed for ${friendId.take(8)}")
            }

            val tokenChanged = !currentSession.recvToken.contentEquals(entry.session.recvToken)
            if (tokenChanged) {
                println(
                    "[E2eeStore] processBatch: friend=${friendId.take(8)} recvToken rotated " +
                        "(${entry.session.recvToken.toHex().take(8)} -> ${currentSession.recvToken.toHex().take(8)})",
                )
            }
            println(
                "[E2eeStore] processBatch: friend=${friendId.take(8)} token=${recvToken.take(8)} " +
                    "total=${orderedMessages.size} ok=$anySuccess locs=${decryptedLocations.size} " +
                    "fails=$failCount dropped=${encryptedMessages.size - orderedMessages.size}" +
                    (if (tokenChanged) " rotated=true" else ""),
            )

            val silentDrops = encryptedMessages.size - orderedMessages.size

            // Persistence: we update the store with the latest successfully ratcheted state.
            val hadActivity = decryptedLocations.isNotEmpty() || (anySuccess && currentSession != entry.session)

            val lastLocation = decryptedLocations.lastOrNull()

            val totalProcessed = orderedMessages.size
            val isFailedBatch = (totalProcessed > 0 && !anySuccess && failCount > 0) || (silentDrops > 0 && !anySuccess)

            val newFriends =
                friends +
                    (
                        friendId to
                            entry.copy(
                                session = currentSession,
                                // Bob becomes confirmed once he receives any valid message from Alice
                                isConfirmed = entry.isConfirmed || anySuccess,
                                lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                                lastLat = lastLocation?.lat ?: entry.lastLat,
                                lastLng = lastLocation?.lng ?: entry.lastLng,
                                lastTs = lastLocation?.ts ?: entry.lastTs,
                                lastDecryptFailed = if (encryptedMessages.isNotEmpty()) isFailedBatch else entry.lastDecryptFailed,
                            )
                    )

            // The recvToken must only change if we had a successful decryption (§7.2).
            check(currentSession.recvToken.contentEquals(entry.session.recvToken) || anySuccess) {
                "recvToken changed without any successful decryption — invariant violated"
            }

            save(friendsOverride = newFriends)
            PollBatchResult(decryptedLocations, anySuccess, silentDrops > 0)
        }

    private fun sanitizeName(name: String): String {
        val normalized = normalizeName(name)
        return normalized.take(64).filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
    }

    companion object {
        private const val STORAGE_KEY = "e2ee_store"
        const val MAX_PENDING_INVITES = 10
    }
}

// -----------------------------------------------------------------------
// Serialization helpers
// -----------------------------------------------------------------------

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
)
