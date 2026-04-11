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
 * @param myOpkPrivs   Bob's OPK private keys keyed by OPK ID — kept to process Alice's epoch rotations.
 * @param theirOpkPubs Alice's cache of Bob's OPK public keys keyed by OPK ID — consumed on rotation.
 * @param nextOpkId    Next OPK ID to use when Bob generates a new batch (Bob-only).
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
    /**
     * Epoch seconds of the last verified RatchetAck received from Bob (Alice only).
     * Initialized to the pairing timestamp so the 7-day grace period starts from pairing.
     * Long.MAX_VALUE is the unset sentinel (used for backward-compat on upgrade from old data).
     */
    val lastAckTs: Long = Long.MAX_VALUE,
    /**
     * True if the key exchange handshake is fully confirmed (both sides derived SK).
     * Alice is confirmed as soon as she processes KeyExchangeInit; Bob is confirmed
     * once he receives any valid message (Location, OPK bundle) from Alice.
     */
    val isConfirmed: Boolean = false,
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
            myOpkPrivs.contentEquals(other.myOpkPrivs) &&
            theirOpkPubs.contentEquals(other.theirOpkPubs) &&
            nextOpkId == other.nextOpkId &&
            lastLat == other.lastLat &&
            lastLng == other.lastLng &&
            lastTs == other.lastTs &&
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
                            // 0L = field absent in old serialized data; give a fresh grace period.
                            lastAckTs = if (s.lastAckTs == 0L) currentTimeSeconds() else s.lastAckTs,
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
                        // Start the ack-timeout clock from pairing time.
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
    // Epoch rotation
    // -----------------------------------------------------------------------

    /**
     * Returns true if Alice should initiate an epoch rotation for this friendship.
     *
     * Alice rotates every [EPOCH_ROTATION_INTERVAL] sends, as long as she has at
     * least one cached OPK from Bob.
     */
    suspend fun shouldRotateEpoch(friendId: String): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            entry.isInitiator &&
                entry.theirOpkPubs.isNotEmpty() &&
                entry.session.sendSeq > 0 &&
                entry.session.sendSeq % EPOCH_ROTATION_INTERVAL == 0L
        }

    /**
     * Alice: Consume one cached OPK, perform a DH epoch rotation, and return the
     * [EpochRotationPayload] that should be posted to the **current** (pre-rotation)
     * routing token so Bob can process it.
     *
     * The session stored in [FriendEntry] is updated to the new epoch immediately.
     * The caller MUST post the returned payload to the OLD routing token BEFORE
     * sending any location messages with the new session.
     *
     * Returns null if the friend is not found, is not the initiator, or has no cached OPKs.
     */
    suspend fun initiateEpochRotation(friendId: String): EpochRotationPayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
            if (!entry.isInitiator || entry.theirOpkPubs.isEmpty()) return@withLock null

            val ts = currentTimeSeconds()
            val (opkId, opkPub) = entry.theirOpkPubs.minBy { it.key }

            val newEk = generateX25519KeyPair()
            val newSession =
                Session.aliceEpochRotation(
                    state = entry.session,
                    aliceNewEkPriv = newEk.priv,
                    aliceNewEkPub = newEk.pub,
                    bobOpkPub = opkPub,
                    senderFp = entry.session.aliceFp,
                    recipientFp = entry.session.bobFp,
                )

            val oldSendToken = entry.session.sendToken
            val nonce = intToBeBytes(newSession.epoch) + ByteArray(8)
            val ct =
                buildEpochRotationCt(
                    rootKey = entry.session.rootKey,
                    epoch = newSession.epoch,
                    opkId = opkId,
                    newEkPub = newEk.pub,
                    ts = ts,
                    nonce = nonce,
                    routingToken = oldSendToken,
                    senderFp = entry.session.aliceFp,
                    recipientFp = entry.session.bobFp,
                )

            friends[friendId] =
                entry.copy(
                    session = newSession,
                    // consume the OPK
                    theirOpkPubs = entry.theirOpkPubs - opkId,
                )
            save()

            EpochRotationPayload(
                epoch = newSession.epoch,
                opkId = opkId,
                newEkPub = newEk.pub,
                ts = ts,
                nonce = nonce,
                ct = ct,
            )
        }

    /**
     * Bob: Verify and process an [EpochRotationPayload] from Alice.
     *
     * Updates the session to the new epoch and routing token. The caller should:
     *   1. POST the returned [RatchetAckPayload] to the **new** routing token.
     *   2. Call [generateOpkBundle] and POST the result to the new routing token.
     *
     * Returns null if the friend is not found, the OPK is unknown, or another
     * non-security parse error occurs. Throws [IllegalArgumentException] on
     * cryptographic failures (bad AEAD, stale timestamp).
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun processEpochRotation(
        friendId: String,
        payload: EpochRotationPayload,
    ): RatchetAckPayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
            if (entry.isInitiator) return@withLock null // Alice doesn't process her own rotations

            val oldRecvToken = entry.session.recvToken
            val rotData =
                try {
                    decryptEpochRotationCt(
                        rootKey = entry.session.rootKey,
                        epoch = payload.epoch,
                        nonce = payload.nonce,
                        ct = payload.ct,
                        routingToken = oldRecvToken,
                        senderFp = entry.session.aliceFp,
                        recipientFp = entry.session.bobFp,
                    )
                } catch (_: Exception) {
                    throw IllegalArgumentException("EpochRotation AEAD verification failed")
                }
            require(rotData.epoch == payload.epoch) { "epoch mismatch in EpochRotation" }
            require(rotData.opkId == payload.opkId) { "opkId mismatch in EpochRotation" }
            require(isTimestampFresh(rotData.ts)) { "EpochRotation timestamp outside freshness window" }

            val opkPriv = entry.myOpkPrivs[payload.opkId] ?: return@withLock null // unknown OPK — skip

            // Step 1: Process Alice's rotation
            val intermediateSession =
                Session.bobProcessAliceRotation(
                    state = entry.session,
                    aliceNewEkPub = payload.newEkPub,
                    bobOpkPriv = opkPriv,
                    newEpoch = payload.epoch,
                    senderFp = entry.session.aliceFp,
                    recipientFp = entry.session.bobFp,
                    currentTime = currentTimeSeconds(),
                    timeout = PREV_TOKEN_TIMEOUT_SECONDS,
                )

            // Step 2: Refresh Bob's own send chain
            val bobNewEk = generateX25519KeyPair()
            val finalSession =
                Session.bobProcessOwnRotation(
                    state = intermediateSession,
                    bobNewEkPriv = bobNewEk.priv,
                    bobNewEkPub = bobNewEk.pub,
                )

            // Authenticate Ack using intermediate root key (the one Alice knows)
            // and include Bob's new key in authenticated plaintext.
            val ts = currentTimeSeconds()
            val nonce = intToBeBytes(payload.epoch) + ByteArray(8)
            val ackCt =
                buildRatchetAckCt(
                    rootKey = intermediateSession.rootKey,
                    epochSeen = payload.epoch,
                    ts = ts,
                    newEkPub = bobNewEk.pub,
                    nonce = nonce,
                    routingToken = finalSession.sendToken,
                    senderFp = entry.session.aliceFp,
                    recipientFp = entry.session.bobFp,
                )

            friends[friendId] =
                entry.copy(
                    session = finalSession,
                    // delete consumed OPK
                    myOpkPrivs = entry.myOpkPrivs - payload.opkId,
                )
            save()

            RatchetAckPayload(epochSeen = payload.epoch, ts = ts, newEkPub = bobNewEk.pub, nonce = nonce, ct = ackCt)
        }

    /**
     * Alice: Verify Bob's [RatchetAckPayload].
     * Returns true if the AEAD and timestamp are valid; false otherwise.
     * Updates Alice's receive chain if Bob provided a new ephemeral key.
     */
    suspend fun processRatchetAck(
        friendId: String,
        payload: RatchetAckPayload,
    ): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            if (!entry.isInitiator) return@withLock false

            val ackData =
                try {
                    decryptRatchetAckCt(
                        rootKey = entry.session.rootKey,
                        epochSeen = payload.epochSeen,
                        nonce = payload.nonce,
                        ct = payload.ct,
                        routingToken = entry.session.recvToken,
                        senderFp = entry.session.aliceFp,
                        recipientFp = entry.session.bobFp,
                    )
                } catch (_: Exception) {
                    return@withLock false
                }

            if (!isTimestampFresh(ackData.ts)) return@withLock false

            // Perform the second half of the DH ratchet if Bob provided a new key.
            if (ackData.newEkPub != null) {
                val newSession = Session.aliceProcessRatchetAck(entry.session, ackData.newEkPub)
                friends[friendId] = entry.copy(session = newSession)
                save()
            }

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
     * @property newToken Non-null when an [EpochRotationPayload] was processed. The caller
     *   should immediately poll this token and call [processBatch] again on the result, so
     *   that messages the sender already posted to the new epoch are not delayed by a full
     *   poll interval.
     * @property outgoing Payloads the caller must POST to the server, in order.
     */
    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val newToken: String?,
        val outgoing: List<OutgoingMessage>,
    )

    /**
     * Process one batch of mailbox messages for [friendId] in the correct order.
     *
     * **Ordering guarantee:** [EncryptedLocationPayload]s on a given token always belong
     * to the current epoch. The function decrypts them *before* applying any
     * [EpochRotationPayload] so that the session state is still on the correct epoch during
     * decryption. Processing the rotation first would advance the chain key and break
     * decryption of messages that arrived in the same batch.
     *
     * Typical client loop:
     * ```
     * var messages = mailboxClient.poll(token)
     * while (true) {
     *     val result = store.processBatch(friendId, messages) ?: break
     *     result.decryptedLocations.forEach { /* update UI */ }
     *     result.outgoing.forEach { mailboxClient.post(it.token, it.payload) }
     *     val next = result.newToken ?: break
     *     messages = mailboxClient.poll(next)
     * }
     * ```
     *
     * @param friendId   ID of the friend whose messages are being processed.
     * @param messages   Batch of payloads from the mailbox.
     * @param tokenUsed  The hex-encoded token from which these messages were polled.
     *                   If this matches the session's prevRecvToken, the old chain state
     *                   is used for decryption.
     * @return [PollBatchResult], or null if [friendId] is not found.
     */
    suspend fun processBatch(
        friendId: String,
        messages: List<MailboxPayload>,
        tokenUsed: String? = null,
    ): PollBatchResult? =
        stateLock.withLock {
            friends[friendId] ?: return@withLock null

            val decryptedLocations = mutableListOf<LocationPlaintext>()
            val outgoing = mutableListOf<OutgoingMessage>()
            var newToken: String? = null

            // Step 1: Cache incoming OPK bundles (Alice stores Bob's prekeys).
            for (msg in messages.filterIsInstance<PreKeyBundlePayload>()) {
                val opks = msg.toOPKList()
                val entry = friends[friendId] ?: continue
                if (!entry.isInitiator) continue
                if (!PreKeyBundleOps.verify(
                        token = entry.session.recvToken,
                        opks = opks,
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
            val sessionAtStart = friends[friendId]?.session ?: return@withLock PollBatchResult(emptyList(), null, emptyList())
            val isPrevToken = tokenUsed != null && sessionAtStart.prevRecvToken?.toHex() == tokenUsed

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

            // Save the updated receive state back to the correct fields.
            val entryAfterDecryption = friends[friendId] ?: return@withLock PollBatchResult(decryptedLocations, null, emptyList())
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
                )

            // Step 3: Process epoch rotation (after location decryption).
            for (msg in messages.filterIsInstance<EpochRotationPayload>()) {
                val entry = friends[friendId] ?: continue
                if (entry.isInitiator) continue // Alice doesn't process her own rotations

                val oldRecvToken = entry.session.recvToken
                val rotData =
                    try {
                        decryptEpochRotationCt(
                            rootKey = entry.session.rootKey,
                            epoch = msg.epoch,
                            nonce = msg.nonce,
                            ct = msg.ct,
                            routingToken = oldRecvToken,
                            senderFp = entry.session.aliceFp,
                            recipientFp = entry.session.bobFp,
                        )
                    } catch (_: Exception) {
                        continue
                    }
                if (rotData.epoch != msg.epoch || rotData.opkId != msg.opkId || !isTimestampFresh(rotData.ts)) continue

                val opkPriv = entry.myOpkPrivs[msg.opkId] ?: continue

                // Step 3a: Process Alice's rotation
                val intermediateSession =
                    Session.bobProcessAliceRotation(
                        state = entry.session,
                        aliceNewEkPub = msg.newEkPub,
                        bobOpkPriv = opkPriv,
                        newEpoch = msg.epoch,
                        senderFp = entry.session.aliceFp,
                        recipientFp = entry.session.bobFp,
                        currentTime = currentTimeSeconds(),
                        timeout = PREV_TOKEN_TIMEOUT_SECONDS,
                    )

                // Step 3b: Refresh Bob's own send chain
                val bobNewEk = generateX25519KeyPair()
                val finalSession =
                    Session.bobProcessOwnRotation(
                        state = intermediateSession,
                        bobNewEkPriv = bobNewEk.priv,
                        bobNewEkPub = bobNewEk.pub,
                    )

                // Authenticate Ack using intermediate root key (the one Alice knows)
                // and include Bob's new key in authenticated plaintext.
                val ts = currentTimeSeconds()
                val nonce = intToBeBytes(msg.epoch) + ByteArray(8)
                val ackCt =
                    buildRatchetAckCt(
                        rootKey = intermediateSession.rootKey,
                        epochSeen = msg.epoch,
                        ts = ts,
                        newEkPub = bobNewEk.pub,
                        nonce = nonce,
                        routingToken = finalSession.sendToken,
                        senderFp = entry.session.aliceFp,
                        recipientFp = entry.session.bobFp,
                    )

                friends[friendId] =
                    entry.copy(
                        session = finalSession,
                        // delete consumed OPK
                        myOpkPrivs = entry.myOpkPrivs - msg.opkId,
                        isConfirmed = true,
                    )
                val rotatedToken = finalSession.sendToken.toHex()
                outgoing.add(
                    OutgoingMessage(
                        rotatedToken,
                        RatchetAckPayload(epochSeen = msg.epoch, ts = ts, newEkPub = bobNewEk.pub, nonce = nonce, ct = ackCt),
                    ),
                )

                // Generate new OPK bundle for this friend
                val freshEntry = friends[friendId] ?: continue
                val startId = freshEntry.nextOpkId
                val count = OPK_BATCH_SIZE
                val newOpks =
                    (0 until count).map { i ->
                        val kp = generateX25519KeyPair()
                        OPK(id = startId + i, pub = kp.pub) to kp.priv
                    }
                val opkList = newOpks.map { (opk, _) -> opk }
                val mac =
                    PreKeyBundleOps.buildMac(
                        token = freshEntry.session.sendToken,
                        opks = opkList,
                        kBundle = freshEntry.session.kBundle,
                    )
                val newPrivMap = freshEntry.myOpkPrivs + newOpks.associate { (opk, priv) -> opk.id to priv }
                friends[friendId] =
                    freshEntry.copy(
                        myOpkPrivs = newPrivMap,
                        nextOpkId = startId + count,
                    )
                outgoing.add(
                    OutgoingMessage(
                        rotatedToken,
                        PreKeyBundlePayload(
                            keys = opkList.map { OPKWire(it.id, it.pub) },
                            mac = mac,
                        ),
                    ),
                )
                newToken = rotatedToken
            }

            // Step 4: Process RatchetAcks (Alice updating her receive chain).
            for (msg in messages.filterIsInstance<RatchetAckPayload>()) {
                val entry = friends[friendId] ?: continue
                if (!entry.isInitiator) continue

                val ackData =
                    try {
                        decryptRatchetAckCt(
                            rootKey = entry.session.rootKey,
                            epochSeen = msg.epochSeen,
                            nonce = msg.nonce,
                            ct = msg.ct,
                            routingToken = entry.session.recvToken,
                            senderFp = entry.session.aliceFp,
                            recipientFp = entry.session.bobFp,
                        )
                    } catch (_: Exception) {
                        continue
                    }

                if (!isTimestampFresh(ackData.ts)) continue

                // Verified ack: reset the staleness clock regardless of whether Bob
                // included a new key (the ack itself proves Bob is still active).
                val newSession =
                    if (ackData.newEkPub != null) {
                        Session.aliceProcessRatchetAck(entry.session, ackData.newEkPub)
                    } else {
                        null
                    }
                friends[friendId] =
                    entry.copy(
                        session = newSession ?: entry.session,
                        lastAckTs = currentTimeSeconds(),
                        isConfirmed = true,
                    )
            }

            save()
            PollBatchResult(decryptedLocations, newToken, outgoing)
        }

    /**
     * Returns true if Alice (initiator) has not received a Ratchet Ack from Bob
     * for longer than [thresholdSeconds].  Always false for Bob (non-initiator).
     *
     * When true, [LocationClient.sendLocation] skips this friend and the UI should
     * show a warning so the user knows their location is no longer being shared.
     */
    suspend fun isAckTimedOut(
        friendId: String,
        thresholdSeconds: Long = ACK_TIMEOUT_SECONDS,
    ): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            if (!entry.isInitiator) return@withLock false
            // Long.MAX_VALUE sentinel = upgraded from old data; treat as not timed out.
            if (entry.lastAckTs == Long.MAX_VALUE) return@withLock false
            currentTimeSeconds() - entry.lastAckTs > thresholdSeconds
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

        /**
         * Alice initiates an epoch rotation every this many location sends.
         * Balances forward-secrecy granularity vs. overhead.
         */
        const val EPOCH_ROTATION_INTERVAL = 50L

        /**
         * If Alice receives no Ratchet Ack from Bob for this long, she stops sending
         * location to him and the UI warns the user.
         *
         * The underlying cause is usually that Bob has disabled background location —
         * his app isn't polling, so he never processes Alice's epoch rotations and
         * never posts a Ratchet Ack back.  Seven days gives ample time for Bob to
         * open the app and trigger a maintenance poll.
         */
        const val ACK_TIMEOUT_SECONDS = 7L * 24 * 3600

        /**
         * How long Bob continues polling the old recvToken after Alice rotates epochs.
         * Per §8.3, this should be 2*T. 1 hour (3600s) is a safe upper bound.
         */
        const val PREV_TOKEN_TIMEOUT_SECONDS = 3600L
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
    val isConfirmed: Boolean = false,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
)
