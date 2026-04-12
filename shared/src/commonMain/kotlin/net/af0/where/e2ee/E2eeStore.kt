package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val lastRecvTs: Long = Long.MAX_VALUE,
    /**
     * True if the key exchange handshake is fully confirmed (both sides derived SK).
     * Alice is confirmed as soon as she processes KeyExchangeInit; Bob is confirmed
     * once he receives any valid message from Alice.
     */
    val isConfirmed: Boolean = false,
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
internal data class PendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
)

class E2eeStore(
    private val storage: E2eeStorage,
) {
    private var friends = mutableMapOf<String, FriendEntry>()
    private var pendingInvite: PendingInvite? = null

    // Mutex to serialize all access to friends and pendingInvite.
    // This prevents TOCTOU races where a key rotation and outgoing message
    // could concurrently access and update the same session state.
    private val stateLock = Mutex()

    init {
        load()
    }

    private fun load() {
        val jsonStr = storage.getString(STORAGE_KEY) ?: return
        try {
            val serialized = Json.decodeFromString<SerializedStore>(jsonStr)
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
                        )
                    entry.id to entry
                }.toMutableMap()
            pendingInvite = serialized.pendingInvite
        } catch (_: Exception) {
            // Error loading state, possibly corrupted; reset
            friends = mutableMapOf()
            pendingInvite = null
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
                        )
                    },
                pendingInvite = pendingInvite,
            )
        storage.putString(STORAGE_KEY, Json.encodeToString(serialized))
    }

    /** The QR payload currently being displayed, or null if no invite is active. */
    suspend fun pendingQrPayload(): QrPayload? = stateLock.withLock { pendingInvite?.qrPayload }

    /**
     * Alice: Create a new invite QR payload and store the ephemeral private key.
     * Replaces any previously active invite.
     */
    suspend fun createInvite(suggestedName: String): QrPayload =
        stateLock.withLock {
            val (payload, ekPriv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            pendingInvite = PendingInvite(payload, ekPriv)
            save()
            payload
        }

    /** Alice: Discard the current pending invite (e.g. user dismissed the QR screen). */
    suspend fun clearInvite() {
        stateLock.withLock {
            pendingInvite = null
            save()
        }
    }

    /**
     * Bob: Process Alice's scanned QR code.
     * Performs the single X25519 DH, creates the initial session, and saves the new friend.
     * Returns the wire payload ready to POST to [QrPayload.discoveryToken] and the new entry.
     */
    suspend fun processScannedQr(
        qr: QrPayload,
        bobSuggestedName: String = "",
    ): Pair<KeyExchangeInitPayload, FriendEntry> =
        stateLock.withLock {
            val (initMsg, session) = KeyExchange.bobProcessQr(qr, bobSuggestedName)

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
    ): FriendEntry? =
        stateLock.withLock {
            val pending = pendingInvite ?: return@withLock null
            val msg =
                KeyExchangeInitMessage(
                    token = payload.token.hexToByteArray(),
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
                        name = bobName,
                        session = session,
                        // Alice created the QR
                        isInitiator = true,
                        lastRecvTs = currentTimeSeconds(),
                        isConfirmed = false,
                    )
                friends[entry.id] = entry
                pendingInvite = null
                save()
                entry
            } catch (e: IllegalArgumentException) {
                throw e // key_confirmation failure — surface to caller
            } catch (e: Exception) {
                throw e // XXX
                // null // transient parse/format error — treat as "not ready yet"
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

            val encryptedMessages = messages.filterIsInstance<EncryptedMessagePayload>()
                .sortedWith(
                    compareBy<EncryptedMessagePayload> { if (it.dhPub.contentEquals(entry.session.remoteDhPub)) 0 else 1 }
                        .thenBy { it.seqAsLong() }
                )
            
            var currentSession = entry.session
            var anySuccess = false

            for (msg in encryptedMessages) {
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
                } catch (_: Exception) {
                    // Skip individually bad messages
                }
            }

            // Persistence: we update the store with the latest successfully ratcheted state.
            val hadActivity = decryptedLocations.isNotEmpty() || (anySuccess && currentSession != entry.session)

            friends[friendId] =
                entry.copy(
                    session = currentSession,
                    isConfirmed = true,
                    lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                )

            save()
            PollBatchResult(decryptedLocations, outgoing)
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
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
)
