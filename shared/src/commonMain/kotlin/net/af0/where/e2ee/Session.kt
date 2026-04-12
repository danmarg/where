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
    private const val AAD_PREFIX = "Where-v1-Message"
    private const val PROTOCOL_VERSION = 1

    internal const val PADDING_SIZE = 512
    private const val MAX_GAP = 1024L

    fun encryptMessage(
        state: SessionState,
        payload: MessagePlaintext,
    ): Pair<SessionState, EncryptedMessagePayload> {
        require(state.sendSeq != Long.MAX_VALUE) { "sequence number overflow" }

        val step = kdfCk(state.sendChainKey)
        val seq = state.sendSeq + 1
        val dhPub = state.localDhPub
        
        // AAD ordering: always (AliceFp, BobFp)
        val aad = buildMessageAad(state.aliceFp, state.bobFp, seq, dhPub)
        val plaintext = padToFixedSize(encodeMessage(payload), PADDING_SIZE)
        val ct = aeadEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
                needsRatchet = false, // We've sent at least one message in this epoch
            )

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
            val aad = buildMessageAad(state.aliceFp, state.bobFp, seq, remoteDhPub)
            // Note: In a production DR implementation, nonces for skipped keys must be 
            // recovered/managed correctly. For simplicity here, we assume the message 
            // contains enough info to re-derive or it is stored alongside the key.
            // (Standard DR usually stores [key || nonce] or derives nonce from seq).
            // Our kdfCk produces a unique nonce per step. 
            // To simplify, we'll implement full Signal-style key derivation including nonces 
            // if we were storing them. For now, we'll try to decrypt.
            
            // FIXME: Nonce management for cached keys. 
            // Standard DR: nonce is either part of the cached key or implicit in seq.
            // Our current kdfCk produces 76 bytes: [CK || MK || Nonce]. 
            // Let's assume the cached value is [MK (32) || Nonce (12)].
            if (cachedKey.size < 44) throw ProtocolException("invalid cached key size")
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
            val newCache = state.skippedMessageKeys.toMutableMap()
            newCache.remove(cacheKey)
            
            return state.copy(skippedMessageKeys = newCache) to decoded
        }

        // 2. Reject replays from already seen/processed epochs
        if (remoteDhPub.contentEquals(state.lastRemoteDhPub)) {
            throw ProtocolException("replay: dhPub matched previous epoch (across-epoch replay)")
        }
        if (state.seenRemoteDhPubs.any { it.contentEquals(remoteDhPub) }) {
            throw ProtocolException("replay: dhPub has already been superseded (old epoch)")
        }

        val isNewDhEpoch = !remoteDhPub.contentEquals(state.remoteDhPub)
        
        // 3. Speculatively perform DH and symmetric ratchet
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
        var chainKey = speculativeState.recvChainKey.copyOf()
        val newSkippedKeys = speculativeState.skippedMessageKeys.toMutableMap()
        var step: ChainStep? = null
        
        // Advance the chain to 'seq', storing intermediate message keys
        repeat(stepsNeeded.toInt()) { i ->
            step = kdfCk(chainKey)
            chainKey.fill(0)
            chainKey = step!!.newChainKey
            
            val currentSeq = speculativeState.recvSeq + i + 1
            if (currentSeq < seq) {
                // Store intermediate message key for future out-of-order delivery
                val mkPlusNonce = step!!.messageKey + step!!.messageNonce
                val mkKey = remoteDhPub.toHex() + "_" + currentSeq
                newSkippedKeys[mkKey] = mkPlusNonce
                // Limit cache size
                if (newSkippedKeys.size > 100) {
                    val oldestKey = newSkippedKeys.keys.first()
                    newSkippedKeys.remove(oldestKey)
                }
            }
        }
        val finalStep = step!!
        val finalRecvChainKey = chainKey

        // 4. Decrypt and verify
        val aad = buildMessageAad(state.aliceFp, state.bobFp, seq, remoteDhPub)
        val plaintext =
            try {
                aeadDecrypt(finalStep.messageKey, finalStep.messageNonce, message.ct, aad)
            } catch (e: Exception) {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
                finalRecvChainKey.fill(0)
                // If this was a new VH epoch, performDhRatchet generated keys we must wipe
                if (isNewDhEpoch) {
                    speculativeState.localDhPriv.fill(0)
                    speculativeState.rootKey.fill(0)
                    speculativeState.recvChainKey.fill(0)
                    speculativeState.sendChainKey.fill(0)
                }
                throw AuthenticationException("decryption failed", e)
            }
        
        // 5. Success! Finalize state.
        finalStep.messageKey.fill(0)
        finalStep.messageNonce.fill(0)
        
        val newState = speculativeState.copy(
            recvChainKey = finalRecvChainKey,
            recvSeq = seq,
            skippedMessageKeys = newSkippedKeys,
        )

        val unpadded =
            try {
                unpad(plaintext)
            } catch (e: Exception) {
                plaintext.fill(0)
                throw DecryptionException("unpadding failed", e)
            }

        val decoded = decodeMessage(unpadded)
        plaintext.fill(0); unpadded.fill(0)

        return newState to decoded
    }

    /**
     * Perform the DH ratchet step (speculative).
     * Returns a new SessionState with updated DH keys and tokens.
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
        val newSeenKeys = state.seenRemoteDhPubs.toMutableList()
        newSeenKeys.add(state.remoteDhPub.copyOf())
        if (newSeenKeys.size > 20) newSeenKeys.removeAt(0)

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
            localDhPriv = newLocalDh.priv,
            localDhPub = newLocalDh.pub,
            remoteDhPub = remoteDhPub.copyOf(),
            lastRemoteDhPub = state.remoteDhPub.copyOf(),
            seenRemoteDhPubs = newSeenKeys,
            prevSendToken = state.sendToken.copyOf(),
            isSendTokenPending = true,
            needsRatchet = true, // Peer ratcheted us, we MUST ratchet our send chain back.
        )
    }

    private fun buildMessageAad(
        aliceFp: ByteArray,
        bobFp: ByteArray,
        seq: Long,
        dhPub: ByteArray,
    ): ByteArray {
        // AAD ordering: always (AliceFp, BobFp) regardless of who is sender.
        return AAD_PREFIX.encodeToByteArray() +
            intToBeBytes(PROTOCOL_VERSION) +
            aliceFp +
            bobFp +
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

    private fun padToFixedSize(
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

    private fun unpad(data: ByteArray): ByteArray {
        require(data.size >= 2) { "padded data too small" }
        val padByte = data[data.size - 1].toInt() and 0xFF
        val padHighByte = data[data.size - 2].toInt() and 0xFF
        val padCount = (padHighByte shl 8) or padByte

        require(padCount >= 2 && padCount <= data.size) { "invalid padding count: $padCount" }
        for (i in data.size - padCount until data.size - 2) {
            require(data[i].toInt() and 0xFF == padByte) { "padding corruption at index $i" }
        }
        return data.copyOfRange(0, data.size - padCount)
    }
}

sealed class MessagePlaintext {
    data class Location(val lat: Double, val lng: Double, val acc: Double, val ts: Long) : MessagePlaintext()
    object Keepalive : MessagePlaintext()
}
