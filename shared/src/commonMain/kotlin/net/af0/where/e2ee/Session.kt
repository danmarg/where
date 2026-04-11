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
 * High-level session operations: encrypt and decrypt location updates.
 *
 * Per-message token rotation (§8.3): each encrypted message embeds a fresh
 * 16-byte nextSendToken in the first bytes of the plaintext.  The recipient
 * extracts it and immediately switches its recvToken — structurally enforcing
 * the single-token invariant without any overlap window.
 *
 * All functions are pure (immutable SessionState in, new SessionState out) so
 * callers can easily audit state transitions and write deterministic tests.
 */
object Session {
    private const val AAD_PREFIX = "Where-v1-Location"
    private const val PROTOCOL_VERSION = 1

    // §7.4: pad plaintext to a fixed block size for traffic-analysis resistance.
    // 512 bytes provides comfortable clearance while remaining a small fixed multiple
    // of a cache line, as per the design doc.
    internal const val PADDING_SIZE = 512

    // Next-token prefix length embedded at the start of every plaintext.
    internal const val TOKEN_PREFIX_SIZE = 16

    /**
     * Maximum allowed gap between the last received seq and the incoming seq.
     * A gap larger than this most likely indicates a desynchronized or malicious session.
     * Enforcing a cap prevents the chain from being advanced thousands of steps in a tight
     * loop on the UI thread (issue #146).
     */
    private const val MAX_GAP = 1024L

    /**
     * Encrypt one location update for a single peer.
     *
     * Generates a fresh random [nextSendToken] (16 bytes), prepends it to the
     * plaintext, and returns a new [SessionState] whose [SessionState.sendToken]
     * is set to that token.  The caller MUST use the *old* sendToken (captured
     * before this call) to POST the ciphertext; the new sendToken is for the
     * next message.
     *
     * @param state       Current session state (will be advanced).
     * @param location    Plaintext location to encrypt.
     * @param senderFp    SHA-256(sender EK.pub), 32 bytes — bound into AAD.
     * @param recipientFp SHA-256(recipient EK.pub), 32 bytes — bound into AAD.
     */
    fun encryptLocation(
        state: SessionState,
        location: LocationPlaintext,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): Pair<SessionState, ByteArray> {
        require(state.sendSeq != Long.MAX_VALUE) {
            "seq overflow — session must be re-keyed before sending more messages"
        }

        val step = kdfCk(state.sendChainKey)
        val seq = state.sendSeq + 1
        val aad = buildLocationAad(senderFp, recipientFp, seq)

        // Generate the token the recipient should poll for the NEXT message.
        val nextSendToken = randomBytes(TOKEN_PREFIX_SIZE)
        val plaintext = padToFixedSize(nextSendToken + encodeLocation(location), PADDING_SIZE)
        val ct = aeadEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
                // Ready for the next outgoing message; caller must use the OLD sendToken
                // for this message's POST.
                sendToken = nextSendToken,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        step.messageKey.fill(0)
        step.messageNonce.fill(0)
        plaintext.fill(0)

        return newState to ct
    }

