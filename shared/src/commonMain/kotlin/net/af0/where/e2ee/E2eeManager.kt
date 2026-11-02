package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Platform-specific Unicode normalization (NFKC) for homograph protection. */
internal expect fun normalizeName(name: String): String

internal fun canonicalId(
    aliceFp: Fingerprint,
    bobFp: Fingerprint,
): String {
    val a = aliceFp.toHex()
    val b = bobFp.toHex()
    return if (a < b) "$a:$b" else "$b:$a"
}

/**
 * Storage interface for persistent E2EE state.
 * Actual implementations will wrap SharedPreferences/UserDefaults.
 */
interface RawKeyValueStorage {
    fun getString(key: String): String?

    @Throws(Exception::class)
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
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    val lastRecvTs: Long = currentTimeSeconds(),
    val isConfirmed: Boolean = false,
    val lastSentTs: Long = 0L,
    val lastPollTs: Long = 0L,
    val sharingEnabled: Boolean = true,
    val lastDecryptFailed: Boolean = false,
    val version: Int = 0,
    val isCaughtUp: Boolean = false,
    /** Epoch-seconds the peer first reported `stationary=true` in the current run. Null when moving. */
    val stationarySinceTs: Long? = null,
    /** Epoch-seconds the peer sent StoppedSharing. Null when sharing or after they resume. */
    val stoppedAtTs: Long? = null,
) {
    companion object {
        const val ACK_TIMEOUT_SECONDS = 7 * 24 * 3600L
    }

    val isStale: Boolean
        get() {
            val now = currentTimeSeconds()
            return lastRecvTs != Long.MAX_VALUE && (now - lastRecvTs) > ACK_TIMEOUT_SECONDS
        }

    val id: String get() = canonicalId(session.aliceFp, session.bobFp)
    val safetyNumber: String get() = formatSafetyNumber(safetyNumber(session.aliceEkPub, session.bobEkPub))

    /**
     * True if this pairing came from [qr]: the session's inviter fingerprint is fixed at pairing
     * time and unique per invite. Lets a re-opened or redelivered invite link be rejected for as
     * long as the friend exists, including friends paired before the app tracked this.
     */
    fun isFromInvite(qr: QrPayload): Boolean = session.aliceFp == fingerprint(qr.ekPub.toDhPublicKey())
}

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
 * Acts as a coordinator between persistence and protocol logic.
 *
 * Constructing one loads the store, which opens (and migrates) the database and can fail.
 */
