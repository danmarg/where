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
            lastTs == other.lastTs
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + isInitiator.hashCode()
        result = 31 * result + nextOpkId
        result = 31 * result + (lastLat?.hashCode() ?: 0)
        result = 31 * result + (lastLng?.hashCode() ?: 0)
        result = 31 * result + (lastTs?.hashCode() ?: 0)
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
                        )
                    entry.id to entry
                }.toMutableMap()
            pendingInvite = serialized.pendingInvite
        } catch (_: Exception) {
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
                            myOpkPrivs = f.myOpkPrivs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            theirOpkPubs = f.theirOpkPubs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            nextOpkId = f.nextOpkId,
                            lastLat = f.lastLat,
                            lastLng = f.lastLng,
                            lastTs = f.lastTs,
                        )
                    },
                pendingInvite = pendingInvite,
            )
        storage.putString(STORAGE_KEY, Json.encodeToString(serialized))
    }

    suspend fun pendingQrPayload(): QrPayload? = stateLock.withLock { pendingInvite?.qrPayload }

    suspend fun createInvite(suggestedName: String): QrPayload =
        stateLock.withLock {
            val (payload, ekPriv) = KeyExchange.aliceCreateQrPayload(suggestedName)
            pendingInvite = PendingInvite(payload, ekPriv)
            save()
            payload
        }

    suspend fun clearInvite() {
        stateLock.withLock {
            pendingInvite = null
            save()
        }
    }

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
                throw e
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

    suspend fun updateFriend(entry: FriendEntry) {
        stateLock.withLock {
            friends[entry.id] = entry
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
    // OPK management
    // -----------------------------------------------------------------------

    suspend fun generateOpkBundle(
        friendId: String,
        count: Int = OPK_BATCH_SIZE,
    ): PreKeyBundlePayload? =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock null
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

    suspend fun storeOpkBundle(
        friendId: String,
        bundle: PreKeyBundlePayload,
    ): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            if (!entry.isInitiator) return@withLock false

            val opks = bundle.toOPKList()
            if (!PreKeyBundleOps.verify(
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

    suspend fun shouldReplenishOpks(friendId: String): Boolean =
        stateLock.withLock {
            val entry = friends[friendId] ?: return@withLock false
            !entry.isInitiator && entry.myOpkPrivs.size < OPK_REPLENISH_THRESHOLD
        }

    // -----------------------------------------------------------------------
    // Batch poll processing
    // -----------------------------------------------------------------------

    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
    )

    suspend fun processBatch(
        friendId: String,
        messages: List<MailboxPayload>,
    ): PollBatchResult? =
        stateLock.withLock {
            val entryAtStart = friends[friendId] ?: return@withLock null

            val decryptedLocations = mutableListOf<LocationPlaintext>()

            // Step 1: Cache incoming OPK bundles.
            for (msg in messages.filterIsInstance<PreKeyBundlePayload>()) {
                val opks = msg.toOPKList()
                val entry = friends[friendId] ?: continue
                if (!entry.isInitiator) continue
                if (PreKeyBundleOps.verify(
                        token = entry.session.recvToken,
                        opks = opks,
                        mac = msg.mac,
                        kBundle = entry.session.kBundle,
                    )
                ) {
                    val newPubMap = entry.theirOpkPubs + opks.associate { it.id to it.pub }
                    friends[friendId] = entry.copy(theirOpkPubs = newPubMap)
                }
            }

            // Step 2: Decrypt location updates.
            val sortedLocations = messages.filterIsInstance<EncryptedLocationPayload>().sortedBy { it.seqAsLong() }
            for (msg in sortedLocations) {
                val entry = friends[friendId] ?: break
                try {
                    val (newSession, loc) =
                        Session.decryptLocation(
                            state = entry.session,
                            ct = msg.ct,
                            seq = msg.seqAsLong(),
                            senderFp = entry.session.aliceFp,
                            recipientFp = entry.session.bobFp,
                            bobOpkPrivGetter = { id ->
                                val priv = friends[friendId]?.myOpkPrivs?.get(id)
                                if (priv != null) {
                                    // Remove from map after use
                                    friends[friendId] = friends[friendId]!!.copy(
                                        myOpkPrivs = friends[friendId]!!.myOpkPrivs - id
                                    )
                                }
                                priv
                            }
                        )
                    friends[friendId] = friends[friendId]!!.copy(session = newSession)
                    decryptedLocations.add(loc)
                } catch (_: Exception) {
                }
            }

            save()
            PollBatchResult(decryptedLocations)
        }

    companion object {
        private const val STORAGE_KEY = "e2ee_store"
        const val OPK_BATCH_SIZE = 10
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
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
)
