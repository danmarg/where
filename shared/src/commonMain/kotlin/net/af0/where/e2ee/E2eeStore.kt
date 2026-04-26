package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
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
    /** Whether the local user is currently sharing their location with this friend. */
    val sharingEnabled: Boolean = true,
    /** Optional outbox for transactional recovery (§5.4). */
    val outbox: EncryptedOutboxMessage? = null,
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FriendEntry) return false
        return name == other.name &&
            session == other.session &&
            isInitiator == other.isInitiator &&
            lastLat == other.lastLat &&
            lastLng == other.lastLng &&
            lastTs == other.lastTs &&
            lastRecvTs == other.lastRecvTs &&
            isConfirmed == other.isConfirmed
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + isInitiator.hashCode()
        result = 31 * result + (lastLat?.hashCode() ?: 0)
        result = 31 * result + (lastLng?.hashCode() ?: 0)
        result = 31 * result + (lastTs?.hashCode() ?: 0)
        result = 31 * result + lastRecvTs.hashCode()
        result = 31 * result + isConfirmed.hashCode()
        result = 31 * result + sharingEnabled.hashCode()
        result = 31 * result + (outbox?.hashCode() ?: 0)
        return result
    }
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
data class PendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
    val createdAt: Long = currentTimeSeconds(),
)