    /**
     * Decrypt one incoming location frame.
     *
     * Extracts the embedded nextRecvToken from the first [TOKEN_PREFIX_SIZE] bytes
     * of the plaintext and stores it as [SessionState.recvToken] in the returned
     * state.  The caller should immediately start polling that new token.
     *
     * Rules:
     *   - Frames with seq <= state.recvSeq are silently dropped (replay).
     *   - The receive chain key is advanced deterministically; missing frames permanently
     *     skip their message keys (strict-ordering policy §8.3.1).
     *   - Gaps larger than MAX_GAP are rejected to prevent CPU exhaustion.
     *
     * @param state       Current session state.
     * @param ct          Ciphertext + GCM tag (as returned by encryptLocation).
     * @param seq         Sequence number from the wire frame.
     * @param senderFp    32-byte fingerprint of the sender.
     * @param recipientFp 32-byte fingerprint of the recipient.
     * @return Pair(newState, plaintext).
     * @throws ProtocolException if seq is a replay or the gap is too large.
     * @throws AuthenticationException if GCM authentication fails.
     */
    @Throws(WhereException::class)
    fun decryptLocation(
        state: SessionState,
        ct: ByteArray,
        seq: Long,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): Pair<SessionState, LocationPlaintext> {
        if (seq <= state.recvSeq) {
            throw ProtocolException("replay — seq $seq must be greater than state.recvSeq ${state.recvSeq}")
        }

        val stepsNeeded = seq - state.recvSeq
        // stepsNeeded is always >= 1 because seq starts at 1 and recvSeq starts at 0 (or resets
        // to 0 on ratchet rotation). The number of *missed* messages is (stepsNeeded - 1), so
        // we allow stepsNeeded up to MAX_GAP + 1 to permit exactly MAX_GAP missed messages.
        if (stepsNeeded > MAX_GAP + 1) {
            throw ProtocolException("seq gap ${stepsNeeded - 1} exceeds maximum $MAX_GAP — session may be desynchronized")
        }

        // Advance the receive chain key to reach the correct seq (handles gaps).
        // Each intermediate chainKey is zeroed before the reference is dropped (§5.5).
        var chainKey = state.recvChainKey.copyOf()
        var step: ChainStep? = null
        require(stepsNeeded <= Int.MAX_VALUE) { "stepsNeeded overflows Int" }
        repeat(stepsNeeded.toInt()) {
            step = kdfCk(chainKey)
            chainKey.fill(0)
            chainKey = step!!.newChainKey
        }
        val finalStep = step!!

        val aad = buildLocationAad(senderFp, recipientFp, seq)
        val plaintext =
            try {
                aeadDecrypt(finalStep.messageKey, finalStep.messageNonce, ct, aad)
            } catch (e: Exception) {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
                throw AuthenticationException("decryption failed: bad MAC", e)
            }
        val unpadded =
            try {
                unpad(plaintext)
            } catch (e: Exception) {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
                plaintext.fill(0)
                throw DecryptionException("unpadding failed", e)
            }

        require(unpadded.size > TOKEN_PREFIX_SIZE) { "plaintext too short to contain token prefix" }
        val nextRecvToken = unpadded.copyOfRange(0, TOKEN_PREFIX_SIZE)
        val location = decodeLocation(unpadded.copyOfRange(TOKEN_PREFIX_SIZE, unpadded.size))

        val newState =
            state.copy(
                recvChainKey = chainKey,
                recvSeq = seq,
                // Immediately switch to the token embedded in this message.
                recvToken = nextRecvToken,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        finalStep.messageKey.fill(0)
        finalStep.messageNonce.fill(0)
        plaintext.fill(0)
        unpadded.fill(0)

        return newState to location
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun buildLocationAad(
        senderFp: ByteArray,
        recipientFp: ByteArray,
        seq: Long,
    ): ByteArray =
        AAD_PREFIX.encodeToByteArray() +
            intToBeBytes(PROTOCOL_VERSION) +
            senderFp +
            recipientFp +
            longToBeBytes(seq)

    private fun encodeLocation(loc: LocationPlaintext): ByteArray =
        buildJsonObject {
            put("lat", loc.lat)
            put("lng", loc.lng)
            put("acc", loc.acc)
            put("ts", loc.ts)
        }.let { Json.encodeToString(it) }.encodeToByteArray()

    private fun decodeLocation(bytes: ByteArray): LocationPlaintext {
        val obj = Json.decodeFromString<JsonObject>(bytes.decodeToString())
        return LocationPlaintext(
            lat = obj["lat"]!!.jsonPrimitive.double,
            lng = obj["lng"]!!.jsonPrimitive.double,
            acc = obj["acc"]!!.jsonPrimitive.double,
            ts = obj["ts"]!!.jsonPrimitive.long,
        )
    }

    /**
     * Padding to a fixed size.
     * Uses 2 bytes (big-endian uint16) for the padding count, stored at the end.
     * All padding bytes (including the count bytes) have the same value (the count).
     */
    private fun padToFixedSize(
        data: ByteArray,
        size: Int,
    ): ByteArray {
        require(data.size > 0) { "plaintext must not be empty" }
        // We need at least 2 bytes for the padding count
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
        require(data.size >= 2) { "padded data too short" }
        val padByte = data[data.size - 1].toInt() and 0xFF
        val padHighByte = data[data.size - 2].toInt() and 0xFF
        val padCount = (padHighByte shl 8) or padByte

        require(padCount >= 2 && padCount <= data.size) { "invalid padding count: $padCount" }
        for (i in data.size - padCount until data.size - 2) {
            require(data[i].toInt() and 0xFF == padByte) { "invalid padding at index $i" }
        }
        return data.copyOfRange(0, data.size - padCount)
    }
}

// ---------------------------------------------------------------------------
// AEAD-based control message helpers
// ---------------------------------------------------------------------------

internal data class EpochRotationPlaintext(val opkId: Int, val newEkPub: ByteArray)

/**
 * Build the AEAD-encrypted EpochRotation payload blob.
 *
 * K_rot = HKDF(currentRootKey, salt=absent, info="Where-v1-EpochRotation", length=32).
 * K_rot is unique per rotation (root key advances after each DH step), so a fixed
 * all-zero nonce is safe (§8.4).
 *
 * Plaintext: opkId (4 bytes big-endian) || newEkPub (32 bytes) = 36 bytes.
 * AAD: aliceFp || bobFp || sendToken (Alice's current sendToken = T_AB_old).
 */
internal fun buildEpochRotationCt(
    currentRootKey: ByteArray,
    opkId: Int,
    newEkPub: ByteArray,
    aliceFp: ByteArray,
    bobFp: ByteArray,
    sendToken: ByteArray,
): ByteArray {
    val kRot = hkdfSha256(currentRootKey, salt = null, INFO_EPOCH_ROTATION.encodeToByteArray(), 32)
    val plaintext = intToBeBytes(opkId) + newEkPub
    val aad = aliceFp + bobFp + sendToken
    val nonce = ByteArray(12)
    return aeadEncrypt(kRot, nonce, plaintext, aad)
}

/**
 * Verify and decrypt an EpochRotation ct. Returns [EpochRotationPlaintext] or throws.
 */
internal fun decryptEpochRotationCt(
    currentRootKey: ByteArray,
    ct: ByteArray,
    aliceFp: ByteArray,
    bobFp: ByteArray,
    sendToken: ByteArray,
): EpochRotationPlaintext {
    val kRot = hkdfSha256(currentRootKey, salt = null, INFO_EPOCH_ROTATION.encodeToByteArray(), 32)
    val aad = aliceFp + bobFp + sendToken
    val nonce = ByteArray(12)
    val plaintext =
        try {
            aeadDecrypt(kRot, nonce, ct, aad)
        } catch (e: Exception) {
            throw AuthenticationException("EpochRotation decryption failed", e)
        }
    if (plaintext.size != 4 + 32) {
        throw ProtocolException("bad EpochRotation plaintext size: ${plaintext.size}")
    }
    val opkId = bytesToInt(plaintext, 0)
    val newEkPub = plaintext.copyOfRange(4, 36)
    return EpochRotationPlaintext(opkId, newEkPub)
}

/**
 * Build the AEAD-encrypted RatchetAck payload blob.
 *
 * K_ack = HKDF(newRootKey, salt=absent, info="Where-v1-RatchetAck", length=32).
 * Both Bob (post-rotation) and Alice (pendingRotation.newSession) know newRootKey, so
 * a successful tag verification proves Bob performed the correct DH step.
 *
 * Plaintext: empty.
 * AAD: bobFp || aliceFp || newSendToken (T_BA_new = Bob's new send / Alice's new recv token).
 */
internal fun buildRatchetAckCt(
    newRootKey: ByteArray,
    bobFp: ByteArray,
    aliceFp: ByteArray,
    newSendToken: ByteArray,
): ByteArray {
    val kAck = hkdfSha256(newRootKey, salt = null, INFO_RATCHET_ACK.encodeToByteArray(), 32)
    val aad = bobFp + aliceFp + newSendToken
    val nonce = ByteArray(12)
    return aeadEncrypt(kAck, nonce, ByteArray(0), aad)
}

/**
 * Verify a RatchetAck ct. Throws if the AEAD tag does not verify.
 */
internal fun decryptRatchetAckCt(
    newRootKey: ByteArray,
    ct: ByteArray,
    bobFp: ByteArray,
    aliceFp: ByteArray,
    newSendToken: ByteArray,
) {
    val kAck = hkdfSha256(newRootKey, salt = null, INFO_RATCHET_ACK.encodeToByteArray(), 32)
    val aad = bobFp + aliceFp + newSendToken
    val nonce = ByteArray(12)
    val plaintext =
        try {
            aeadDecrypt(kAck, nonce, ct, aad)
        } catch (e: Exception) {
            throw AuthenticationException("RatchetAck decryption failed", e)
        }
    if (plaintext.isNotEmpty()) {
        throw ProtocolException("unexpected RatchetAck plaintext: ${plaintext.size} bytes")
    }
}

private fun bytesToInt(
    buf: ByteArray,
    offset: Int,
): Int =
    ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)