class E2eeManager(
    sqlDriver: app.cash.sqldelight.db.SqlDriver,
    ioDispatcher: CoroutineDispatcher,
) {
    // Secondary constructor for callers (including Swift/ObjC) that don't need to inject a dispatcher.
    // @Throws so Swift can catch a database that won't open; Kotlin/Native would otherwise abort.
    @Throws(Exception::class)
    constructor(sqlDriver: app.cash.sqldelight.db.SqlDriver) : this(sqlDriver, storageDispatcher)

    private val persistence = E2eeStore(net.af0.where.db.WhereDatabase(sqlDriver), ioDispatcher)

    val diagnosticLog: StateFlow<List<String>> = persistence.diagnosticLog

    fun addDiagnosticEvent(
        message: String,
        coalesceKey: String? = null,
    ) {
        persistence.addDiagnosticEvent(message, coalesceKey)
    }

    fun diagnosticLogSnapshot(): List<String> = diagnosticLog.value

    suspend fun createInvite(suggestedName: String): QrPayload =
        persistence.withMetadataLock {
            if (pendingInvites.size >= MAX_PENDING_INVITES) {
                // Evict an unexported invite first: an exported one has been handed to someone
                // and is likely still awaiting its scan.
                val victim =
                    pendingInvites.filter { it.exportedAt == null }.minByOrNull { it.createdAt }
                        ?: pendingInvites.minByOrNull { it.createdAt }
                pendingInvites = pendingInvites.filter { it !== victim }
            }
            val invite =
                ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.CreateInvite(suggestedName), currentTimeMillis(), SystemRng)
                    .state as ProtocolCore.State.Inviting
            val (qr, priv) = invite.qr to invite.priv
            // createdAt is tied to the advertised expiry, so a reloaded invite derives the same expires_at.
            val createdAt = qr.expiresAt?.minus(INVITE_LIFETIME_SECONDS) ?: currentTimeSeconds()
            pendingInvites = pendingInvites + PendingInvite(qr, priv, createdAt)
            qr
        }

    @Throws(
        Exception::class,
        SelfPairingException::class,
        AuthenticationException::class,
        IllegalArgumentException::class,
        CancellationException::class,
    )
    suspend fun processKeyExchangeInit(
        payload: KeyExchangeInitPayload,
        aliceSuggestedName: String,
        aliceEkPub: InvitePublicKey,
    ): FriendEntry? {
        val pending = persistence.withMetadataLock { pendingInvites.find { it.qrPayload.ekPub == aliceEkPub } } ?: return null
        val inviteKey = pending.qrPayload.ekPub

        // The core works on a copy of the key: `pending` is the live in-memory invite. If this init
        // is bogus the invite stays pending, and zeroing its key would break every later scan (and,
        // once the invite is re-persisted by a rename or share, write the zeroed key to the DB).
        val step =
            ProtocolCore.step(
                ProtocolCore.State.Inviting(pending.qrPayload, pending.aliceEkPriv),
                ProtocolCore.Event.ProcessInit(payload),
                currentTimeMillis(),
                SystemRng,
            )
        val session = (step.state as ProtocolCore.State.Paired).session

        val entry =
            FriendEntry(
                name = sanitizeName(aliceSuggestedName),
                session = session,
                isInitiator = true,
                isConfirmed = true,
            )

        // The step's RetireToken (the discovery mailbox) is carried out by removing the pending
        // invite, which is what LocationClient polls that mailbox for (#186). In one transaction
        // with saving the friend: removed alone, a crash would leave neither the invite nor the
        // friend, and Bob's init on a mailbox nobody polls. And only if the invite is still
        // pending: a concurrent init for it got there first.
        val paired =
            try {
                persistence.withFriendAndMetadataLock(entry.id) { _, metadata ->
                    if (metadata.pendingInvites.none { it.qrPayload.ekPub == inviteKey }) {
                        return@withFriendAndMetadataLock PersistenceAction.None to false
                    }
                    metadata.pendingInvites = metadata.pendingInvites.filterNot { it.qrPayload.ekPub == inviteKey }
                    PersistenceAction.Update(entry) to true
                }
            } catch (e: CancellationException) {
                // Not a failed write: the transaction may have committed and the friend's cached
                // entry may hold this very session. Leave it alone.
                throw e
            } catch (e: Exception) {
                // The write failed: E2eeStore reloaded from the database, so nothing holds the new
                // session but us. The invite's key is left alone: the reload may have left the
                // invite pending, for a retry.
                session.zeroizeAll()
                throw e
            }
        // Pending no more either way (consumed here, or by a concurrent init that got there
        // first), so its key is dead; the losing session was never saved.
        pending.aliceEkPriv.zeroize()
        if (!paired) {
            session.zeroizeAll()
            return null
        }

        return entry
    }

    /**
     * Changes the name shown in an existing invite, keeping its keys, discovery secret and expiry.
     * Used when the user edits their name while the invite is on screen. A scan of the QR as it was
     * displayed a moment ago still pairs, and nothing is minted per edit. Returns null if the invite
     * no longer exists or expires within [INVITE_RENAME_MIN_REMAINING_SECONDS]; callers then create
     * a fresh invite rather than keep showing one scanners are about to reject.
     */
    suspend fun updateInviteName(
        ekPub: InvitePublicKey,
        suggestedName: String,
    ): QrPayload? =
        persistence.withMetadataLock {
            val old = pendingInvites.find { it.qrPayload.ekPub == ekPub } ?: return@withMetadataLock null
            val expiresAt = old.qrPayload.expiresAt
            if (expiresAt != null && expiresAt - currentTimeSeconds() < INVITE_RENAME_MIN_REMAINING_SECONDS) {
                return@withMetadataLock null
            }
            val updated = old.copy(qrPayload = old.qrPayload.copy(suggestedName = suggestedName))
            pendingInvites = pendingInvites.map { if (it === old) updated else it }
            updated.qrPayload
        }

    suspend fun listPendingInvites(): List<PendingInviteView> =
        persistence.withMetadataLock {
            pendingInvites.map { it.toView() }
        }

    suspend fun clearInvite(ekPub: InvitePublicKey) {
        persistence.withMetadataLock {
            pendingInvites = pendingInvites.filterNot { it.qrPayload.ekPub == ekPub }
        }
    }

    suspend fun markInviteExported(ekPub: InvitePublicKey) {
        persistence.withMetadataLock {
            pendingInvites =
                pendingInvites.map {
                    if (it.qrPayload.ekPub == ekPub) {
                        it.copy(exportedAt = currentTimeSeconds())
                    } else {
                        it
                    }
                }
        }
    }

    suspend fun cleanupExpiredInvites() {
        val now = currentTimeSeconds()
        val unconfirmedExpired =
            persistence.withMetadataLock {
                val (kept, expired) = pendingInvites.partition { now <= it.retainUntil() }
                if (expired.isNotEmpty()) pendingInvites = kept

                // Also identify unconfirmed friends for cleanup
                friends.filter {
                    !it.isConfirmed && (now - it.lastRecvTs > INVITE_LIFETIME_SECONDS)
                }
            }

        unconfirmedExpired.forEach { friend ->
            persistence.withFriendAndMetadataLock(friend.id) { current, _ ->
                if (current != null && !current.isConfirmed && (now - current.lastRecvTs > INVITE_LIFETIME_SECONDS)) {
                    PersistenceAction.Delete to Unit
                } else {
                    PersistenceAction.None to Unit
                }
            }
        }
    }

    suspend fun getFriend(id: String): FriendEntry? = persistence.getFriend(id)

    suspend fun listFriends(): List<FriendEntry> = persistence.listFriends()

    suspend fun renameFriend(
        id: String,
        newName: String,
    ) {
        val sanitized = sanitizeName(newName)
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(name = sanitized)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    /**
     * Encrypts a message for a friend and atomically persists the advanced ratchet state.
     * This ensures that the sequence number is ALWAYS advanced locally before a network
     * attempt, preventing nonce reuse if the same message is retried later.
     */
    suspend fun encryptAndAdvance(
        friendId: String,
        payload: MessagePlaintext,
    ): Pair<EncryptedMessagePayload, SessionState> {
        return persistence.withFriendAndMetadataLock(friendId) { entry, metadata ->
            if (entry == null) throw Exception("Friend not found: $friendId")
            val (updatedEntry, message) = encryptToOutbox(friendId, entry, metadata, payload)
            PersistenceAction.Update(updatedEntry) to (message to updatedEntry.session)
        }
    }

    /**
     * Removes a friend, first queueing [farewell] (if non-null) to them. Their outbox - the
     * farewell and anything still stuck from before - is kept rather than deleted with the
     * friend, so LocationClient can deliver it later from the token and ciphertext alone.
     * Stuck messages can't simply be dropped: one may be a DH-ratchet transition the peer
     * needs to learn the token every later message (including the farewell) is posted to.
     *
     * If our KeyExchangeInit to them is still queued, pairing never got through: they're deleted
     * outright, outbox included, with no farewell, so the init can't pair us after removal.
     *
     * A farewell that fails to encrypt (e.g. broken session state) is skipped rather than
     * blocking the removal.
     */
    suspend fun deleteFriendKeepingOutbox(
        id: String,
        farewell: MessagePlaintext?,
    ) {
        persistence.withFriendAndMetadataLock(id) { entry, metadata ->
            when {
                entry == null -> PersistenceAction.None to Unit
                persistence.getOutboxInternal(id).any { it.payload is KeyExchangeInitPayload } -> PersistenceAction.Delete to Unit
                else -> {
                    if (farewell != null) {
                        try {
                            encryptToOutbox(id, entry, metadata, farewell)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            metadata.addDiagnosticEvent("removeFriend: farewell not sent: ${e.message}")
                        }
                    }
                    PersistenceAction.DeleteKeepingOutbox to Unit
                }
            }
        }
    }

    suspend fun getOrphanedOutbox(): List<Pair<String, EncryptedOutboxMessage>> = persistence.getOrphanedOutbox()

    suspend fun deleteOutboxByFriendId(friendId: String) = persistence.deleteOutboxByFriendId(friendId)

    private fun encryptToOutbox(
        friendId: String,
        entry: FriendEntry,
        metadata: E2eeStore.MetadataScope,
        payload: MessagePlaintext,
    ): Pair<FriendEntry, EncryptedMessagePayload> {
        val step =
            ProtocolCore.step(
                ProtocolCore.State.Paired(entry.session),
                ProtocolCore.Event.Send(payload),
                currentTimeMillis(),
                SystemRng,
            )
        val nextSession = (step.state as ProtocolCore.State.Paired).session
        val put = step.effects.single() as ProtocolCore.Effect.Put
        val message = put.payload as EncryptedMessagePayload
        val outboxMsg = EncryptedOutboxMessage(token = put.mailbox, payload = message)

        // lastSentTs is updated uniformly for every payload type, including Keepalive: both
        // LocationClient.sendLocation's unresponsive-friend throttle and pollFriend's automated
        // keepalive gate on "now - lastSentTs >= UNRESPONSIVE_SEND_INTERVAL_SECONDS", so whichever
        // of the two fires resets the shared clock for the other. That's what keeps them mutually
        // exclusive (a sent Location suppresses the next keepalive, and vice versa) and self-paced
        // at a consistent ~5-minute cadence instead of firing on every poll cycle. For a Location
        // or StoppedSharing it is at least that message's ts, which LocationClient may date a
        // little ahead of the clock (§5.7.2); after a restart it dates the next one from here.
        val sharingTs =
            when (payload) {
                is MessagePlaintext.Location -> payload.ts
                is MessagePlaintext.StoppedSharing -> payload.ts
                else -> Long.MIN_VALUE
            }
        val updatedEntry =
            entry.copy(
                session = nextSession,
                lastSentTs = maxOf(currentTimeSeconds(), sharingTs),
            )

        metadata.insertOutbox(
            msgId = outboxMsg.msgId,
            friendId = friendId,
            token = outboxMsg.token,
            payloadBlob = E2eeStore.json.encodeToString(MailboxPayload.serializer(), outboxMsg.payload).encodeToByteArray(),
            createdAt = outboxMsg.createdAt,
        )

        return updatedEntry to message
    }

    @Throws(Exception::class, SelfPairingException::class, InviteAlreadyAcceptedException::class, AuthenticationException::class)
    suspend fun processScannedQr(
        qr: QrPayload,
        bobSuggestedName: String = "",
    ): Pair<KeyExchangeInitPayload, FriendEntry> {
        val sanitizedRequestedName = sanitizeName(bobSuggestedName)
        val step =
            ProtocolCore.step(
                ProtocolCore.State.Idle,
                ProtocolCore.Event.ScanQr(qr, sanitizedRequestedName),
                currentTimeMillis(),
                SystemRng,
            )
        val session = (step.state as ProtocolCore.State.Paired).session
        val put = step.effects.single() as ProtocolCore.Effect.Put
        val payload = put.payload as KeyExchangeInitPayload

        val entry =
            FriendEntry(
                // Same sanitization as every other name path (callers put the user's chosen name
                // here, falling back to the inviter's QR-supplied one).
                name = sanitizeName(qr.suggestedName, fallback = "Friend"),
                session = session,
                isInitiator = false,
                lastRecvTs = currentTimeSeconds(),
                isConfirmed = false,
            )

        return persistence.withFriendAndMetadataLock(entry.id) { _, metadata ->
            metadata.insertOutbox(
                msgId = payload.msgId,
                friendId = entry.id,
                token = put.mailbox,
                payloadBlob = E2eeStore.json.encodeToString(MailboxPayload.serializer(), payload).encodeToByteArray(),
                createdAt = currentTimeMillis(),
            )
            if (metadata.pendingInvites.any { it.qrPayload.ekPub == qr.ekPub }) {
                throw SelfPairingException()
            }
            // Checked under the store lock, so two confirms of the same invite can't both pair.
            if (metadata.friends.any { it.isFromInvite(qr) }) {
                throw InviteAlreadyAcceptedException()
            }
            PersistenceAction.Update(entry) to (payload to entry)
        }
    }

    suspend fun removeFromOutbox(
        friendId: String,
        msgId: String,
    ) {
        persistence.deleteOutboxByMsgId(friendId, msgId)
    }

    suspend fun getOutbox(friendId: String): List<EncryptedOutboxMessage> = persistence.getOutbox(friendId)

    /**
     * One polled batch, processed. [ack] and [retiredToken] are the core's mailbox effects, left
     * to LocationClient to carry out (it owns the network and the force-ACK policy).
     */
    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val anySuccess: Boolean,
        val hadSilentDrops: Boolean,
        /** Ids to delete from the polled mailbox (the core's [ProtocolCore.Effect.Ack]). */
        val processedIds: List<String>,
        val anyReplay: Boolean = false,
        val failCount: Int = 0,
        val hadStateUpdate: Boolean = false,
        val hadDhRatchet: Boolean = false,
        val shouldAck: Boolean = true,
        /** The polled mailbox, if the receive token rotated away from it (the core's [ProtocolCore.Effect.RetireToken]). */
        val retiredToken: String? = null,
    )

    suspend fun processBatch(
        friendId: String,
        recvToken: String,
        messages: List<MailboxPayload>,
    ): PollBatchResult? {
        return persistence.withFriendAndMetadataLock(friendId) { entry, metadata ->
            if (entry == null) return@withFriendAndMetadataLock PersistenceAction.None to null
            // A batch from any mailbox but the current one (a poll that raced a token rotation, a
            // stale caller) is refused before anything is decrypted, saved or acked.
            if (recvToken != entry.session.recvToken.toHex()) {
                metadata.addDiagnosticEvent("processBatch($friendId): batch is from a retired mailbox; ignored")
                return@withFriendAndMetadataLock PersistenceAction.None to null
            }

            val step =
                ProtocolCore.step(
                    ProtocolCore.State.Paired(entry.session),
                    ProtocolCore.Event.Receive(recvToken, messages),
                    currentTimeMillis(),
                    SystemRng,
                )
            val finalSession = (step.state as ProtocolCore.State.Paired).session
            val report = step.report!!
            val delivered = step.effects.filterIsInstance<ProtocolCore.Effect.Deliver>().map { it.plaintext }

            // Peer activity = any frame authenticated as theirs, even one whose body was lost
            // (soft-fail). Delivery (locations, anySuccess) is tracked separately.
            val hadActivity =
                delivered.any { it is MessagePlaintext.Location } || (report.anyAuthenticated && finalSession != entry.session)
            // A peer's ts is self-reported (authenticated, but not trustworthy as a clock). A ts
            // more than the allowed skew ahead is replaced by our receive time, and a stored
            // lastTs that far ahead is ignored, so a single far-future value (bad clock or
            // malicious friend) can't freeze the pin: later honest updates are only applied
            // when their ts >= the stored one. (Clamping to now + skew instead would still
            // block honest updates for the whole skew window.)
            val nowTs = currentTimeSeconds()
            val maxTs = nowTs + MAX_PEER_CLOCK_SKEW_SECONDS

            fun clampTs(ts: Long) = if (ts > maxTs) nowTs else ts
            // Clamped values are also what callers see (PollBatchResult -> UserLocation ->
            // updateLastLocation), so the raw ts never reaches storage or the UI.
            val decryptedLocations =
                delivered.filterIsInstance<MessagePlaintext.Location>().map {
                    LocationPlaintext(lat = it.lat, lng = it.lng, acc = it.acc, ts = clampTs(it.ts), stationary = it.stationary)
                }
            val stoppedSharingTs = delivered.filterIsInstance<MessagePlaintext.StoppedSharing>().maxOfOrNull { it.ts }?.let { clampTs(it) }
            val lastLocation = decryptedLocations.lastOrNull()
            val storedLastTs = entry.lastTs?.takeIf { it <= maxTs } ?: 0

            // In the same transaction as the new session (see ProtocolCore's atomicity contract).
            for (effect in step.effects) {
                if (effect is ProtocolCore.Effect.CancelPut) metadata.deleteOutbox(friendId, effect.mailbox)
            }

            // Derive the sharing-state fields (stationarySinceTs, stoppedAtTs) atomically
            // from the most recent event in this batch. Whichever of {last Location, max
            // StoppedSharing ts} is later wins: a Location implies the peer is active
            // (clear stopped); a StoppedSharing implies they paused (clear stationary).
            // Within a stationary run we preserve the original "since" timestamp.
            val lastLocTs = lastLocation?.ts ?: Long.MIN_VALUE
            val stopTs = stoppedSharingTs ?: Long.MIN_VALUE
            val newStationarySinceTs: Long?
            val newStoppedAtTs: Long?
            when {
                lastLocation == null && stoppedSharingTs == null -> {
                    newStationarySinceTs = entry.stationarySinceTs
                    newStoppedAtTs = entry.stoppedAtTs
                }
                stopTs > lastLocTs -> {
                    newStationarySinceTs = null
                    newStoppedAtTs = stoppedSharingTs
                }
                else -> {
                    newStoppedAtTs = null
                    newStationarySinceTs =
                        if (lastLocation!!.stationary) {
                            entry.stationarySinceTs ?: lastLocation.ts
                        } else {
                            null
                        }
                }
            }

            val updatedEntry =
                entry.copy(
                    session = finalSession,
                    isConfirmed = entry.isConfirmed || report.anyAuthenticated,
                    lastRecvTs = if (hadActivity) currentTimeSeconds() else entry.lastRecvTs,
                    lastLat = if (lastLocation != null && (lastLocation.ts >= storedLastTs)) lastLocation.lat else entry.lastLat,
                    lastLng = if (lastLocation != null && (lastLocation.ts >= storedLastTs)) lastLocation.lng else entry.lastLng,
                    lastTs = if (lastLocation != null && (lastLocation.ts >= storedLastTs)) lastLocation.ts else entry.lastTs,
                    lastPollTs = currentTimeSeconds(),
                    stationarySinceTs = newStationarySinceTs,
                    stoppedAtTs = newStoppedAtTs,
                )

            val idsToAck = step.effects.filterIsInstance<ProtocolCore.Effect.Ack>().flatMap { it.ids }
            PersistenceAction.Update(updatedEntry) to
                PollBatchResult(
                    decryptedLocations = decryptedLocations,
                    anySuccess = report.anySuccess,
                    hadSilentDrops = report.silentDrops > 0,
                    processedIds = idsToAck,
                    anyReplay = report.anyReplay,
                    failCount = report.failCount,
                    hadStateUpdate = report.stateAdvanced,
                    hadDhRatchet = report.dhRatchet,
                    shouldAck = idsToAck.isNotEmpty(),
                    retiredToken = step.effects.filterIsInstance<ProtocolCore.Effect.RetireToken>().singleOrNull()?.mailbox,
                )
        }
    }

    suspend fun updateLastLocation(
        id: String,
        lat: Double,
        lng: Double,
        ts: Long,
    ) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            val maxTs = currentTimeSeconds() + MAX_PEER_CLOCK_SKEW_SECONDS
            val storedTs = entry?.lastTs?.takeIf { it <= maxTs } ?: 0
            // Same rules as processBatch: never store a far-future ts, never go backwards.
            if (entry != null && ts <= maxTs && ts >= storedTs) {
                PersistenceAction.Update(entry.copy(lastLat = lat, lastLng = lng, lastTs = ts)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateLastPollTs(
        id: String,
        ts: Long,
    ) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null) {
                PersistenceAction.Update(entry.copy(lastPollTs = ts)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    suspend fun updateIsCaughtUp(
        id: String,
        isCaughtUp: Boolean,
    ) {
        persistence.withFriendAndMetadataLock(id) { entry, _ ->
            if (entry != null && entry.isCaughtUp != isCaughtUp) {
                PersistenceAction.Update(entry.copy(isCaughtUp = isCaughtUp)) to Unit
            } else {
                PersistenceAction.None to Unit
            }
        }
    }

    @Suppress("RawKeyBytes") // bobEkPub is the scanner's key as received; fromWire checks it
    suspend fun decryptSuggestedName(
        aliceEkPub: InvitePublicKey,
        bobEkPub: ByteArray,
        encryptedName: ByteArray,
    ): String? {
        return persistence.withMetadataLock {
            val pending = pendingInvites.find { it.qrPayload.ekPub == aliceEkPub }
            if (pending == null) return@withMetadataLock null
            try {
                val bobPub = DhPublicKey.fromWire(bobEkPub)
                val sk = dh(pending.aliceEkPriv, bobPub)
                try {
                    KeyExchange.requireNonZeroSharedSecret(sk)
                    KeyExchange.decryptSuggestedName(sk, aliceEkPub.toDhPublicKey(), bobPub, encryptedName)
                } finally {
                    sk.zeroize()
                }
            } catch (e: Exception) {
                addDiagnosticEvent("decryptSuggestedName failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Strips control and format characters (incl. bidi overrides and zero-width marks) and
     * limits length, without dropping emoji or other symbols users pick as names. Falls back to
     * [fallback] if nothing printable remains.
     */
    private fun sanitizeName(
        name: String,
        fallback: String = "",
    ): String {
        val cleaned =
            normalizeName(name)
                .filterNot { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT }
                .take(32)
                .let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
                .trim()
        return cleaned.ifEmpty { fallback }
    }

    companion object {
        const val MAX_PENDING_INVITES = 10

        /** How far a peer's self-reported location ts may run ahead of our clock. */
        const val MAX_PEER_CLOCK_SKEW_SECONDS = 10 * 60L
    }
}
