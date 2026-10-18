package net.af0.where.e2ee

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * High-level session operations using a standard Double Ratchet.
 *
 * Deterministic given its inputs: randomness comes from the [Rng] passed in and the time from
 * `nowMillis`, never from the platform directly. The defaults ([SystemRng], the clock) are what the
 * app uses; tests and the golden vectors pass their own.
 */
object Session {
    private const val HEADER_TAG_SIZE = 16

    fun encryptMessage(
        state: SessionState,
        payload: MessagePlaintext,
        rng: Rng = SystemRng,
    ): OutgoingMessage = encryptPadded(state, padToFixedSize(encodeMessage(payload), PADDING_SIZE), rng)

    /**
     * Encrypts an already-padded plaintext. Separate from [encryptMessage] so tests can
     * produce authenticated frames whose plaintext is malformed. Zeroizes [plaintext].
     */
    internal fun encryptPadded(
        state: SessionState,
        plaintext: ByteArray,
        rng: Rng,
    ): OutgoingMessage {
        if (state.sendSeq == Long.MAX_VALUE) throw SessionBrickedException("sequence number overflow")

        // No lazy send-side ratchet here: performDhRatchet runs both sub-steps as soon as a new
        // peer DH key is received, so the send chain is always current.
        val currentState = state

        // DOUBLE RATCHET TRANSITION PROTECTION (§4.3): the epoch's first message goes to the
        // previous mailbox under the previous header key (see transportFor).
        val seqToUse = currentState.sendSeq + 1
        val transport = currentState.transportFor(seqToUse)

        val chainKeyToUse = currentState.sendChainKey
        val headerKeyToUse = transport.headerKey
        val dhPubToUse = currentState.localDhPub
        val ackRemoteToUse = currentState.remoteDhPub

        val step = kdfCk(chainKeyToUse)
        val aad = currentState.parties.sendAad(seqToUse, dhPubToUse, ackRemoteToUse)

        val ct = sealMessage(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            currentState.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seqToUse,
            )

        // Seal the metadata into an envelope (#186)
        val envelope = encryptHeader(headerKeyToUse, dhPubToUse, ackRemoteToUse, seqToUse, currentState.pn, currentState.headerAad(), rng)

        // Memory Hygiene (sealMessage already consumed the message key and nonce)
        plaintext.zeroize()

        val message =
            EncryptedMessagePayload(
                v = PROTOCOL_VERSION,
                envelope = envelope,
                ct = ct,
            )

        return OutgoingMessage(newState, message, transport.token)
    }

    /**
     * Decrypts one received frame. Every protocol failure comes back as a [ReceivedMessage],
     * which says what to persist; only JVM/native [Error]s (e.g. out of memory) propagate.
     */
    fun decryptMessage(
        state: SessionState,
        message: EncryptedMessagePayload,
        preDecryptedHeader: DecryptedHeader? = null,
        nowMillis: Long = currentTimeMillis(),
        rng: Rng = SystemRng,
    ): ReceivedMessage =
        try {
            val (newState, plaintext) = decryptMessageOrThrow(state, message, preDecryptedHeader, nowMillis, rng)
            ReceivedMessage.Delivered(newState, plaintext)
        } catch (e: DecryptionExceptionWithState) {
            ReceivedMessage.Undeliverable(e.newState, e.cause ?: e)
        } catch (e: ReplayException) {
            ReceivedMessage.Replay(e)
        } catch (e: Exception) {
            ReceivedMessage.Rejected(e)
        }

    private fun decryptMessageOrThrow(
        state: SessionState,
        message: EncryptedMessagePayload,
        preDecryptedHeader: DecryptedHeader?,
        now: Long,
        rng: Rng,
    ): Pair<SessionState, MessagePlaintext> {
        // 0. Purge stale message keys (#189)
        val cleanSkippedKeys = LinkedHashMap(state.skippedMessageKeys)
        var modified = false
        val it = cleanSkippedKeys.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val v = entry.value
            if ((now - v.storedAtMillis) > MAX_KEY_AGE_MS) {
                // Dropped, not wiped: this buffer is still referenced by the caller's input
                // state (see the ownership rule below). It disappears from storage when the
                // new state is persisted.
                it.remove()
                modified = true
            }
        }
        val cleanState = if (modified) state.copy(skippedMessageKeys = cleanSkippedKeys) else state