class E2eeStore(
    private val storage: E2eeStorage,
) {
    private var friends = mutableMapOf<String, FriendEntry>()
    private var pendingInvites = mutableListOf<PendingInvite>()

    // Mutex to serialize all access to friends and pendingInvites.
    // This prevents TOCTOU races where a key rotation and outgoing message
    // could concurrently access and update the same session state.
    private val stateLock = Mutex()

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
                            sharingEnabled = s.sharingEnabled,
                            outbox = s.outbox,
                        )
                    entry.id to entry
                }.toMutableMap()

            // Migration: if the old single pendingInvite exists, add it to the list.
            val migratedInvites = serialized.pendingInvites.toMutableList()
            serialized.pendingInvite?.let { legacy ->
                if (migratedInvites.none { it.qrPayload.ekPub.contentEquals(legacy.qrPayload.ekPub) }) {
                    migratedInvites.add(legacy)
                }
            }
            pendingInvites = migratedInvites
        } catch (e: Exception) {
            println("[E2eeStore] Error loading state: ${e.message}")
            e.printStackTrace()
            // Error loading state, possibly corrupted; reset
            friends = mutableMapOf()
            pendingInvites = mutableListOf()
        }
    }

    private fun save() {
        val serialized =
            SerializedStore(
                friends =
                    friends.values.map { f ->
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
                            sharingEnabled = f.sharingEnabled,
                            outbox = f.outbox,
                        )
                    },
                pendingInvites = pendingInvites,
            )
        storage.putString(STORAGE_KEY, json.encodeToString(serialized))
    }

    /** The QR payload currently being displayed, or null if no invite is active. */
    suspend fun pendingQrPayload(): QrPayload? = stateLock.withLock { pendingInvites.lastOrNull()?.qrPayload }

    /**
     * Alice: Create a new invite QR payload and store the ephemeral private key.
     * Appends to the list of active invites.
     */
    suspend fun createInvite(suggestedName: String): QrPayload =
        stateLock.withLock {
            val (payload, ekPriv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            pendingInvites.add(PendingInvite(payload, ekPriv))
            save()
            payload
        }

    /** Alice: Discard all pending invites (e.g. user dismissed the QR screen). */
    suspend fun clearInvites() {
        stateLock.withLock {
            pendingInvites.clear()
            save()
        }
    }

    /** Alice: Discard a specific pending invite. */
    suspend fun clearInvite(ekPub: ByteArray) {
        stateLock.withLock {
            pendingInvites.removeAll { it.qrPayload.ekPub.contentEquals(ekPub) }
            save()
        }
    }

    /** Alice: Discard the legacy single invite (for backward compatibility during transition). */
    suspend fun clearInvite() {
        stateLock.withLock {
            pendingInvites.clear()
            save()
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
            friends[entry.id] = entry
            save()
            val payload =
                KeyExchangeInitPayload(
                    v = initMsg.protocolVersion,
                    token = initMsg.token.toHex(),
                    ekPub = initMsg.ekPub,
                    keyConfirmation = initMsg.keyConfirmation,
                    suggestedName = initMsg.suggestedName,
                    aliceEkPub = qr.ekPub,
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
    ): FriendEntry? =
        stateLock.withLock {
            val pending =
                pendingInvites.find {
                    it.qrPayload.ekPub.contentEquals(payload.aliceEkPub)
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
                friends[entry.id] = entry
                pendingInvites.remove(pending)
                save()
                entry
            } catch (e: AuthenticationException) {
                throw e // key_confirmation or token mismatch failure — surface to caller
            } catch (e: Exception) {
                throw e // surface other unexpected exceptions
            }
        }

    /** Returns all active pending invites. */
    suspend fun listPendingInvites(): List<PendingInvite> = stateLock.withLock { pendingInvites.toList() }

    /**
     * Cleans up expired invites (both link invites and unconfirmed scanned friends).
     * @param expirySeconds The age after which an invite is considered expired (default 48h).
     */
    suspend fun cleanupExpiredInvites(expirySeconds: Long = 48 * 3600L) {
        stateLock.withLock {
            val now = currentTimeSeconds()
            val initialSize = pendingInvites.size
            pendingInvites.removeAll { now - it.createdAt > expirySeconds }

            val unconfirmedBefore = friends.size
            val toRemove =
                friends.values.filter {
                    !it.isConfirmed && (now - it.lastRecvTs > expirySeconds)
                }.map { it.id }
            toRemove.forEach { friends.remove(it) }

            if (pendingInvites.size != initialSize || friends.size != unconfirmedBefore) {
                println("[E2eeStore] Cleaned up ${initialSize - pendingInvites.size} invites and ${unconfirmedBefore - friends.size} unconfirmed friends")
                save()
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
            friends[id] = entry.copy(name = newName)
            save()
        }
    }

    suspend fun deleteFriend(id: String) {
        stateLock.withLock {
            if (friends.remove(id) != null) {
                save()
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

            friends[friendId] =
                entry.copy(
                    session = newSession,
                    outbox = EncryptedOutboxMessage(token = tokenToUse.toHex(), payload = message),
                    lastSentTs = currentTimeSeconds(),
                )
            save()
            message
        }

    suspend fun updateSession(
        id: String,
        newSession: SessionState,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(session = newSession)
            save()
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
            friends[id] =
                entry.copy(
                    session = newSession,
                    outbox = EncryptedOutboxMessage(token = token, payload = message),
                )
            save()
        }
    }

    /** Clears the outbox after successful delivery. */
    suspend fun clearOutbox(id: String) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            if (entry.outbox != null) {
                friends[id] = entry.copy(outbox = null)
                save()
            }
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
            friends[id] = entry.copy(lastLat = lat, lastLng = lng, lastTs = ts)
            save()
        }
    }

    suspend fun updateLastSentTs(
        id: String,
        ts: Long,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = entry.copy(lastSentTs = ts)
            save()
        }
    }

    suspend fun updateFriend(
        id: String,
        transform: (FriendEntry) -> FriendEntry,
    ) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            friends[id] = transform(entry)
            save()
        }
    }

    // -----------------------------------------------------------------------
    // Batch poll processing
    // -----------------------------------------------------------------------

    /**
     * A message the caller should POST back to the server after processing a batch.
     */
    data class OutgoingMessage(val token: String, val payload: MailboxPayload)

    /**
     * Result of [processBatch].
     *
     * @property decryptedLocations Locations decrypted from this batch, in receive order.
     * @property outgoing Payloads the caller must POST to the server, in order.
     */
    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val outgoing: List<OutgoingMessage>,
        val anySuccess: Boolean,
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
            val outgoing = mutableListOf<OutgoingMessage>()

            // With Sealed Envelopes (#186), we must first decrypt headers to sort
            val sortedMessagesWithHeaders =
                messages.filterIsInstance<EncryptedMessagePayload>().mapNotNull { msg ->
                    try {
                        val header =
                            try {
                                Session.decryptHeader(entry.session.headerKey, msg.envelope)
                            } catch (_: Exception) {
                                Session.decryptHeader(entry.session.nextHeaderKey, msg.envelope)
                            }
                        header to msg
                    } catch (_: Exception) {
                        null // Un-decryptable header, likely not for us
                    }
                }.sortedWith { (h1, _), (h2, _) ->
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

            for ((_, msg) in sortedMessagesWithHeaders) {
                try {
                    // Sequential mutation of currentSession provides the replay guarantee:
                    // if multiple messages with the same new DH key arrive in one batch,
                    // the first one processed updates currentSession.recvSeq = seq.
                    // Subequent replays in the same for-loop will be rejected by decryptMessage
                    // because they are checked against the updated recvSeq.
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
                    // Skip individually bad messages to prevent head-of-line blocking
                    failCount++
                }
            }
            val tokenChanged = !currentSession.recvToken.contentEquals(entry.session.recvToken)
            println(
                "[E2eeStore] processBatch: friend=${friendId.take(
                    8,
                )} token=${recvToken.take(
                    8,
                )} total=${sortedMessagesWithHeaders.size} ok=$anySuccess locs=${decryptedLocations.size} fails=$failCount tokenChanged=$tokenChanged",
            )

            // Persistence: we update the store with the latest successfully ratcheted state.
            val hadActivity = decryptedLocations.isNotEmpty() || (anySuccess && currentSession != entry.session)

            val lastLocation = decryptedLocations.lastOrNull()

            friends[friendId] =
                entry.copy(
                    session = currentSession,
                    // Bob becomes confirmed once he receives any valid message from Alice
                    isConfirmed = entry.isConfirmed || anySuccess,
                    lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                    lastLat = lastLocation?.lat ?: entry.lastLat,
                    lastLng = lastLocation?.lng ?: entry.lastLng,
                    lastTs = lastLocation?.ts ?: entry.lastTs,
                )

            // The recvToken must only change if we had a successful decryption (§7.2).
            check(currentSession.recvToken.contentEquals(entry.session.recvToken) || anySuccess) {
                "recvToken changed without any successful decryption — invariant violated"
            }

            save()
            PollBatchResult(decryptedLocations, outgoing, anySuccess)
        }

    private fun sanitizeName(name: String): String {
        val normalized = normalizeName(name)
        return normalized.take(64).filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
    }

    companion object {
        private const val STORAGE_KEY = "e2ee_store"
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
    val sharingEnabled: Boolean = true,
    val outbox: EncryptedOutboxMessage? = null,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
    val pendingInvites: List<PendingInvite> = emptyList(),
)
