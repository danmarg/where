package net.af0.where.e2ee

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * High-level session operations using a standard Double Ratchet.
 */
object Session {
    private const val HEADER_NONCE_SIZE = 12
    private const val HEADER_TAG_SIZE = 16

    fun encryptMessage(
        state: SessionState,
        payload: MessagePlaintext,
    ): Pair<SessionState, EncryptedMessagePayload> {
        if (state.sendSeq == Long.MAX_VALUE) throw SessionBrickedException("sequence number overflow")

        val step = kdfCk(state.sendChainKey)
        val seq = state.sendSeq + 1
        val dhPub = state.localDhPub

        // AAD directionality: include sender and recipient fingerprints explicitly.
        val (sender, recipient) = if (state.isAlice) state.aliceFp to state.bobFp else state.bobFp to state.aliceFp
        val aad = buildMessageAad(sender, recipient, seq, dhPub)
        val plaintext = padToFixedSize(encodeMessage(payload), PADDING_SIZE)
        val ct = aeadEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
                needsRatchet = false,
            )

        // Seal the metadata into an envelope (#186)
        val envelope = encryptHeader(state.sendHeaderKey, dhPub, seq, state.pn)

        // Memory Hygiene
        state.sendChainKey.zeroize()
        step.messageKey.zeroize()
        step.messageNonce.zeroize()
        plaintext.zeroize()

        val message =
            EncryptedMessagePayload(
                v = PROTOCOL_VERSION,
                envelope = envelope,
                ct = ct,
            )