        // 1. Unwrap the envelope to reveal metadata (#186)
        val header: DecryptedHeader =
            preDecryptedHeader ?: run {
                val sessionAad = cleanState.headerAad()
                cleanState.recvHeaderKeys().firstNotNullOfOrNull { key ->
                    try {
                        decryptHeader(key, message.envelope, sessionAad)
                    } catch (e: Exception) {
                        null
                    }
                } ?: throw AuthenticationException("envelope decryption failed — incorrect key or corrupted metadata")
            }

        val remoteDhPub = header.dhPub
        val ackRemoteDhPub = header.ackRemoteDhPub
        val isNewDhEpoch = remoteDhPub != cleanState.remoteDhPub
        val seq = header.seq

        // Cache Key Invariant (§5.5): toHex() on a 32-byte X25519 key is ALWAYS 64 chars.
        // Colon separator prevents collision if platform hex implementations vary.
        val cacheKey = remoteDhPub.toHex() + ":" + seq

        // 2. Check skipped message key cache first
        val cachedKey = cleanState.skippedMessageKeys[cacheKey]
        if (cachedKey != null) {
            // Copies: the cached entry belongs to the input state (see the ownership rule below).
            val mk = cachedKey.messageKey.copyOf()
            val nonce = cachedKey.nonce.copyOf()
            val aad = cleanState.parties.recvAad(seq, remoteDhPub, ackRemoteDhPub)

            val plaintext =
                try {
                    openMessage(mk, nonce, message.ct, aad)
                } catch (e: Exception) {
                    throw AuthenticationException("decryption failed with cached key", e)
                }

            // Authenticated: the key is spent either way. A malformed plaintext is committed
            // and discarded (§8.3.1(4)) rather than retried forever.
            val newCache = LinkedHashMap(cleanState.skippedMessageKeys)
            newCache.remove(cacheKey)
            val newState = cleanState.copy(skippedMessageKeys = newCache)

            val decoded =
                try {
                    val unpadded = unpad(plaintext)
                    try {
                        decodeMessage(unpadded)
                    } finally {
                        unpadded.zeroize()
                    }
                } catch (e: Exception) {
                    throw DecryptionExceptionWithState(newState, e)
                } finally {
                    plaintext.zeroize()
                }

            return newState to decoded
        }

        // 3. Speculatively perform DH and symmetric ratchet.
        //
        // Buffer ownership rule for everything below:
        //  - Never mutate (zeroize) a buffer reachable from the input `state`. The caller keeps
        //    using it after a hard failure, and the store's cached copy is the same object
        //    until the new state is persisted.
        //  - Every buffer created here is either moved into the state this call commits
        //    (returned, or attached to DecryptionExceptionWithState) or zeroized on exit.
        // `committed` flips exactly once, when ownership moves to `newState`.
        var speculativeState = cleanState

        if (isNewDhEpoch) {
            // Ratchet state forward. Note: headerKey transition happens inside performDhRatchet.
            // Across-epoch replays are dropped earlier at the header-decryption stage:
            // tryDecryptHeader only ever holds the current + next receive header keys
            // (see §2.2, §8.3.1(6)), so a replayed retired-epoch frame fails to decrypt
            // its header before any ratchet logic runs.
            // performDhRatchet deep-copies, so every buffer in the result is fresh (owned here).
            speculativeState = performDhRatchet(speculativeState, remoteDhPub, rng)
        }

