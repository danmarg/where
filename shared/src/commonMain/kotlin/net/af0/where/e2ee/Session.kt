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
    private const val PROTOCOL_VERSION = 1

    fun encryptMessage(
        state: SessionState,
        payload: MessagePlaintext,
    ): Pair<SessionState, EncryptedMessagePayload> {
        require(state.sendSeq != Long.MAX_VALUE) { "sequence number overflow" }

        val step = kdfCk(state.sendChainKey)
        val seq = state.sendSeq + 1
        val dhPub = state.localDhPub
        
        // AAD directionality: include sender and recipient fingerprints explicitly.
        val (sender, recipient) = if (state.isAlice) state.aliceFp to state.bobFp else state.bobFp to state.aliceFp
        val aad = buildMessageAad(sender, recipient, seq, dhPub, state.pn)
        val plaintext = padToFixedSize(encodeMessage(payload), PADDING_SIZE)
        val ct = aeadEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
                needsRatchet = false, // We've sent at least one message in this epoch
            )

        // Memory Hygiene: Wipe the OLD chain key now that it is superseded
        state.sendChainKey.fill(0)

        step.messageKey.fill(0)
        step.messageNonce.fill(0)
        plaintext.fill(0)

        val message =
            EncryptedMessagePayload(
                dhPub = dhPub,
                seq = seq.toString(),
                ct = ct,
            )

        return newState to message
    }

    @Throws(WhereException::class)
    fun decryptMessage(
        state: SessionState,
        message: EncryptedMessagePayload,
    ): Pair<SessionState, MessagePlaintext> {
        val remoteDhPub = message.dhPub
        val seq = message.seqAsLong()
        val cacheKey = remoteDhPub.toHex() + "_" + seq

        // 1. Check skipped message key cache first (out-of-order within prior epochs)
        val cachedKey = state.skippedMessageKeys[cacheKey]
        if (cachedKey != null) {
            // AAD directionality: peer is the sender, I am the recipient.
            val (sender, recipient) = if (state.isAlice) state.bobFp to state.aliceFp else state.aliceFp to state.bobFp
            val aad = buildMessageAad(sender, recipient, seq, remoteDhPub, state.pr)
            
            // Cached key format: [MK (32) || Nonce (12)]
            if (cachedKey.size < 44) throw ProtocolException("invalid cached key size in cache")
            val mk = cachedKey.copyOfRange(0, 32)
            val nonce = cachedKey.copyOfRange(32, 44)
            
            val plaintext = try {
                aeadDecrypt(mk, nonce, message.ct, aad)
            } catch (e: Exception) {
                throw AuthenticationException("decryption failed with cached key", e)
            } finally {
                mk.fill(0); nonce.fill(0)
            }
            
            val unpadded = try { unpad(plaintext) } catch (e: Exception) { 
                plaintext.fill(0); throw DecryptionException("unpad failed", e) 
            }
            val decoded = decodeMessage(unpadded)
            plaintext.fill(0); unpadded.fill(0)

            // Remove used key from cache
            val newCache = LinkedHashMap(state.skippedMessageKeys)
            val removed = newCache.remove(cacheKey)
            // Memory Hygiene: Explicitly zero the combined MK || Nonce upon cache hit (§5.5)
            removed?.fill(0)
            
            return state.copy(skippedMessageKeys = newCache) to decoded
        }

        // 2. Reject replays from already seen/processed epochs
        if (remoteDhPub.contentEquals(state.lastRemoteDhPub)) {
            throw ProtocolException("replay: dhPub matched previous epoch (across-epoch replay)")
        }
        if (state.seenRemoteDhPubs.contains(remoteDhPub.toHex())) {
            throw ProtocolException("replay: dhPub has already been superseded (old epoch)")
        }

        val isNewDhEpoch = !remoteDhPub.contentEquals(state.remoteDhPub)
        
        // 3. Speculatively perform DH and symmetric ratchet
        // We do NOT mutate the original 'state' or commit anything until decryption succeeds.
        var speculativeState = state
        if (isNewDhEpoch) {
            speculativeState = performDhRatchet(state, remoteDhPub)
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
        val newSkippedKeys = LinkedHashMap(speculativeState.skippedMessageKeys)
        
        // If we entered a new DH epoch, standard DR says we should eventually clear 
        // very old skipped keys. We'll clear keys belonging to epochs older than 'lastRemoteDhPub'.
        if (isNewDhEpoch) {
            val validEpochs = mutableSetOf(remoteDhPub.toHex(), state.remoteDhPub.toHex())
            newSkippedKeys.keys.retainAll { k -> validEpochs.any { e -> k.startsWith(e) } }
        }

        var chainKey = speculativeState.recvChainKey.copyOf()
        var currentStep: ChainStep? = null
        
        try {
            // Advance the chain to 'seq', storing intermediate message keys
            repeat(stepsNeeded.toInt()) { i ->
                val step = kdfCk(chainKey)
                // Zero OLD chain key immediately after step
                chainKey.fill(0)
                chainKey = step.newChainKey
                
                val currentSeq = speculativeState.recvSeq + i + 1
                if (currentSeq < seq) {
                    // Store intermediate message key for future out-of-order delivery
                    val mkPlusNonce = step.messageKey + step.messageNonce
                    val mkKey = remoteDhPub.toHex() + "_" + currentSeq
                    newSkippedKeys[mkKey] = mkPlusNonce
                    
                    // Limit cache size with deterministic FIFO (LinkedHashMap order)
                    if (newSkippedKeys.size > MAX_SKIPPED_KEYS) {
                        val oldestKey = newSkippedKeys.keys.first()
                        // Memory Hygiene: Explicitly zero the evicted key before GC
                        newSkippedKeys[oldestKey]?.fill(0)
                        newSkippedKeys.remove(oldestKey)
                    }
                } else {
                    currentStep = step
                }
            }

            val finalStep = currentStep ?: throw ProtocolException("failed to derive message key")
            // AAD directionality: peer is the sender, I am the recipient.
            val (sender, recipient) = if (state.isAlice) state.bobFp to state.aliceFp else state.aliceFp to state.bobFp
            val aad = buildMessageAad(sender, recipient, seq, remoteDhPub, speculativeState.pr)
            
            val plaintext = try {
                aeadDecrypt(finalStep.messageKey, finalStep.messageNonce, message.ct, aad)
            } catch (e: Exception) {
                // Decryption failure: Wipe all speculative keys and throw
                if (isNewDhEpoch) {
                    speculativeState.localDhPriv.fill(0)
                    speculativeState.rootKey.fill(0)
                }
                // Also wipe the derived chainKey on failure since we won't commit it
                chainKey.fill(0)
                throw AuthenticationException("decryption failed", e)
            } finally {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
            }

            // Success! Commit the state.
            val newState = speculativeState.copy(
                recvChainKey = chainKey,
                recvSeq = seq,
                skippedMessageKeys = newSkippedKeys,
            )

            // Memory Hygiene: Wipe the OLD ratchet keys now that they are superseded
            if (isNewDhEpoch) {
                state.localDhPriv.fill(0)
                state.rootKey.fill(0)
                state.sendChainKey.fill(0)
                state.recvChainKey.fill(0)
            }

            val unpadded = try { unpad(plaintext) } catch (e: Exception) { 
                plaintext.fill(0); throw DecryptionException("unpad failed", e) 
            }
            val decoded = decodeMessage(unpadded)
            plaintext.fill(0); unpadded.fill(0)

            return newState to decoded

        } catch (e: Exception) {
            // This catch handles any errors between derivation and final commit.
            // (Most errors are already caught and re-thrown inside the inner catch)
            if (e !is WhereException) {
                // Wipe chainKey if we crashed unexpectedly
                chainKey.fill(0)
            }
            throw e
        }
    }

    /**
     * Perform the DH ratchet step (pure computation).
     * Returns a new SessionState with updated DH keys and tokens.
     * Does NOT zero the input state's private key (caller must do so after commit).
     */
    private fun performDhRatchet(state: SessionState, remoteDhPub: ByteArray): SessionState {
        // DH ratchet step
        val dhOutRecv = x25519(state.localDhPriv, remoteDhPub)
        val stepRecv = kdfRk(state.rootKey, dhOutRecv)
        dhOutRecv.fill(0)

        val newLocalDh = generateX25519KeyPair()
        val dhOutSend = x25519(newLocalDh.priv, remoteDhPub)
        val stepSend = kdfRk(stepRecv.newRootKey, dhOutSend)
        dhOutSend.fill(0)

        // Derive new tokens
        val aliceFp = state.aliceFp
        val bobFp = state.bobFp

        val newRecvToken = if (state.isAlice) {
            deriveRoutingToken(stepRecv.newRootKey, bobFp, aliceFp)
        } else {
            deriveRoutingToken(stepRecv.newRootKey, aliceFp, bobFp)
        }
        val newSendToken = if (state.isAlice) {
            deriveRoutingToken(stepSend.newRootKey, aliceFp, bobFp)
        } else {
            deriveRoutingToken(stepSend.newRootKey, bobFp, aliceFp)
        }

        // Track seen DH keys to avoid re-ratcheting to old epochs
        val newSeenKeys = LinkedHashSet(state.seenRemoteDhPubs)
        newSeenKeys.add(state.remoteDhPub.toHex())
        if (newSeenKeys.size > 100) {
            val oldest = newSeenKeys.first()
            newSeenKeys.remove(oldest)
        }

        // NONCE UNIQUENESS (§5.4): Resetting sendSeq to 0 is safe because KDF_RK
        // includes the unique DH shared secret, ensuring the new rootKey and 
        // subsequent chain keys (and thus nonces) are unique to this epoch.
        // We stash PN (§4.4) to bind the AAD across epochs.
        // Zero intermediate keys
        stepRecv.newRootKey.fill(0)

        return state.copy(
            rootKey = stepSend.newRootKey,
            recvChainKey = stepRecv.newChainKey,
            sendChainKey = stepSend.newChainKey,
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

    private fun buildMessageAad(
        senderFp: ByteArray,
        recipientFp: ByteArray,
        seq: Long,
        dhPub: ByteArray,
        pn: Long,
    ): ByteArray {
        return AAD_PREFIX.encodeToByteArray() +
            intToBeBytes(PROTOCOL_VERSION) +
            senderFp +
            recipientFp +
            longToBeBytes(seq) +
            longToBeBytes(pn) +
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
                }
                is MessagePlaintext.Keepalive -> {
                    // empty object
                }
            }
        }.let { Json.encodeToString(it) }.encodeToByteArray()

    private fun decodeMessage(bytes: ByteArray): MessagePlaintext {
        val obj = Json.decodeFromString<JsonObject>(bytes.decodeToString())
        return if (obj.containsKey("lat")) {
            MessagePlaintext.Location(
                lat = obj["lat"]!!.jsonPrimitive.double,
                lng = obj["lng"]!!.jsonPrimitive.double,
                acc = obj["acc"]!!.jsonPrimitive.double,
                ts = obj["ts"]!!.jsonPrimitive.long,
            )
        } else {
            MessagePlaintext.Keepalive
        }
    }

    internal fun padToFixedSize(
        data: ByteArray,
        size: Int,
    ): ByteArray {
        require(data.size > 0) { "plaintext must not be empty" }
        require(data.size <= size - 2) { "plaintext (${data.size} bytes) too large for PADDING_SIZE $size" }

        val padCount = size - data.size
        val padByte = (padCount and 0xFF).toByte()
        val padHighByte = ((padCount shr 8) and 0xFF).toByte()

        return data.copyOf(size).also { padded ->
            for (i in data.size until size - 2) padded[i] = padByte
            padded[size - 2] = padHighByte
            padded[size - 1] = padByte
        }
    }

    internal fun unpad(data: ByteArray): ByteArray {
        require(data.size >= 2) { "padded data too small" }
        val padByte = data[data.size - 1].toInt() and 0xFF
        val padHighByte = data[data.size - 2].toInt() and 0xFF
        val padCount = (padHighByte shl 8) or padByte

        require(padCount >= 2 && padCount <= data.size) { "invalid padding count: $padCount" }
        
        // Constant-time full block validation: ensure all padding bytes match the length byte.
        // We use an xor accumulator to detect any mismatch without early-exit.
        var diff = 0
        for (i in data.size - padCount until data.size - 2) {
            diff = diff or (data[i].toInt() xor padByte)
        }
        
        if ((diff and 0xFF) != 0) {
            data.fill(0)
            throw ProtocolException("padding corruption")
        }
        
        return data.copyOfRange(0, data.size - padCount)
    }
}

sealed class MessagePlaintext {
    data class Location(val lat: Double, val lng: Double, val acc: Double, val ts: Long) : MessagePlaintext()
    object Keepalive : MessagePlaintext()
}
