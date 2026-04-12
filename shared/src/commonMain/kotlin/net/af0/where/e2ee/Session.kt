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

    /**
     * Encrypt a message (Location or Keepalive) for a single peer.
     *
     * @param state    Current session state.
     * @param payload  Plaintext message to encrypt.
     * @return Updated state and EncryptedMessagePayload.
     */
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

    /**
     * Decrypt one incoming message. Handles DH ratchet advancement if needed.
     *
     * @param state   Current session state.
     * @param message Encrypted message from the peer.
     * @return Updated state and decrypted message.
     */
    @Throws(WhereException::class)
    fun decryptMessage(
        state: SessionState,
        message: EncryptedMessagePayload,
    ): Pair<SessionState, MessagePlaintext> {
        val remoteDhPub = message.dhPub
        if (remoteDhPub.contentEquals(state.lastRemoteDhPub)) {
            throw ProtocolException("replay: dhPub matched previous epoch (across-epoch replay)")
        }
        val isNewDhEpoch = !remoteDhPub.contentEquals(state.remoteDhPub)
        val seq = message.seqAsLong()

        // 1. Speculatively compute the ratchet pieces
        var currentRootKey = state.rootKey
        var currentRecvChainKey = state.recvChainKey
        var currentSendChainKey = state.sendChainKey
        var currentSendToken = state.sendToken
        var currentRecvToken = state.recvToken
        var currentSendSeq = state.sendSeq
        var currentRecvSeq = state.recvSeq
        var currentLocalDhPriv = state.localDhPriv
        var currentLocalDhPub = state.localDhPub
        var currentRemoteDhPub = state.remoteDhPub
        var currentLastRemoteDhPub = state.lastRemoteDhPub
        var currentPrevSendToken = state.prevSendToken
        var currentIsSendTokenPending = state.isSendTokenPending

        if (isNewDhEpoch) {
            // DH ratchet step
            val dhOutRecv = x25519(state.localDhPriv, remoteDhPub)
            val stepRecv = kdfRk(state.rootKey, dhOutRecv)
            dhOutRecv.fill(0)

            val newLocalDh = generateX25519KeyPair()
            val dhOutSend = x25519(newLocalDh.priv, remoteDhPub)
            val stepSend = kdfRk(stepRecv.newRootKey, dhOutSend)
            dhOutSend.fill(0)

            // Correctly derive new tokens using the spec-compliant root keys
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

            // Zero intermediate keys that won't be in the final state
            stepRecv.newRootKey.fill(0)

            currentRootKey = stepSend.newRootKey
            currentRecvChainKey = stepRecv.newChainKey
            currentSendChainKey = stepSend.newChainKey
            currentSendToken = newSendToken
            currentRecvToken = newRecvToken
            currentSendSeq = 0L
            currentRecvSeq = 0L
            currentLocalDhPriv = newLocalDh.priv
            currentLocalDhPub = newLocalDh.pub
            currentRemoteDhPub = remoteDhPub.copyOf()
            currentLastRemoteDhPub = state.remoteDhPub.copyOf()
            currentPrevSendToken = state.sendToken.copyOf()
            currentIsSendTokenPending = true
        }

        // 2. Replay rejection against speculative seq
        if (seq <= currentRecvSeq) {
            throw ProtocolException("replay: seq $seq <= recvSeq $currentRecvSeq")
        }
        val stepsNeeded = seq - currentRecvSeq
        if (stepsNeeded > MAX_GAP + 1) {
            throw ProtocolException("gap too large: stepsNeeded $stepsNeeded")
        }

        // 3. Speculatively derive the message key
        var chainKey = currentRecvChainKey.copyOf()
        var step: ChainStep? = null
        // Due to the MAX_GAP check above, we know stepsNeeded <= MAX_GAP, 
        // which fits comfortably within an Int, making the .toInt() cast safe.
        repeat(stepsNeeded.toInt()) {
            step = kdfCk(chainKey)
            chainKey.fill(0)
            chainKey = step!!.newChainKey
        }
        val finalStep = step!!
        val speculativeRecvChainKey = chainKey

        // 4. Decrypt and verify
        val aad = buildMessageAad(state.aliceFp, state.bobFp, seq, remoteDhPub)
        val plaintext =
            try {
                aeadDecrypt(finalStep.messageKey, finalStep.messageNonce, message.ct, aad)
            } catch (e: Exception) {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
                speculativeRecvChainKey.fill(0)
                if (isNewDhEpoch) {
                    currentRootKey.fill(0)
                    currentRecvChainKey.fill(0)
                    currentSendChainKey.fill(0)
                    currentLocalDhPriv.fill(0)
                }
                throw AuthenticationException("decryption failed", e)
            }
        
        // 5. Success! Commit the state changes and zero message keys.
        finalStep.messageKey.fill(0)
        finalStep.messageNonce.fill(0)
        
        val newState = state.copy(
            rootKey = currentRootKey,
            sendChainKey = currentSendChainKey,
            recvChainKey = speculativeRecvChainKey,
            sendToken = currentSendToken,
            recvToken = currentRecvToken,
            sendSeq = currentSendSeq,
            recvSeq = seq,
            localDhPriv = currentLocalDhPriv,
            localDhPub = currentLocalDhPub,
            remoteDhPub = currentRemoteDhPub,
            lastRemoteDhPub = currentLastRemoteDhPub,
            prevSendToken = currentPrevSendToken,
            isSendTokenPending = currentIsSendTokenPending,
        )

        val unpadded =
            try {
                unpad(plaintext)
            } catch (e: Exception) {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
                plaintext.fill(0)
                throw DecryptionException("unpadding failed", e)
            }

        val decoded = decodeMessage(unpadded)

        plaintext.fill(0)
        unpadded.fill(0)

        return newState to decoded
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