        var chainKey: ChainKey? = null // the receive chain as advanced so far; owned here
        var currentStep: ChainStep? = null
        val derivedKeys = mutableListOf<SkippedMessageKey>() // fresh skipped-key entries created here
        var committed = false

        try {
            // Replay rejection against speculative seq
            if (seq <= speculativeState.recvSeq) {
                throw ReplayException("replay: seq $seq <= recvSeq ${speculativeState.recvSeq}")
            }

            val stepsNeeded = seq - speculativeState.recvSeq
            if (stepsNeeded > MAX_SKIPPED_KEYS + 1) {
                throw ProtocolGapException("gap too large: stepsNeeded $stepsNeeded")
            }

            // Derivation loop for chain keys and skipped message keys
            val derivationSkippedKeys = LinkedHashMap(speculativeState.skippedMessageKeys)

            // Clear skipped keys for old epochs. Only reached for a new epoch, where the values
            // are performDhRatchet's fresh copies (never the input's buffers), so wiping is safe.
            if (isNewDhEpoch) {
                val validEpochs = setOf(remoteDhPub.toHex(), cleanState.remoteDhPub.toHex())
                val it = derivationSkippedKeys.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (validEpochs.none { e -> entry.key.startsWith(e) }) {
                        entry.value.zeroize()
                        it.remove()
                    }
                }
            }

            val pnGaps =
                if (isNewDhEpoch && header.pn > cleanState.recvSeq) {
                    val diff = header.pn - cleanState.recvSeq
                    if (diff > MAX_SKIPPED_KEYS) throw ProtocolGapException("previous-chain pn gap too large: $diff")
                    diff.toInt()
                } else {
                    0
                }

            val projectedSize = derivationSkippedKeys.size + kotlin.math.max(0, stepsNeeded.toInt() - 1) + pnGaps
            if (projectedSize > MAX_SKIPPED_KEYS) {
                throw ProtocolGapException("combined skipped key gap too large: $projectedSize")
            }

            var ck = speculativeState.recvChainKey.copyOf()
            chainKey = ck

            // Advance the chain to 'seq', storing intermediate message keys
            repeat(stepsNeeded.toInt()) { i ->
                val step = kdfCk(ck)
                // Zero OLD chain key immediately after step
                ck.zeroize()
                ck = step.newChainKey
                chainKey = ck

                val currentSeq = speculativeState.recvSeq + i + 1
                if (currentSeq < seq) {
                    // Store intermediate message key for future out-of-order delivery.
                    val mkPlusNonce = SkippedMessageKey.takeFrom(step, now)
                    derivedKeys.add(mkPlusNonce)
                    // No eviction needed: the projectedSize admission check above bounds the cache.
                    derivationSkippedKeys[remoteDhPub.toHex() + ":" + currentSeq] = mkPlusNonce
                } else {
                    currentStep = step
                }
            }

            // PH-3.3: Gap filling for the PREVIOUS receiving chain. Done before the body AEAD
            // check so that a corrupted new-epoch frame leaves the same skipped-key cache as a
            // dropped one followed by a good one (§8.3.1(4)).
            if (pnGaps > 0) {
                val prevEpochHex = cleanState.remoteDhPub.toHex()
                var oldChainKey = cleanState.recvChainKey.copyOf()
                repeat(pnGaps) { i ->
                    val step = kdfCk(oldChainKey)
                    oldChainKey.zeroize()
                    oldChainKey = step.newChainKey
                    val mkPlusNonce = SkippedMessageKey.takeFrom(step, now)
                    derivedKeys.add(mkPlusNonce)
                    derivationSkippedKeys[prevEpochHex + ":" + (cleanState.recvSeq + i + 1)] = mkPlusNonce
                }
                oldChainKey.zeroize()
            }

            val finalStep = currentStep ?: throw ProtocolException("failed to derive message key")
            val aad = cleanState.parties.recvAad(seq, remoteDhPub, ackRemoteDhPub)

