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
 * @param isInitiator  true if the local user was "Alice" (created the QR); false if "Bob" (scanned QR).
 */
data class FriendEntry(
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
) {
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
            lastTs == other.lastTs
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + isInitiator.hashCode()
        result = 31 * result + (lastLat?.hashCode() ?: 0)
        result = 31 * result + (lastLng?.hashCode() ?: 0)
        result = 31 * result + (lastTs?.hashCode() ?: 0)
        return result
    }
}

/**
 * Alice's pending invite state.
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
                            session = f.session.copy(myEkPriv = ByteArray(32)),
                            isInitiator = f.isInitiator,
                            lastLat = f.lastLat,
                            lastLng = f.lastLng,
                            lastTs = f.lastTs,
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
                    isInitiator = false,
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
                        isInitiator = true,
                    )
                friends[entry.id] = entry
                pendingInvite = null
                save()
                entry
            } catch (e: IllegalArgumentException) {
                throw e // key_confirmation failure — surface to caller
            } catch (e: Exception) {
                throw e
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
     * Result of [processBatch].
     *
     * @property decryptedLocations Locations decrypted from this batch, in receive order.
     */
    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
    )

    /**
     * Process one batch of mailbox messages for [friendId].
     *
     * Decrypts all [EncryptedLocationPayload] messages in seq order. Each
     * successfully decrypted message advances the session's [SessionState.recvToken]
     * to the next-token embedded in its ciphertext, structurally enforcing the
     * single-token invariant (§7.4.1, §8.3).
     *
     * @param friendId  ID of the friend whose messages are being processed.
     * @param messages  Batch of payloads from the mailbox.
     * @return [PollBatchResult], or null if [friendId] is not found.
     */
    suspend fun processBatch(
        friendId: String,
        messages: List<MailboxPayload>,
    ): PollBatchResult? =
        stateLock.withLock {
            friends[friendId] ?: return@withLock null

            val decryptedLocations = mutableListOf<LocationPlaintext>()

            val sessionAtStart = friends[friendId]?.session ?: return@withLock PollBatchResult(emptyList())
            var decryptionSession = sessionAtStart

            val sortedLocations = messages.filterIsInstance<EncryptedLocationPayload>().sortedBy { it.seqAsLong() }
            for (msg in sortedLocations) {
                try {
                    val (newSession, loc) =
                        Session.decryptLocation(
                            state = decryptionSession,
                            ct = msg.ct,
                            seq = msg.seqAsLong(),
                            senderFp = decryptionSession.aliceFp,
                            recipientFp = decryptionSession.bobFp,
                        )
                    decryptionSession = newSession
                    decryptedLocations.add(loc)
                } catch (_: Exception) {
                    // drop individually bad messages rather than aborting the whole batch
                }
            }

            // Persist the updated session (recvToken may have advanced).
            val entryAfterDecryption = friends[friendId] ?: return@withLock PollBatchResult(decryptedLocations)
            friends[friendId] = entryAfterDecryption.copy(
                session = entryAfterDecryption.session.copy(
                    recvChainKey = decryptionSession.recvChainKey,
                    recvSeq = decryptionSession.recvSeq,
                    recvToken = decryptionSession.recvToken,
                ),
            )

            save()
            PollBatchResult(decryptedLocations)
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
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
)
