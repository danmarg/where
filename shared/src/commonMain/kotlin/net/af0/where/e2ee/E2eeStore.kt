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
 * @param myOpkPrivs      Bob's OPK private keys keyed by OPK ID — kept to process Alice's rotations.
 * @param theirOpkPubs    Alice's cache of Bob's OPK public keys keyed by OPK ID — consumed on rotation.
 * @param nextOpkId       Next OPK ID to use when Bob generates a new batch (Bob-only).
 * @param pendingRotation Alice's pending DH ratchet rotation (Alice only); null if no rotation in flight.
 * @param pendingAck      Bob's cached RatchetAck for lost-ack recovery (Bob only); null once Alice commits.
 */
data class FriendEntry(
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val myOpkPrivs: Map<Int, ByteArray> = emptyMap(),
    val theirOpkPubs: Map<Int, ByteArray> = emptyMap(),
    val nextOpkId: Int = 1,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    val lastAckTs: Long = Long.MAX_VALUE,
    val pendingRotation: PendingRotation? = null,
    val pendingAck: PendingAck? = null,
    /**
     * True if the key exchange handshake is fully confirmed (both sides derived SK).
     * Alice is confirmed as soon as she processes KeyExchangeInit; Bob is confirmed
     * once he receives any valid message (Location, OPK bundle) from Alice.
     */
    val isConfirmed: Boolean = false,
) {
    companion object {
        /** §12: Surface a "no recent location" warning after 2 days of silence. */
        const val STALE_THRESHOLD_SECONDS = 2 * 24 * 3600L

        /** §12: Surface a "no acks received" warning after 7 days of silence. */
        const val ACK_TIMEOUT_SECONDS = 7 * 24 * 3600L
    }

    /**
     * Returns true if Bob's app hasn't polled Alice's rotation in [STALE_THRESHOLD_SECONDS],
     * or if no acks have been received for [ACK_TIMEOUT_SECONDS].
     * This is a heuristic for UI to show a "not seen recently" warning.
     */
    val isStale: Boolean
        get() {
            val now = currentTimeSeconds()
            val rotationStale = pendingRotation?.let { (now - it.createdAt) > STALE_THRESHOLD_SECONDS } ?: false
            val ackStale = isInitiator && lastAckTs != Long.MAX_VALUE && (now - lastAckTs) > ACK_TIMEOUT_SECONDS
            return rotationStale || ackStale
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
            myOpkPrivs.contentEquals(other.myOpkPrivs) &&
            theirOpkPubs.contentEquals(other.theirOpkPubs) &&
            nextOpkId == other.nextOpkId &&
            lastLat == other.lastLat &&
            lastLng == other.lastLng &&
            lastTs == other.lastTs &&
            pendingRotation == other.pendingRotation &&
            pendingAck == other.pendingAck &&
            lastAckTs == other.lastAckTs &&
            isConfirmed == other.isConfirmed
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + isInitiator.hashCode()
        result = 31 * result + nextOpkId
        result = 31 * result + (lastLat?.hashCode() ?: 0)
        result = 31 * result + (lastLng?.hashCode() ?: 0)
        result = 31 * result + (lastTs?.hashCode() ?: 0)
        result = 31 * result + (pendingRotation?.hashCode() ?: 0)
        result = 31 * result + (pendingAck?.hashCode() ?: 0)
        result = 31 * result + lastAckTs.hashCode()
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
                            myOpkPrivs = s.myOpkPrivs.associate { it.id to it.key },
                            theirOpkPubs = s.theirOpkPubs.associate { it.id to it.key },
                            nextOpkId = s.nextOpkId,
                            lastLat = s.lastLat,
                            lastLng = s.lastLng,
                            lastTs = s.lastTs,
                            // 0L = field absent in old serialized data; load() converts 0L → currentTimeSeconds().
                            lastAckTs = if (s.lastAckTs == 0L) currentTimeSeconds() else s.lastAckTs,
                            pendingRotation = s.pendingRotation,
                            pendingAck = s.pendingAck,
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
                            session = f.session.copy(myEkPriv = ByteArray(32)),
                            isInitiator = f.isInitiator,
                            myOpkPrivs = f.myOpkPrivs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            theirOpkPubs = f.theirOpkPubs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            nextOpkId = f.nextOpkId,
                            lastLat = f.lastLat,
                            lastLng = f.lastLng,
                            lastTs = f.lastTs,
                            lastAckTs = f.lastAckTs,
                            pendingRotation = f.pendingRotation,
                            pendingAck = f.pendingAck,
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
                    // Start the ack-timeout clock from pairing time.
                    lastAckTs = currentTimeSeconds(),
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
                        lastAckTs = currentTimeSeconds(),
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

    /**
     * Clear the previous receive token state (used during epoch rotation window).
     * Zeroes out the old chain key before nulling it.
     */
    suspend fun clearPrevRecvState(id: String) {
        stateLock.withLock {
            val entry = friends[id] ?: return@withLock
            val session = entry.session
            if (session.prevRecvToken == null && session.prevRecvChainKey == null) return@withLock

            session.prevRecvChainKey?.fill(0)
            friends[id] =
                entry.copy(
                    session =
                        session.copy(
                            prevRecvToken = null,
                            prevRecvTokenDeadline = 0L,
                            prevRecvChainKey = null,
                            prevRecvSeq = 0L,
                        ),
                )
            save()
        }
    }

    // -----------------------------------------------------------------------
    // OPK management
    // -----------------------------------------------------------------------

    /**
     * Bob: Generate [count] fresh one-time pre-keys, store their private keys, and return
     * a MAC-authenticated [PreKeyBundlePayload] ready to POST to the routing token.
     *
     * Returns null if the friend is not found or this device is not Bob (isInitiator = true).
     */
    suspend fun generateOpkBundle(
        friendId: String,
        count: Int = OPK_BATCH_SIZE,
    ): PreKeyBundlePayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
            // Only Bob (non-initiator) maintains OPK private keys.
            if (entry.isInitiator) return@withLock null

            val startId = entry.nextOpkId
            val newOpks =
                (0 until count).map { i ->
                    val kp = generateX25519KeyPair()
                    OPK(id = startId + i, pub = kp.pub) to kp.priv
                }

            val opkList = newOpks.map { (opk, _) -> opk }
            val mac =
                PreKeyBundleOps.buildMac(
                    // Bob's sendToken == Alice's recvToken (both are T_BA_0), so Alice's
                    // storeOpkBundle can verify this MAC using her recvToken.
                    token = entry.session.sendToken,
                    opks = opkList,
                    kBundle = entry.session.kBundle,
                )

            val newPrivMap = entry.myOpkPrivs + newOpks.associate { (opk, priv) -> opk.id to priv }
            friends[friendId] =
                entry.copy(
                    myOpkPrivs = newPrivMap,
                    nextOpkId = startId + count,
                )
            save()

            PreKeyBundlePayload(
                keys = opkList.map { OPKWire(it.id, it.pub) },
                mac = mac,
            )
        }

    /**
     * Alice: Verify and cache an incoming [PreKeyBundlePayload] from Bob.
     * Returns true if the MAC was valid and the keys were stored.
     */
    suspend fun storeOpkBundle(
        friendId: String,
        bundle: PreKeyBundlePayload,
    ): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            // Only Alice (initiator) caches Bob's OPK public keys.
            if (!entry.isInitiator) return@withLock false

            val opks = bundle.toOPKList()
            if (!PreKeyBundleOps.verify(
                    // Alice's recvToken == Bob's sendToken (both are T_BA_0), matching
                    // the token Bob used in generateOpkBundle.
                    token = entry.session.recvToken,
                    opks = opks,
                    mac = bundle.mac,
                    kBundle = entry.session.kBundle,
                )
            ) {
                return@withLock false
            }

            val newPubMap = entry.theirOpkPubs + opks.associate { it.id to it.pub }
            friends[friendId] = entry.copy(theirOpkPubs = newPubMap)
            save()
            true
        }

    /**
     * Returns true if Bob should replenish his OPK supply for this friend.
     * Bob replenishes when his remaining OPKs fall below [OPK_REPLENISH_THRESHOLD].
     */
    suspend fun shouldReplenishOpks(friendId: String): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            !entry.isInitiator && entry.myOpkPrivs.size < OPK_REPLENISH_THRESHOLD
        }

    // -----------------------------------------------------------------------
    // Ratchet rotation
    // -----------------------------------------------------------------------

    /**
     * Returns true if Alice should initiate a DH ratchet rotation for this friendship.
     *
     * Alice can rotate whenever she has a cached OPK from Bob and no rotation is
     * currently in flight. There is no fixed rotation interval — the goal is to rotate
     * as often as OPKs are available to maximise forward secrecy.
     */
    suspend fun shouldInitiateRotation(friendId: String): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            entry.isInitiator &&
                entry.theirOpkPubs.isNotEmpty() &&
                entry.pendingRotation == null
        }

    /**
     * Alice: Consume one cached OPK, compute the new session state, and store it as a
     * [PendingRotation] alongside the current session.
     *
     * The current session is NOT advanced until Bob acks (see [processBatch]). The
     * returned [EpochRotationPayload] must be included alongside every outgoing location
     * send until the rotation is acked.
     *
     * Returns null if the friend is not found, is not the initiator, already has a
     * pending rotation, or has no cached OPKs.
     */
    suspend fun initiateRotation(friendId: String): EpochRotationPayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
            if (!entry.isInitiator || entry.pendingRotation != null || entry.theirOpkPubs.isEmpty()) return@withLock null

            val (opkId, opkPub) = entry.theirOpkPubs.minBy { it.key }
            val newEk = generateX25519KeyPair()
            val pending =
                Session.aliceEpochRotation(
                    state = entry.session,
                    aliceNewEkPriv = newEk.priv,
                    aliceNewEkPub = newEk.pub,
                    bobOpkPub = opkPub,
                    opkId = opkId,
                    aliceFp = entry.session.aliceFp,
                    bobFp = entry.session.bobFp,
                    createdAt = currentTimeSeconds(),
                )

            friends[friendId] =
                entry.copy(
                    pendingRotation = pending,
                    // Consume the OPK immediately.
                    theirOpkPubs = entry.theirOpkPubs - opkId,
                )
            save()

            EpochRotationPayload(ct = pending.epochRotationCt)
        }

    /**
     * Returns the pre-built [EpochRotationPayload] from the current pending rotation, or
     * null if no rotation is in flight. Alice includes this in every outgoing send until acked.
     */
    suspend fun pendingEpochRotation(friendId: String): EpochRotationPayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
            val pending = entry.pendingRotation ?: return@withLock null
            EpochRotationPayload(ct = pending.epochRotationCt)
        }

    /**
     * Bob: Verify and process an [EpochRotationPayload] from Alice.
     *
     * Immediately switches Bob's recvToken (no dual-polling window). Returns the
     * [RatchetAckPayload] that the caller must POST to the **pre-rotation** sendToken
     * (i.e. Alice's current recvToken) so Alice can receive it before she commits.
     *
     * Returns null if the friend is not found, the OPK is unknown, or a
     * non-security parse error occurs. Throws [IllegalArgumentException] on
     * cryptographic failures (bad AEAD tag).
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun processEpochRotation(
        friendId: String,
        payload: EpochRotationPayload,
    ): RatchetAckPayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
            if (entry.isInitiator) return@withLock null

            val rotPt =
                try {
                    decryptEpochRotationCt(
                        currentRootKey = entry.session.rootKey,
                        ct = payload.ct,
                        aliceFp = entry.session.aliceFp,
                        bobFp = entry.session.bobFp,
                        sendToken = entry.session.recvToken,
                    )
                } catch (_: Exception) {
                    throw IllegalArgumentException("EpochRotation AEAD verification failed")
                }

            val opkPriv = entry.myOpkPrivs[rotPt.opkId] ?: return@withLock null

            val (newState, ackCt) =
                Session.bobProcessAliceRotation(
                    state = entry.session,
                    aliceNewEkPub = rotPt.newEkPub,
                    bobOpkPriv = opkPriv,
                    aliceFp = entry.session.aliceFp,
                    bobFp = entry.session.bobFp,
                )

            friends[friendId] =
                entry.copy(
                    session = newState,
                    myOpkPrivs = entry.myOpkPrivs - rotPt.opkId,
                    isConfirmed = true,
                    // Cache the ack for lost-ack recovery: re-posted on every poll until
                    // Alice's first message on the new recvToken proves she committed.
                    pendingAck =
                        PendingAck(
                            ackCt = ackCt,
                            sendToken = entry.session.sendToken.toHex(), // Use entry.session.sendToken here
                            expectedRecvToken = newState.recvToken.toHex(),
                        ),
                )
            save()

            RatchetAckPayload(ct = ackCt)
        }

    /**
     * Alice: Verify Bob's [RatchetAckPayload] and commit the pending rotation.
     * Returns true if the AEAD tag is valid and the rotation was committed; false otherwise.
     */
    suspend fun processRatchetAck(
        friendId: String,
        payload: RatchetAckPayload,
    ): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            if (!entry.isInitiator) return@withLock false
            val pending = entry.pendingRotation ?: return@withLock false

            val committed =
                try {
                    Session.aliceProcessRatchetAck(
                        pendingRotation = pending,
                        ackCt = payload.ct,
                        bobFp = entry.session.bobFp,
                        aliceFp = entry.session.aliceFp,
                    )
                } catch (_: Exception) {
                    return@withLock false
                }

            friends[friendId] =
                entry.copy(
                    session = committed,
                    pendingRotation = null,
                    lastAckTs = currentTimeSeconds(),
                    isConfirmed = true,
                )
            save()
            true
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
     * **Ordering guarantee:** [EncryptedLocationPayload]s in a batch always belong to
     * the current session. The function decrypts them *before* applying any
     * [EpochRotationPayload] so that the chain key is still on the correct step during
     * decryption.
     *
     * For Bob, after processing an [EpochRotationPayload] the function adds a
     * [RatchetAckPayload] (and a fresh [PreKeyBundlePayload]) to [PollBatchResult.outgoing].
     * Both are addressed to the **pre-rotation** sendToken so Alice receives them before
     * she commits and switches to the new routing token.
     *
     * For Alice, [RatchetAckPayload]s in the batch atomically commit any pending rotation.
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
            friends[friendId] ?: return@withLock null

            val decryptedLocations = mutableListOf<LocationPlaintext>()
            val outgoing = mutableListOf<OutgoingMessage>()

            // Step 1: Cache incoming OPK bundles (Alice stores Bob's prekeys).
            for (msg in messages.filterIsInstance<PreKeyBundlePayload>()) {
                val entry = friends[friendId] ?: continue
                if (!entry.isInitiator) continue
                if (!PreKeyBundleOps.verify(
                        token = entry.session.recvToken,
                        opks = msg.toOPKList(),
                        mac = msg.mac,
                        kBundle = entry.session.kBundle,
                    )
                ) {
                    continue
                }
                val newPubMap = entry.theirOpkPubs + opks.associate { it.id to it.pub }
                friends[friendId] = entry.copy(theirOpkPubs = newPubMap, isConfirmed = true)
            }

            // Step 2: Decrypt location updates BEFORE processing epoch rotation.
            // All EncryptedLocationPayloads on this token are from the same epoch.
            val sessionAtStart = friends[friendId]?.session ?: return@withLock PollBatchResult(emptyList(), emptyList())
            val isPrevToken = recvToken != null && sessionAtStart.prevRecvToken?.toHex() == recvToken

            // Construct a temporary SessionState for decryption if this is the old token.
            var decryptionSession =
                if (isPrevToken && sessionAtStart.prevRecvChainKey != null) {
                    sessionAtStart.copy(
                        epoch = sessionAtStart.epoch - 1,
                        recvChainKey = sessionAtStart.prevRecvChainKey,
                        recvSeq = sessionAtStart.prevRecvSeq,
                    )
                } else {
                    sessionAtStart
                }


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

            val entryAfterDecryption = friends[friendId] ?: return@withLock PollBatchResult(decryptedLocations, emptyList())
            val updatedSession =
                if (isPrevToken) {
                    // If we decrypted messages on the old token, update the prevRecv fields.
                    entryAfterDecryption.session.copy(
                        prevRecvChainKey = decryptionSession.recvChainKey,
                        prevRecvSeq = decryptionSession.recvSeq,
                    )
                } else {
                    // If we decrypted messages on the new token, update the main recv fields.
                    var s =
                        entryAfterDecryption.session.copy(
                            recvChainKey = decryptionSession.recvChainKey,
                            recvSeq = decryptionSession.recvSeq,
                        )
                    // §8.3: Stop polling the old token once we have a valid message on the new one.
                    if (decryptedLocations.isNotEmpty() && s.prevRecvToken != null) {
                        s = s.copy(prevRecvToken = null, prevRecvTokenDeadline = 0L, prevRecvChainKey = null, prevRecvSeq = 0L)
                    }
                    s
                }
            friends[friendId] =
                entryAfterDecryption.copy(
                    session = updatedSession,
                    isConfirmed = if (decryptedLocations.isNotEmpty()) true else entryAfterDecryption.isConfirmed,
                    // If Bob had a pendingAck and Alice just sent us a message on the new
                    // recvToken, she has committed the rotation — ack was received, stop re-posting.
                    pendingAck =
                        if (decryptedLocations.isNotEmpty() && entryAfterDecryption.pendingAck?.expectedRecvToken == recvToken) {
                            null
                        } else {
                            entryAfterDecryption.pendingAck
                        },
                )

            // Step 3: Process EpochRotation (Bob only).
            // Bob immediately switches to the new recvToken (no dual-polling window).
            // RatchetAck + new OPK bundle are addressed to the pre-rotation sendToken
            // so Alice can process them before committing.
            for (msg in messages.filterIsInstance<EpochRotationPayload>()) {
                val entry = friends[friendId] ?: continue
                if (entry.isInitiator) continue

                val oldSendToken = entry.session.sendToken

                val rotPt =
                    try {
                        decryptEpochRotationCt(
                            currentRootKey = entry.session.rootKey,
                            ct = msg.ct,
                            aliceFp = entry.session.aliceFp,
                            bobFp = entry.session.bobFp,
                            sendToken = entry.session.recvToken,
                        )
                    } catch (_: Exception) {
                        continue
                    }

                val opkPriv = entry.myOpkPrivs[rotPt.opkId] ?: continue

                val (newState, ackCt) =
                    Session.bobProcessAliceRotation(
                        state = entry.session,
                        aliceNewEkPub = rotPt.newEkPub,
                        bobOpkPriv = opkPriv,
                        aliceFp = entry.session.aliceFp,
                        bobFp = entry.session.bobFp,
                    )

                friends[friendId] =
                    entry.copy(
                        session = newState,
                        myOpkPrivs = entry.myOpkPrivs - rotPt.opkId,
                        isConfirmed = true,
                        // Cache the ack for lost-ack recovery: re-posted on every poll until
                        // Alice's first message on the new recvToken proves she committed.
                        pendingAck =
                            PendingAck(
                                ackCt = ackCt,
                                sendToken = oldSendToken.toHex(),
                                expectedRecvToken = newState.recvToken.toHex(),
                            ),
                    )

                // Generate and post a fresh OPK bundle on the OLD sendToken as well,
                // MACed with oldSendToken so Alice can verify it before committing.
                //
                // Ordering guarantee: Alice's processBatch processes PreKeyBundlePayloads in
                // Step 1 (before RatchetAck in Step 4), so if both arrive in the same batch,
                // the bundle is verified against Alice's pre-commit recvToken (= T_BA_old). ✓
                //
                // Cross-poll gap: we add the OPK bundle to 'outgoing' BEFORE the RatchetAck.
                // LocationClient posts them in order. This ensures that if Alice polls
                // between POSTs, she either sees both (in a single poll) or only the bundle.
                // If she sees the RatchetAck, she is guaranteed to have the bundle already
                // available on the server (or in the same batch).
                val freshEntry = friends[friendId] ?: continue
                val startId = freshEntry.nextOpkId
                val newOpks =
                    (0 until OPK_BATCH_SIZE).map { i ->
                        val kp = generateX25519KeyPair()
                        OPK(id = startId + i, pub = kp.pub) to kp.priv
                    }
                val opkList = newOpks.map { (opk, _) -> opk }
                val mac =
                    PreKeyBundleOps.buildMac(
                        token = oldSendToken,
                        opks = opkList,
                        kBundle = freshEntry.session.kBundle,
                    )
                val newPrivMap = freshEntry.myOpkPrivs + newOpks.associate { (opk, priv) -> opk.id to priv }
                friends[friendId] =
                    freshEntry.copy(
                        myOpkPrivs = newPrivMap,
                        nextOpkId = startId + OPK_BATCH_SIZE,
                    )
                outgoing.add(
                    OutgoingMessage(
                        oldSendToken.toHex(),
                        PreKeyBundlePayload(
                            keys = opkList.map { OPKWire(it.id, it.pub) },
                            mac = mac,
                        ),
                    ),
                )

                // Post RatchetAck on the OLD sendToken (T_BA_old).
                outgoing.add(OutgoingMessage(oldSendToken.toHex(), RatchetAckPayload(ct = ackCt)))
            }

            // Step 4: Process RatchetAcks — Alice atomically commits her pending rotation.
            for (msg in messages.filterIsInstance<RatchetAckPayload>()) {
                val entry = friends[friendId] ?: continue
                if (!entry.isInitiator) continue
                val pending = entry.pendingRotation ?: continue

                val committed =
                    try {
                        Session.aliceProcessRatchetAck(
                            pendingRotation = pending,
                            ackCt = msg.ct,
                            bobFp = entry.session.bobFp,
                            aliceFp = entry.session.aliceFp,
                        )
                    } catch (_: Exception) {
                        continue
                    }

                friends[friendId] =
                    entry.copy(
                        session = committed,
                        pendingRotation = null,
                        lastAckTs = currentTimeSeconds(),
                        isConfirmed = true,
                    )
            }

            save()
            PollBatchResult(decryptedLocations, outgoing)
        }

    companion object {
        private const val STORAGE_KEY = "e2ee_store"

        /** OPKs generated per batch. */
        const val OPK_BATCH_SIZE = 10

        /**
         * Bob replenishes OPKs when fewer than this many remain.
         * Should be > 0 to allow Alice to rotate while Bob is generating a new batch.
         */
        const val OPK_REPLENISH_THRESHOLD = 3
    }
}

// -----------------------------------------------------------------------
// Serialization helpers
// -----------------------------------------------------------------------

@Serializable
internal data class SerializedOpkEntry(
    val id: Int,
    @Serializable(with = ByteArrayBase64Serializer::class) val key: ByteArray,
)

@Serializable
internal data class SerializedFriendEntry(
    val friendId: String,
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val myOpkPrivs: List<SerializedOpkEntry> = emptyList(),
    val theirOpkPubs: List<SerializedOpkEntry> = emptyList(),
    val nextOpkId: Int = 1,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    // Default 0L = field absent in old data; load() converts 0L → currentTimeSeconds().
    val lastAckTs: Long = 0L,
    val pendingRotation: PendingRotation? = null,
    val pendingAck: PendingAck? = null,
    val isConfirmed: Boolean = false,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
)