            // The state advanced to `seq`. It is committed whether or not the body decrypts
            // (§8.3.1(4)): a server that drops a frame and one that corrupts it must leave
            // identical state, or the ratchet desyncs permanently. The failed message's key
            // itself is never cached (it would live up to 7 days for no benefit).
            // A new-epoch speculative state is already all fresh buffers; otherwise it aliases
            // the input, so copy it (minus the skipped-key map, replaced below) rather than hand
            // the input's buffers to the new state.
            val base =
                if (isNewDhEpoch) speculativeState else speculativeState.copy(skippedMessageKeys = emptyMap()).deepCopy()
            val newState =
                base.copy(
                    recvChainKey = ck,
                    recvSeq = seq,
                    skippedMessageKeys = derivationSkippedKeys,
                )
            base.recvChainKey.zeroize() // superseded by chainKey; no longer referenced
            committed = true

            val plaintext =
                try {
                    openMessage(finalStep.messageKey, finalStep.messageNonce, message.ct, aad)
                } catch (e: Exception) {
                    throw DecryptionExceptionWithState(newState, DecryptionException("message body authentication failed", e))
                }

            // Likewise, an authenticated frame whose plaintext is malformed (bad padding,
            // invalid JSON) came from the peer; commit and discard it.
            val decoded =
                try {
                    val unpadded = unpad(plaintext)
                    try {
                        decodeMessage(unpadded)
                    } finally {
                        unpadded.zeroize()
                    }
                } catch (e: Exception) {
                    throw DecryptionExceptionWithState(newState, e)
                } finally {
                    plaintext.zeroize()
                }