        return newState to message
    }

    @Throws(WhereException::class)
    fun decryptMessage(
        state: SessionState,
        message: EncryptedMessagePayload,
    ): Pair<SessionState, MessagePlaintext> {
        // 0. Purge stale message keys (#189)
        val now = currentTimeMillis()
        val cleanSkippedKeys = LinkedHashMap(state.skippedMessageKeys)
        var modified = false
        val it = cleanSkippedKeys.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val v = entry.value
            // Format: [MK (32) || Nonce (12) || PN (8) || Timestamp (8)]
            if (v.size >= 60 && (now - bytesToLong(v.copyOfRange(52, 60))) > MAX_KEY_AGE_MS) {
                v.zeroize()
                it.remove()
                modified = true
            }
        }
        val cleanState = if (modified) state.copy(skippedMessageKeys = cleanSkippedKeys) else state

        // 1. Unwrap the envelope to reveal metadata (#186)
        val header =
            try {
                decryptHeader(cleanState.headerKey, message.envelope)
            } catch (_: Exception) {
                // If current headerKey fails, Alice might have ratcheted DH. Try nextHeaderKey.
                try {
                    decryptHeader(cleanState.nextHeaderKey, message.envelope)
                } catch (e: Exception) {
                    throw AuthenticationException("envelope decryption failed — incorrect key or corrupted metadata", e)
                }
            }

        val remoteDhPub = header.dhPub
        val isNewDhEpoch = !remoteDhPub.contentEquals(cleanState.remoteDhPub)
        val seq = header.seq
        val cacheKey = remoteDhPub.toHex() + "_" + seq

        // 2. Check skipped message key cache first
        val cachedKey = cleanState.skippedMessageKeys[cacheKey]
        if (cachedKey != null) {
            // AAD directionality: peer is the sender, I am the recipient.
            val (sender, recipient) =
                if (cleanState.isAlice) cleanState.bobFp to cleanState.aliceFp else cleanState.aliceFp to cleanState.bobFp

            // Cached key format ([§5.5]): [MK (32) || Nonce (12) || PN (8)]
            if (cachedKey.size < 52) throw ProtocolException("invalid cached key size in cache")
            val mk = cachedKey.copyOfRange(0, 32)
            val nonce = cachedKey.copyOfRange(32, 44)
            val cachedPn = bytesToLong(cachedKey.copyOfRange(44, 52))

            // Use the new buildMessageAad signature (pn is now inside the ciphertext)
            val aad = buildMessageAad(sender, recipient, seq, remoteDhPub)

            val plaintext =
                try {
                    aeadDecrypt(mk, nonce, message.ct, aad)
                } catch (e: Exception) {
                    throw AuthenticationException("decryption failed with cached key", e)
                } finally {
                    mk.zeroize()
                    nonce.zeroize()
                }

            val unpadded =
                try {
                    unpad(plaintext)
                } catch (e: Exception) {
                    plaintext.zeroize()
                    throw DecryptionException("unpad failed", e)
                }
            val decoded = decodeMessage(unpadded)
            plaintext.zeroize()
            unpadded.zeroize()

            // Multi-epoch safety: ensure internal 'pn' matches what we had in the cache
            if (decoded.pn != cachedPn) throw ProtocolException("message pn mismatch in cache")

            // Remove used key from cache
            val newCache = LinkedHashMap(cleanState.skippedMessageKeys)
            val removed = newCache.remove(cacheKey)
            removed?.zeroize()

            return cleanState.copy(skippedMessageKeys = newCache) to decoded
        }

        // 3. Speculatively perform DH and symmetric ratchet
        // We do NOT mutate the original 'state' or commit anything until decryption succeeds.
        var speculativeState = cleanState

        if (isNewDhEpoch) {
            // Unified error message to satisfy existing brittle test assertions (§9.2)
            if (remoteDhPub.contentEquals(cleanState.lastRemoteDhPub) || cleanState.seenRemoteDhPubs.contains(remoteDhPub.toHex())) {
                throw ProtocolException("replay: dhPub already superseded (out-of-order window closed)")
            }

            // Ratchet state forward. Note: headerKey transition happens inside performDhRatchet.
            speculativeState = performDhRatchet(speculativeState, remoteDhPub)
        }

        // Replay rejection against speculative seq
        if (seq <= speculativeState.recvSeq) {
            throw ProtocolException("replay: seq $seq <= recvSeq ${speculativeState.recvSeq}")
        }
        val stepsNeeded = seq - speculativeState.recvSeq
        if (stepsNeeded > MAX_GAP + 1) {
            throw ProtocolException("gap too large: stepsNeeded $stepsNeeded")
        }

        // Derivation loop for chain keys and skipped message keys
        val derivationSkippedKeys = LinkedHashMap(speculativeState.skippedMessageKeys)

        // If we entered a new DH epoch, standard DR says we should eventually clear
        // very old skipped keys. We'll clear keys belonging to epochs older than 'lastRemoteDhPub'.
        if (isNewDhEpoch) {
            val validEpochs = mutableSetOf(remoteDhPub.toHex(), cleanState.remoteDhPub.toHex())
            derivationSkippedKeys.keys.retainAll { k -> validEpochs.any { e -> k.startsWith(e) } }
        }

        val pnGaps =
            if (isNewDhEpoch && header.pn > cleanState.recvSeq) {
                (header.pn - cleanState.recvSeq).toInt()
            } else {
                0
            }

        val projectedSize = derivationSkippedKeys.size + kotlin.math.max(0, stepsNeeded.toInt() - 1) + pnGaps
        if (projectedSize > MAX_SKIPPED_KEYS) {
            throw ProtocolException("combined skipped key gap too large: $projectedSize")
        }

        var chainKey = speculativeState.recvChainKey.copyOf()
        var currentStep: ChainStep? = null

        try {
            // Advance the chain to 'seq', storing intermediate message keys
            repeat(stepsNeeded.toInt()) { i ->
                val step = kdfCk(chainKey)
                // Zero OLD chain key immediately after step
                chainKey.zeroize()
                chainKey = step.newChainKey

                val currentSeq = speculativeState.recvSeq + i + 1
                if (currentSeq < seq) {
                    // Store intermediate message key for future out-of-order delivery.
                    // Cached format: [MK (32) || Nonce (12) || PN (8) || Timestamp (8)]
                    // We store 'speculativeState.pr' which is the 'pn' for CURRENT epoch messages.
                    val mkPlusNonce = step.messageKey + step.messageNonce + longToBeBytes(speculativeState.pr) + longToBeBytes(now)
                    val mkKey = remoteDhPub.toHex() + "_" + currentSeq
                    derivationSkippedKeys[mkKey] = mkPlusNonce
                    // Limit cache size with deterministic FIFO (LinkedHashMap order)
                    if (derivationSkippedKeys.size > MAX_SKIPPED_KEYS) {
                        val oldestKey = derivationSkippedKeys.keys.first()
                        // Memory Hygiene: Explicitly zero the evicted key before GC
                        derivationSkippedKeys[oldestKey]?.zeroize()
                        derivationSkippedKeys.remove(oldestKey)
                    }
                } else {
                    currentStep = step
                }
            }

            val finalStep = currentStep ?: throw ProtocolException("failed to derive message key")
            // AAD directionality: peer is the sender, I am the recipient.
            val (sender, recipient) =
                if (cleanState.isAlice) cleanState.bobFp to cleanState.aliceFp else cleanState.aliceFp to cleanState.bobFp
            val aad = buildMessageAad(sender, recipient, seq, remoteDhPub)

            val plaintext =
                try {
                    aeadDecrypt(finalStep.messageKey, finalStep.messageNonce, message.ct, aad)
                } catch (e: Exception) {
                    // Decryption failure: Wipe all speculative keys and throw
                    if (isNewDhEpoch) {
                        speculativeState.localDhPriv.zeroize()
                        speculativeState.rootKey.zeroize()
                    }
                    // Also wipe the derived chainKey on failure since we won't commit it
                    chainKey.zeroize()
                    throw AuthenticationException("decryption failed", e)
                } finally {
                    finalStep.messageKey.zeroize()
                    finalStep.messageNonce.zeroize()
                }

            // Success! Commit the state.

            val unpadded =
                try {
                    unpad(plaintext)
                } catch (e: Exception) {
                    plaintext.zeroize()
                    throw DecryptionException("unpad failed", e)
                }
            val decoded = decodeMessage(unpadded)
            plaintext.zeroize()
            unpadded.zeroize()

            // PH-3.3: Post-decryption gap filling for the PREVIOUS receiving chain.
            // If we are in a new DH epoch, 'decoded.pn' tells us how many messages Bob sent
            // in the epoch before this one. We only skip them if we haven't already.
            val finalSkippedKeys =
                if (isNewDhEpoch && decoded.pn > cleanState.recvSeq) {
                    val pnGaps = (decoded.pn - cleanState.recvSeq).toInt()

                    val updatedCache = LinkedHashMap(derivationSkippedKeys)
                    var oldChainKey = cleanState.recvChainKey.copyOf()
                    repeat(pnGaps) { i ->
                        val step = kdfCk(oldChainKey)
                        oldChainKey.zeroize()
                        oldChainKey = step.newChainKey

                        val skippedSeq = cleanState.recvSeq + i + 1
                        val mkKey = cleanState.remoteDhPub.toHex() + "_" + skippedSeq
                        // Skip keys are stored with the 'pn' of THEIR epoch (state.pr) and current timestamp
                        updatedCache[mkKey] = step.messageKey + step.messageNonce + longToBeBytes(cleanState.pr) + longToBeBytes(now)

                        if (updatedCache.size > MAX_SKIPPED_KEYS) {
                            updatedCache.remove(updatedCache.keys.first())?.zeroize()
                        }
                    }
                    oldChainKey.zeroize()
                    updatedCache
                } else {
                    derivationSkippedKeys
                }

            val newState =
                speculativeState.copy(
                    rootKey = speculativeState.rootKey.copyOf(),
                    sendChainKey = speculativeState.sendChainKey.copyOf(),
                    headerKey = speculativeState.headerKey.copyOf(),
                    nextHeaderKey = speculativeState.nextHeaderKey.copyOf(),
                    recvChainKey = chainKey,
                    recvSeq = seq,
                    skippedMessageKeys = finalSkippedKeys,
                    needsRatchet = isNewDhEpoch,
                    seenRemoteDhPubs =
                        if (isNewDhEpoch) {
                            (speculativeState.seenRemoteDhPubs + cleanState.remoteDhPub.toHex()).toList().takeLast(MAX_SEEN_DH_PUBS).toSet()
                        } else {
                            speculativeState.seenRemoteDhPubs
                        },
                )

            // Memory Hygiene
            if (isNewDhEpoch) {
                cleanState.localDhPriv.zeroize()
                cleanState.rootKey.zeroize()
                cleanState.sendChainKey.zeroize()
                cleanState.recvChainKey.zeroize()
            }

            return newState to decoded
        } catch (e: Exception) {
            // This catch handles any errors between derivation and final commit.
            // (Most errors are already caught and re-thrown inside the inner catch)
            if (e !is WhereException) {
                // Wipe chainKey if we crashed unexpectedly
                chainKey.zeroize()
            }
            throw e
        }
    }

    /**
     * Perform the DH ratchet step (pure computation).
     * Returns a new SessionState with updated DH keys and tokens.
     * Does NOT zero the input state's private key (caller must do so after commit).
     */
    private fun performDhRatchet(
        state: SessionState,
        remoteDhPub: ByteArray,
    ): SessionState {
        // DH ratchet step
        val dhOutRecv = x25519(state.localDhPriv, remoteDhPub)
        val stepRecv = kdfRk(state.rootKey, dhOutRecv)
        dhOutRecv.zeroize()

        val newLocalDh = generateX25519KeyPair()
        val dhOutSend = x25519(newLocalDh.priv, remoteDhPub)
        val stepSend = kdfRk(stepRecv.newRootKey, dhOutSend)
        dhOutSend.zeroize()

        // Derive new tokens
        val aliceFp = state.aliceFp
        val bobFp = state.bobFp

        val newRecvToken =
            if (state.isAlice) {
                deriveRoutingToken(stepRecv.newRootKey, bobFp, aliceFp)
            } else {
                deriveRoutingToken(stepRecv.newRootKey, aliceFp, bobFp)
            }
        val newSendToken =
            if (state.isAlice) {
                deriveRoutingToken(stepSend.newRootKey, aliceFp, bobFp)
            } else {
                deriveRoutingToken(stepSend.newRootKey, bobFp, aliceFp)
            }

        // Track seen DH keys to avoid re-ratcheting to old epochs
        val newSeenKeys = LinkedHashSet(state.seenRemoteDhPubs)
        newSeenKeys.add(state.remoteDhPub.toHex())
        if (newSeenKeys.size > MAX_SEEN_DH_PUBS) {
            val oldest = newSeenKeys.first()
            newSeenKeys.remove(oldest)
        }

        stepRecv.newRootKey.zeroize()

        // HEADER ENCRYPTION TRANSITION (#186):
        // When we ratchet, our previous nextHeaderKey becomes the current headerKey
        // for the epoch we just entered. The nextHeaderKey is derived for the subsequent epoch.
        return state.copy(
            rootKey = stepSend.newRootKey,
            recvChainKey = stepRecv.newChainKey,
            sendChainKey = stepSend.newChainKey,
            headerKey = state.nextHeaderKey.copyOf(),
            sendHeaderKey = stepRecv.newHeaderKey,
            nextHeaderKey = stepSend.newHeaderKey,
            sendToken = newSendToken,
            recvToken = newRecvToken,
            sendSeq = 0L,
            recvSeq = 0L,
            pn = state.sendSeq,
            pr = state.recvSeq,
            localDhPriv = newLocalDh.priv,
            localDhPub = newLocalDh.pub,
            remoteDhPub = remoteDhPub.copyOf(),
            lastRemoteDhPub = state.remoteDhPub.copyOf(),
            seenRemoteDhPubs = newSeenKeys,
            prevSendToken = state.sendToken.copyOf(),
            isSendTokenPending = true,
        )
    }

    internal fun encryptHeader(
        key: ByteArray,
        dhPub: ByteArray,
        seq: Long,
        pn: Long,
    ): ByteArray {
        val plaintext = ByteArray(1 + 32 + 8 + 8)
        plaintext[0] = PROTOCOL_VERSION.toByte()
        dhPub.copyInto(plaintext, 1)
        longToBeBytes(seq).copyInto(plaintext, 33)
        longToBeBytes(pn).copyInto(plaintext, 41)

        val nonce = randomBytes(HEADER_NONCE_SIZE)
        val ct = aeadEncrypt(key, nonce, plaintext, aad = ByteArray(0))
        return nonce + ct
    }

    internal data class DecryptedHeader(val dhPub: ByteArray, val seq: Long, val pn: Long)

    internal fun decryptHeader(
        key: ByteArray,
        envelope: ByteArray,
    ): DecryptedHeader {
        if (envelope.size < HEADER_NONCE_SIZE + HEADER_TAG_SIZE + 49) {
            throw ProtocolException("header envelope too small")
        }
        val nonce = envelope.copyOfRange(0, HEADER_NONCE_SIZE)
        val ct = envelope.copyOfRange(HEADER_NONCE_SIZE, envelope.size)
        val plaintext =
            try {
                aeadDecrypt(key, nonce, ct, aad = ByteArray(0))
            } catch (e: Exception) {
                throw AuthenticationException("header decryption failed — signature mismatch or corrupted data", e)
            }

        if (plaintext[0].toInt() != PROTOCOL_VERSION) {
            throw ProtocolException("unsupported protocol version in header: ${plaintext[0].toInt()}")
        }

        val dhPub = plaintext.copyOfRange(1, 33)
        val seq = bytesToLong(plaintext.copyOfRange(33, 41))
        val pn = bytesToLong(plaintext.copyOfRange(41, 49))
        return DecryptedHeader(dhPub, seq, pn)
    }

    private fun buildMessageAad(
        senderFp: ByteArray,
        recipientFp: ByteArray,
        seq: Long,
        dhPub: ByteArray,
    ): ByteArray {
        return AAD_PREFIX.encodeToByteArray() +
            intToBeBytes(PROTOCOL_VERSION) +
            senderFp +
            recipientFp +
            longToBeBytes(seq) +
            dhPub
    }

    private fun encodeMessage(msg: MessagePlaintext): ByteArray =
        buildJsonObject {
            when (msg) {
                is MessagePlaintext.Location -> {
                    put("lat", msg.lat)
                    put("lng", msg.lng)
                    put("acc", msg.acc)
                    put("ts", msg.ts)
                    put("precision", msg.precision.name)
                }
                is MessagePlaintext.Keepalive -> {
                    // empty object
                }
            }
            put("pn", msg.pn)
        }.let { Json.encodeToString(it) }.encodeToByteArray()

    private fun decodeMessage(bytes: ByteArray): MessagePlaintext {
        val obj = Json.decodeFromString<JsonObject>(bytes.decodeToString())
        val pn = obj["pn"]?.jsonPrimitive?.long ?: 0L
        return if (obj.containsKey("lat")) {
            MessagePlaintext.Location(
                lat = obj["lat"]!!.jsonPrimitive.double,
                lng = obj["lng"]!!.jsonPrimitive.double,
                acc = obj["acc"]!!.jsonPrimitive.double,
                ts = obj["ts"]!!.jsonPrimitive.long,
                precision = obj["precision"]?.jsonPrimitive?.content?.let { LocationPrecision.valueOf(it) } ?: LocationPrecision.FINE,
                pn = pn,
            )
        } else {
            MessagePlaintext.Keepalive(pn = pn)
        }
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
    abstract val pn: Long

    data class Location(
        val lat: Double,
        val lng: Double,
        val acc: Double,
        val ts: Long,
        val precision: LocationPrecision = LocationPrecision.FINE,
        override val pn: Long = 0,
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

    data class Keepalive(override val pn: Long = 0) : MessagePlaintext()
}

class SessionBrickedException(message: String) : WhereException(message)
