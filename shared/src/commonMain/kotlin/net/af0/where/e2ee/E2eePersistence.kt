package net.af0.where.e2ee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
    val createdAt: Long = currentTimeSeconds(),
    val exportedAt: Long? = null,
)

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
    val consecutiveSilentDrops: Int = 0,
    val lastSavedTs: Long = 0L,
)

@Serializable
internal data class GlobalMetadata(
    val friendIds: List<String> = emptyList(),
    val pendingInvites: List<PendingInvite> = emptyList(),
    val diagnosticLog: List<String> = emptyList(),
    val lastSavedTs: Long = 0L,
)

internal sealed class PersistenceAction {
    data class Update(val entry: FriendEntry) : PersistenceAction()
    object Delete : PersistenceAction()
    object None : PersistenceAction()
}

/**
 * Handles all persistent storage and concurrency control for the E2EE module.
 * Enforces the strict lock hierarchy: 1. friendLock, 2. metadataLock.
 */
internal class E2eePersistence(
    private val storage: E2eeStorage,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val globalDb = DoubleBufferedStorage(
        storage = storage,
        serializer = GlobalMetadata.serializer(),
        json = json,
        timestampSelector = { it.lastSavedTs },
    )

    private val friendDb = DoubleBufferedStorage(
        storage = storage,
        serializer = SerializedFriendEntry.serializer(),
        json = json,
        timestampSelector = { it.lastSavedTs },
    )

    // In-memory state
    private var friends = mutableMapOf<String, FriendEntry>()
    private var pendingInvites = mutableListOf<PendingInvite>()
    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    private var lastUsedTs: Long = 0L

    // Locks
    private val metadataLock = Mutex()
    private val friendLocks = mutableMapOf<String, Mutex>()
    private val locksLock = Mutex()

    init {
        load()
    }

    private fun load() {
        val global = globalDb.load(STORAGE_KEY_GLOBAL)
        if (global != null) {
            lastUsedTs = maxOf(lastUsedTs, global.lastSavedTs)
            pendingInvites = global.pendingInvites.toMutableList()
            _diagnosticLog.value = global.diagnosticLog

            friends.clear()
            global.friendIds.forEach { id ->
                val s = friendDb.load(friendKey(id))
                if (s != null) {
                    friends[id] = s.toEntry()
                    lastUsedTs = maxOf(lastUsedTs, s.lastSavedTs)
                }
            }
        }
    }

    private fun nextTs(): Long {
        val now = currentTimeSeconds()
        lastUsedTs = if (now <= lastUsedTs) lastUsedTs + 1 else now
        return lastUsedTs
    }

    private suspend fun getFriendLock(id: String): Mutex {
        locksLock.withLock {
            return friendLocks.getOrPut(id) { Mutex() }
        }
    }

    suspend fun <T> withMetadataLock(block: suspend MetadataScope.() -> T): T {
        return metadataLock.withLock {
            val scope = MetadataScopeImpl()
            val result = block(scope)
            if (scope.dirty) {
                saveGlobalInternal(
                    nextInvites = scope.pendingInvites,
                    nextLog = scope.diagnosticLog
                )
            }
            result
        }
    }

    suspend fun <T> withFriendAndMetadataLock(
        friendId: String,
        block: suspend (FriendEntry?, MetadataScope) -> Pair<PersistenceAction, T>
    ): T {
        val lock = getFriendLock(friendId)
        return lock.withLock {
            metadataLock.withLock {
                val entry = friends[friendId]
                val scope = MetadataScopeImpl()
                val (action, result) = block(entry, scope)
                
                var globalDirty = scope.dirty
                when (action) {
                    is PersistenceAction.Update -> {
                        if (!friends.containsKey(friendId)) {
                            globalDirty = true
                        }
                        saveFriendInternal(friendId, action.entry)
                    }
                    is PersistenceAction.Delete -> {
                        if (friends.containsKey(friendId)) {
                            globalDirty = true
                            deleteFriendInternal(friendId)
                        }
                    }
                    is PersistenceAction.None -> {}
                }

                if (globalDirty) {
                    saveGlobalInternal(
                        nextInvites = scope.pendingInvites,
                        nextLog = scope.diagnosticLog
                    )
                }
                result
            }
        }
    }

    suspend fun getFriend(id: String): FriendEntry? = metadataLock.withLock { friends[id] }
    suspend fun listFriends(): List<FriendEntry> = metadataLock.withLock { friends.values.toList() }

    fun addDiagnosticEvent(message: String) {
        val t = currentTimeSeconds()
        val s = (t % 86400).toInt()
        val entry = "${(s / 3600).toString().padStart(2, '0')}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')} $message"
        _diagnosticLog.value = (listOf(entry) + _diagnosticLog.value).take(MAX_DIAGNOSTIC_EVENTS)
    }

    private fun saveGlobalInternal(
        nextFriendIds: List<String>? = null,
        nextInvites: List<PendingInvite>? = null,
        nextLog: List<String>? = null,
    ) {
        val saveTs = nextTs()
        val metadata = GlobalMetadata(
            friendIds = nextFriendIds ?: friends.keys.toList(),
            pendingInvites = nextInvites ?: pendingInvites,
            diagnosticLog = nextLog ?: _diagnosticLog.value,
            lastSavedTs = saveTs,
        )
        globalDb.save(STORAGE_KEY_GLOBAL, metadata)
        if (nextInvites != null) pendingInvites = nextInvites.toMutableList()
        if (nextLog != null) _diagnosticLog.value = nextLog
    }

    private fun saveFriendInternal(friendId: String, entry: FriendEntry) {
        val ts = nextTs()
        friendDb.save(friendKey(friendId), entry.toSerialized(ts))
        friends[friendId] = entry
    }

    private fun deleteFriendInternal(friendId: String) {
        storage.putString("${friendKey(friendId)}_a", "")
        storage.putString("${friendKey(friendId)}_b", "")
        friends.remove(friendId)
    }

    private fun SerializedFriendEntry.toEntry() = FriendEntry(
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
        consecutiveSilentDrops = consecutiveSilentDrops,
    )

    private fun FriendEntry.toSerialized(ts: Long) = SerializedFriendEntry(
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
        consecutiveSilentDrops = consecutiveSilentDrops,
        lastSavedTs = ts,
    )

    private fun friendKey(id: String) = "e2ee_friend_$id"

    interface MetadataScope {
        val friends: List<FriendEntry>
        var pendingInvites: List<PendingInvite>
        var diagnosticLog: List<String>
    }

    private inner class MetadataScopeImpl : MetadataScope {
        var dirty = false
        override val friends: List<FriendEntry> get() = this@E2eePersistence.friends.values.toList()
        override var pendingInvites: List<PendingInvite> = this@E2eePersistence.pendingInvites
            set(value) {
                field = value
                dirty = true
            }
        override var diagnosticLog: List<String> = this@E2eePersistence._diagnosticLog.value
            set(value) {
                field = value
                dirty = true
            }
    }

    companion object {
        private const val STORAGE_KEY_GLOBAL = "e2ee_global"
    }
}
