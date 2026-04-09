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
 * High-level session operations: encrypt a location update, decrypt an incoming update,
 * and perform DH epoch rotation.
 *
 * All functions are pure (immutable SessionState in, new SessionState out) so callers
 * can easily audit state transitions and write deterministic tests.
 */
object Session {
    private const val AAD_PREFIX = "Where-v1-Location"
    private const val PROTOCOL_VERSION = 1

    // §7.4: pad plaintext to a fixed block size for traffic-analysis resistance.
    // 512 bytes provides comfortable clearance while remaining a small fixed multiple
    // of a cache line, as per the design doc.
    private const val PADDING_SIZE = 512

    /**
     * Maximum allowed gap between the last received seq and the incoming seq.
     * A gap larger than this most likely indicates a desynchronized or malicious session.
     * Enforcing a cap prevents the chain from being advanced thousands of steps in a tight
     * loop on the UI thread (issue #7).
     */
    private const val MAX_DECRYPT_GAP = 1000L

    /**
     * Encrypt one location update for a single peer.
     *
     * Returns the updated SessionState (with advanced chain key and seq) and the
     * ciphertext (GCM ciphertext + 16-byte tag, ready to embed in EncryptedLocation).
     *
     * @param state       Current session state (will be advanced).
     * @param location    Plaintext location to encrypt.
     * @param senderFp    SHA-256(sender IK.pub || sender SigIK.pub), 32 bytes.
     * @param recipientFp SHA-256(recipient IK.pub || recipient SigIK.pub), 32 bytes.
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
        val aad = buildLocationAad(senderFp, recipientFp, state.epoch, seq)
        val plaintext = padToFixedSize(encodeLocation(location), PADDING_SIZE)
        val ct = aeadEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
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
     * Returns the updated SessionState (with advanced recvSeq) and the plaintext.
     *
     * Rules:
     *   - Frames with seq <= state.recvSeq are silently dropped (replay).
     *   - The receive chain key is advanced deterministically; missing frames permanently
     *     skip their message keys (strict-ordering policy §8.3.1).
     *   - Gaps larger than MAX_DECRYPT_GAP are rejected to prevent CPU exhaustion.
     *
     * @param state       Current session state.
     * @param ct          Ciphertext + GCM tag (as returned by encryptLocation).
     * @param seq         Sequence number from the wire frame.
     * @param senderFp    32-byte fingerprint of the sender.
     * @param recipientFp 32-byte fingerprint of the recipient.
     * @return Pair(newState, plaintext).
     * @throws IllegalArgumentException if GCM authentication fails, the seq gap is too large, or seq is a replay.
     */
    @Throws(IllegalArgumentException::class)
    fun decryptLocation(
        state: SessionState,
        ct: ByteArray,
        seq: Long,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): Pair<SessionState, LocationPlaintext> {
        require(seq > state.recvSeq) { "replay — seq $seq must be greater than state.recvSeq ${state.recvSeq}" }

        val stepsNeeded = seq - state.recvSeq
        require(stepsNeeded <= MAX_DECRYPT_GAP) {
            "seq gap $stepsNeeded exceeds maximum $MAX_DECRYPT_GAP — session may be desynchronized"
        }

        // Advance the receive chain key to reach the correct seq (handles gaps).
        var chainKey = state.recvChainKey.copyOf()
        var step: ChainStep? = null
        repeat(stepsNeeded.toInt()) {
            step = kdfCk(chainKey)
            chainKey = step!!.newChainKey
        }
        val finalStep = step!!

        val aad = buildLocationAad(senderFp, recipientFp, state.epoch, seq)
        val plaintext = aeadDecrypt(finalStep.messageKey, finalStep.messageNonce, ct, aad)
        val unpadded =
            try {
                unpad(plaintext)
            } catch (e: Exception) {
                finalStep.messageKey.fill(0)
                finalStep.messageNonce.fill(0)
                plaintext.fill(0)
                throw e
            }
        val location = decodeLocation(unpadded)

        val newState =
            state.copy(
                recvChainKey = chainKey,
                recvSeq = seq,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        finalStep.messageKey.fill(0)
        finalStep.messageNonce.fill(0)
        plaintext.fill(0)
        unpadded.fill(0)

        return newState to location
    }

    /**
     * Alice: perform a DH epoch rotation step using a cached OPK from Bob.
     *
     * Returns the new SessionState (with incremented epoch, updated root/chain keys,
     * and new routing token). Alice MUST delete aliceEkPriv and the consumed OPK
     * from her cache after this call.
     *
     * @param state       Current session state.
     * @param aliceNewEkPriv Alice's new ephemeral X25519 private key (fresh for this epoch).
     * @param aliceNewEkPub  Corresponding public key.
     * @param bobOpkPub   Bob's OPK public key (consumed from cache).
     * @param senderFp    Alice's fingerprint.
     * @param recipientFp Bob's fingerprint.
     */
    fun aliceEpochRotation(
        state: SessionState,
        aliceNewEkPriv: ByteArray,
        aliceNewEkPub: ByteArray,
        bobOpkPub: ByteArray,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): SessionState {
        val newEpoch = state.epoch + 1
        val dhOut = x25519(aliceNewEkPriv, bobOpkPub)
        val ratchetStep = kdfRk(state.rootKey, dhOut)
        val tokenAliceToBob = deriveRoutingToken(ratchetStep.newRootKey, newEpoch, senderFp = senderFp, recipientFp = recipientFp)
        val tokenBobToAlice = deriveRoutingToken(ratchetStep.newRootKey, newEpoch, senderFp = recipientFp, recipientFp = senderFp)
        val newState =
            state.copy(
                rootKey = ratchetStep.newRootKey,
                // Alice's new send chain
                sendChainKey = ratchetStep.newChainKey,
                sendToken = tokenAliceToBob,
                recvToken = tokenBobToAlice,
                epoch = newEpoch,
                myEkPriv = aliceNewEkPriv.copyOf(),
                myEkPub = aliceNewEkPub.copyOf(),
                sendSeq = 0L,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        dhOut.fill(0)

        return newState
    }

    /**
     * Bob: process Alice's EpochRotation by computing the matching DH step.
     *
     * Updates Bob's receive chain key (= Alice's new send chain) so he can decrypt
     * Alice's messages in the new epoch.
     *
     * @param state       Bob's current session state.
     * @param aliceNewEkPub Alice's new ephemeral public key (from EpochRotation message).
     * @param bobOpkPriv  Bob's OPK private key for the consumed opk_id.
     * @param senderFp    Alice's fingerprint.
     * @param recipientFp Bob's fingerprint.
     */
    fun bobProcessAliceRotation(
        state: SessionState,
        aliceNewEkPub: ByteArray,
        bobOpkPriv: ByteArray,
        newEpoch: Int,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): SessionState {
        val dhOut = x25519(bobOpkPriv, aliceNewEkPub)
        val ratchetStep = kdfRk(state.rootKey, dhOut)
        val tokenAliceToBob = deriveRoutingToken(ratchetStep.newRootKey, newEpoch, senderFp = senderFp, recipientFp = recipientFp)
        val tokenBobToAlice = deriveRoutingToken(ratchetStep.newRootKey, newEpoch, senderFp = recipientFp, recipientFp = senderFp)
        val newState =
            state.copy(
                rootKey = ratchetStep.newRootKey,
                // Bob's new recv chain (= Alice's new send)
                recvChainKey = ratchetStep.newChainKey,
                sendToken = tokenBobToAlice,
                recvToken = tokenAliceToBob,
                epoch = newEpoch,
                theirEkPub = aliceNewEkPub.copyOf(),
                recvSeq = 0L,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        dhOut.fill(0)

        return newState
    }

    /**
     * Bob: perform his own DH rotation step to refresh his send chain.
     * The DH is computed immediately; bobNewEkPriv is NOT stored in the returned state.
     */
    fun bobProcessOwnRotation(
        state: SessionState,
        bobNewEkPriv: ByteArray,
        bobNewEkPub: ByteArray,
    ): SessionState {
        val dhOut = x25519(bobNewEkPriv, state.theirEkPub)
        val ratchetStep = kdfRk(state.rootKey, dhOut)
        val newState =
            state.copy(
                rootKey = ratchetStep.newRootKey,
                sendChainKey = ratchetStep.newChainKey,
                // DH already computed above; zero priv so it is not persisted (§5.5).
                myEkPriv = ByteArray(32),
                myEkPub = bobNewEkPub.copyOf(),
                sendSeq = 0L,
            )
        dhOut.fill(0)
        bobNewEkPriv.fill(0)
        return newState
    }

    /**
     * Alice: process Bob's RatchetAck and perform the second half of the DH ratchet.
     * state.myEkPriv is consumed and zeroed in the returned state (§5.5).
     *
     * @param state       Alice's current session state.
     * @param bobNewEkPub Bob's new ephemeral public key from RatchetAck.
     */
    fun aliceProcessRatchetAck(
        state: SessionState,
        bobNewEkPub: ByteArray,
    ): SessionState {
        val dhOut = x25519(state.myEkPriv, bobNewEkPub)
        val ratchetStep = kdfRk(state.rootKey, dhOut)
        val newState =
            state.copy(
                rootKey = ratchetStep.newRootKey,
                // Alice's new recv chain (= Bob's new send)
                recvChainKey = ratchetStep.newChainKey,
                theirEkPub = bobNewEkPub.copyOf(),
                recvSeq = 0L,
                // Consumed by this DH step; zero so it is not persisted (§5.5).
                myEkPriv = ByteArray(32),
            )
        dhOut.fill(0)
        return newState
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun buildLocationAad(
        senderFp: ByteArray,
        recipientFp: ByteArray,
        epoch: Int,
        seq: Long,
    ): ByteArray =
        AAD_PREFIX.encodeToByteArray() +
            intToBeBytes(PROTOCOL_VERSION) +
            senderFp +
            recipientFp +
            intToBeBytes(epoch) +
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

internal data class EpochRotationPlaintext(val epoch: Int, val opkId: Int, val newEkPub: ByteArray, val ts: Long)

internal data class RatchetAckPlaintext(val epochSeen: Int, val ts: Long, val newEkPub: ByteArray?)

/**
 * Build the AEAD-encrypted EpochRotation payload blob.
 * K_rot is derived from the current root key via HKDF.
 */
internal fun buildEpochRotationCt(
    rootKey: ByteArray,
    epoch: Int,
    opkId: Int,
    newEkPub: ByteArray,
    ts: Long,
    nonce: ByteArray,
    routingToken: ByteArray,
    senderFp: ByteArray,
    recipientFp: ByteArray,
): ByteArray {
    val epochBe = intToBeBytes(epoch)
    val salt = epochBe
    val kRot = hkdfSha256(rootKey, salt, INFO_EPOCH_ROTATION.encodeToByteArray(), 32)
    val plaintext = intToBeBytes(epoch) + intToBeBytes(opkId) + newEkPub + longToBeBytes(ts)
    val aad = senderFp + recipientFp + routingToken
    return aeadEncrypt(kRot, nonce, plaintext, aad)
}

/**
 * Verify and decrypt an EpochRotation ct. Returns EpochRotationPlaintext or throws.
 */
internal fun decryptEpochRotationCt(
    rootKey: ByteArray,
    epoch: Int,
    nonce: ByteArray,
    ct: ByteArray,
    routingToken: ByteArray,
    senderFp: ByteArray,
    recipientFp: ByteArray,
): EpochRotationPlaintext {
    val epochBe = intToBeBytes(epoch)
    val salt = epochBe
    val kRot = hkdfSha256(rootKey, salt, INFO_EPOCH_ROTATION.encodeToByteArray(), 32)
    val aad = senderFp + recipientFp + routingToken
    val plaintext = aeadDecrypt(kRot, nonce, ct, aad)
    require(plaintext.size == 4 + 4 + 32 + 8) { "bad EpochRotation plaintext size: ${plaintext.size}" }
    val decodedEpoch = bytesToInt(plaintext, 0)
    val opkId = bytesToInt(plaintext, 4)
    val newEkPub = plaintext.copyOfRange(8, 40)
    val ts = bytesToLong(plaintext, 40)
    return EpochRotationPlaintext(decodedEpoch, opkId, newEkPub, ts)
}

/**
 * Build the AEAD-encrypted RatchetAck payload blob.
 */
internal fun buildRatchetAckCt(
    rootKey: ByteArray,
    epochSeen: Int,
    ts: Long,
    newEkPub: ByteArray?,
    nonce: ByteArray,
    routingToken: ByteArray,
    senderFp: ByteArray,
    recipientFp: ByteArray,
): ByteArray {
    val epochBe = intToBeBytes(epochSeen)
    val salt = epochBe
    val kAck = hkdfSha256(rootKey, salt, INFO_RATCHET_ACK.encodeToByteArray(), 32)
    val plaintext = intToBeBytes(epochSeen) + longToBeBytes(ts) + (newEkPub ?: ByteArray(32))
    val aad = senderFp + recipientFp + routingToken
    return aeadEncrypt(kAck, nonce, plaintext, aad)
}

/**
 * Verify and decrypt a RatchetAck ct. Returns RatchetAckPlaintext or throws.
 */
internal fun decryptRatchetAckCt(
    rootKey: ByteArray,
    epochSeen: Int,
    nonce: ByteArray,
    ct: ByteArray,
    routingToken: ByteArray,
    senderFp: ByteArray,
    recipientFp: ByteArray,
): RatchetAckPlaintext {
    val epochBe = intToBeBytes(epochSeen)
    val salt = epochBe
    val kAck = hkdfSha256(rootKey, salt, INFO_RATCHET_ACK.encodeToByteArray(), 32)
    val aad = senderFp + recipientFp + routingToken
    val plaintext = aeadDecrypt(kAck, nonce, ct, aad)
    require(plaintext.size == 4 + 8 + 32) { "bad RatchetAck plaintext size: ${plaintext.size}" }
    val decodedEpochSeen = bytesToInt(plaintext, 0)
    val ts = bytesToLong(plaintext, 4)
    val newEkPub = plaintext.copyOfRange(12, 44)
    val isAllZeros = newEkPub.all { it == 0.toByte() }
    return RatchetAckPlaintext(decodedEpochSeen, ts, if (isAllZeros) null else newEkPub)
}

private fun bytesToInt(
    buf: ByteArray,
    offset: Int,
): Int =
    ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)

private fun bytesToLong(
    buf: ByteArray,
    offset: Int,
): Long =
    ((buf[offset].toLong() and 0xFF) shl 56) or
        ((buf[offset + 1].toLong() and 0xFF) shl 48) or
        ((buf[offset + 2].toLong() and 0xFF) shl 40) or
        ((buf[offset + 3].toLong() and 0xFF) shl 32) or
        ((buf[offset + 4].toLong() and 0xFF) shl 24) or
        ((buf[offset + 5].toLong() and 0xFF) shl 16) or
        ((buf[offset + 6].toLong() and 0xFF) shl 8) or
        (buf[offset + 7].toLong() and 0xFF)