            return newState to decoded
        } finally {
            if (!committed) {
                // Nothing was handed to a caller: wipe every buffer created by this call.
                chainKey?.zeroize()
                currentStep?.let {
                    it.messageKey.zeroize()
                    it.messageNonce.zeroize()
                }
                derivedKeys.forEach { it.zeroize() }
                if (isNewDhEpoch) speculativeState.zeroizeAll()
            }
        }
    }

    /**
     * Perform the DH ratchet step (pure computation).
     * Returns a new SessionState with updated DH keys and tokens.
     * Does NOT zero the input state's private key (caller must do so after commit).
     */
    private fun performDhRatchet(
        state: SessionState,
        remoteDhPub: DhPublicKey,
        rng: Rng,
    ): SessionState {
        // DH ratchet step
        val stepRecv = dhRatchetSubStep(state.localDhPriv, remoteDhPub, state.rootKey)
        val newLocalDh = rng.x25519KeyPair()
        val newLocalPriv = DhPrivateKey(newLocalDh.priv)
        val stepSend =
            try {
                dhRatchetSubStep(newLocalPriv, remoteDhPub, stepRecv.newRootKey)
            } catch (e: Exception) {
                stepRecv.newRootKey.zeroize()
                stepRecv.newChainKey.zeroize()
                stepRecv.newHeaderKey.zeroize()
                newLocalPriv.zeroize()
                throw e
            }

        // Derive new tokens
        val newRecvToken = state.parties.recvToken(stepRecv.newRootKey)
        val newSendToken = state.parties.sendToken(stepSend.newRootKey)

        // Every buffer in the result is fresh and not shared with `state`: freshly derived ones
        // are moved in (not copied, so no stray copies are left unwiped), and the carried-over
        // fields are copied. Keep this covering every ByteArray field (see SessionState).
        val newState =
            state.copy(
                rootKey = stepSend.newRootKey,
                recvChainKey = stepRecv.newChainKey,
                sendChainKey = stepSend.newChainKey,
                headerKey = state.nextHeaderKey?.copyOf(),
                sendHeaderKey = stepRecv.newHeaderKey,
                nextHeaderKey = stepSend.newHeaderKey,
                sendToken = newSendToken,
                recvToken = newRecvToken,
                prevSendHeaderKey = state.sendHeaderKey?.copyOf(),
                sendSeq = 0L,
                recvSeq = 0L,
                pn = state.sendSeq,
                pr = state.recvSeq,
                localDhPriv = newLocalPriv,
                localDhPub = DhPublicKey(newLocalDh.pub),
                remoteDhPub = remoteDhPub.copyOf(),
                prevSendToken = state.sendToken.copyOf(),
                aliceEkPub = state.aliceEkPub.copyOf(),
                bobEkPub = state.bobEkPub.copyOf(),
                aliceFp = state.aliceFp.copyOf(),
                bobFp = state.bobFp.copyOf(),
                localFp = state.localFp?.copyOf(),
                remoteFp = state.remoteFp?.copyOf(),
                skippedMessageKeys = state.skippedMessageKeys.mapValues { it.value.copyOf() },
            )

        // Only used as KDF input for the send sub-step; not part of the new state.
        stepRecv.newRootKey.zeroize()

        return newState
    }

    internal fun encryptHeader(
        key: HeaderKey,
        dhPub: DhPublicKey,
        ackRemoteDhPub: DhPublicKey,
        seq: Long,
        pn: Long,
        aad: ByteArray = ByteArray(0),
        rng: Rng,
    ): ByteArray {
        val plaintext = ByteArray(1 + 32 + 32 + 8 + 8 + 1)
        plaintext[0] = PROTOCOL_VERSION.toByte()
        dhPub.toByteArray().copyInto(plaintext, 1)
        ackRemoteDhPub.toByteArray().copyInto(plaintext, 33)
        longToBeBytes(seq).copyInto(plaintext, 65)
        longToBeBytes(pn).copyInto(plaintext, 73)
        plaintext[81] = 0 // flags

        return sealRandomNonce(key, plaintext, aad, rng)
    }

    data class DecryptedHeader(val dhPub: DhPublicKey, val ackRemoteDhPub: DhPublicKey, val seq: Long, val pn: Long)

    internal fun decryptHeader(
        key: HeaderKey,
        envelope: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): DecryptedHeader {
        if (envelope.size < RANDOM_NONCE_SIZE + HEADER_TAG_SIZE + 82) {
            throw ProtocolException("header envelope too small")
        }
        val plaintext =
            try {
                openRandomNonce(key, envelope, aad)
            } catch (e: Exception) {
                throw AuthenticationException("header decryption failed — signature mismatch or corrupted data", e)
            }

        if (plaintext[0].toInt() != PROTOCOL_VERSION) {
            throw ProtocolException("unsupported protocol version in header: ${plaintext[0].toInt()}")
        }

        val dhPub = DhPublicKey(plaintext.copyOfRange(1, 33))
        val ackRemoteDhPub = DhPublicKey(plaintext.copyOfRange(33, 65))
        val seq = bytesToLong(plaintext.copyOfRange(65, 73))
        val pn = bytesToLong(plaintext.copyOfRange(73, 81))
        return DecryptedHeader(dhPub, ackRemoteDhPub, seq, pn)
    }

    internal fun encodeMessage(msg: MessagePlaintext): ByteArray =
        buildJsonObject {
            when (msg) {
                is MessagePlaintext.Location -> {
                    put("type", "loc")
                    put("lat", msg.lat)
                    put("lng", msg.lng)
                    put("acc", msg.acc)
                    put("ts", msg.ts)
                    put("precision", msg.precision.name)
                    if (msg.stationary) put("stationary", true)
                }
                is MessagePlaintext.Keepalive -> {
                    put("type", "ka")
                }
                is MessagePlaintext.StoppedSharing -> {
                    put("type", "stop")
                    put("ts", msg.ts)
                }
            }
        }.let { Json.encodeToString(it) }.encodeToByteArray()

    internal fun decodeMessage(bytes: ByteArray): MessagePlaintext =
        runCatching {
            val obj = Json.decodeFromString<JsonObject>(bytes.decodeToString())
            when (obj["type"]?.jsonPrimitive?.content) {
                "loc" -> decodeLocation(obj) ?: MessagePlaintext.Keepalive()
                "ka" -> MessagePlaintext.Keepalive()
                "stop" -> {
                    // Lenient: a malformed "stop" missing/invalid ts degrades to a
                    // Keepalive instead of hard-failing the whole frame, matching
                    // the unknown-type behavior below (spec §5.7.3).
                    val ts = obj["ts"]?.jsonPrimitive?.longOrNull
                    if (ts != null) MessagePlaintext.StoppedSharing(ts = ts) else MessagePlaintext.Keepalive()
                }
                null -> {
                    // Legacy emitter: sniff for "lat" → Location, else Keepalive.
                    if (obj.containsKey("lat")) decodeLocation(obj) ?: MessagePlaintext.Keepalive() else MessagePlaintext.Keepalive()
                }
                else -> {
                    // Unknown future variant — treat as Keepalive (safe no-op).
                    MessagePlaintext.Keepalive()
                }
            }
        }.getOrElse { e -> throw DecryptionException("malformed message plaintext", e) }

    private fun decodeLocation(obj: JsonObject): MessagePlaintext.Location? {
        // Missing or non-finite required coordinates → degrade to Keepalive (§5.7.3 leniency,
        // mirrors "stop" handling). "1e999" and "NaN" parse as doubles; neither is a place.
        fun finite(key: String) = obj[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val lat = finite("lat") ?: return null
        val lng = finite("lng") ?: return null
        val acc = finite("acc") ?: return null
        val ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: return null
        // Unknown precision string (e.g. a future "MEDIUM") falls back to FINE for forward-compat.
        val precision =
            obj["precision"]?.jsonPrimitive?.content
                ?.let { runCatching { LocationPrecision.valueOf(it) }.getOrNull() }
                ?: LocationPrecision.FINE
        return MessagePlaintext.Location(
            lat = lat,
            lng = lng,
            acc = acc,
            ts = ts,
            precision = precision,
            stationary = obj["stationary"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    internal fun padToFixedSize(
        data: ByteArray,
        size: Int,
    ): ByteArray {
        require(data.size > 0) { "plaintext must not be empty" }
        require(data.size < size) { "plaintext (${data.size} bytes) too large for PADDING_SIZE $size" }

        return data.copyOf(size).also { padded ->
            padded[data.size] = 0x80.toByte()
            // remaining bytes are already 0x00 from copyOf
        }
    }

    internal fun unpad(data: ByteArray): ByteArray {
        var markerIdx = -1
        var diff = 0
        for (j in data.indices.reversed()) {
            val isMarker = (data[j].toInt() and 0xFF) xor 0x80
            val notYetFound = if (markerIdx == -1) 1 else 0
            if (isMarker == 0 && notYetFound == 1) {
                markerIdx = j
            } else if (notYetFound == 1) {
                diff = diff or (data[j].toInt() and 0xFF) // must be 0x00
            }
        }
        if (diff != 0 || markerIdx < 0) {
            data.zeroize()
            throw ProtocolException("padding corruption")
        }
        return data.copyOfRange(0, markerIdx)
    }
}

sealed class MessagePlaintext {
    data class Location(
        val lat: Double,
        val lng: Double,
        val acc: Double,
        val ts: Long,
        val precision: LocationPrecision = LocationPrecision.FINE,
        val stationary: Boolean = false,
    ) : MessagePlaintext() {
        fun blur(): Location =
            if (precision == LocationPrecision.COARSE) {
                copy(
                    lat = kotlin.math.round(lat * 100.0) / 100.0,
                    lng = kotlin.math.round(lng * 100.0) / 100.0,
                    acc = kotlin.math.max(acc, 1100.0),
                )
            } else {
                this
            }
    }

    class Keepalive : MessagePlaintext()

    data class StoppedSharing(val ts: Long) : MessagePlaintext()
}

class SessionBrickedException(message: String) : WhereException(message)
