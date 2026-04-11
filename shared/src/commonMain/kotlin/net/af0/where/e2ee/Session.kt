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
 * and perform DH ratchet rotation.
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
    internal const val PADDING_SIZE = 512

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
     * Returns the updated SessionState (with advanced chain key and seq) and the
     * ciphertext (GCM ciphertext + 16-byte tag, ready to embed in EncryptedLocation).
     *
     * @param state       Current session state (will be advanced).
     * @param location    Plaintext location to encrypt.
     * @param senderFp    Alice's 32-byte fingerprint.
     * @param recipientFp Bob's 32-byte fingerprint.
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
        // stepsNeeded is capped at MAX_GAP (1024) above, so .toInt() is safe.
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
     * Alice: initiate a two-step DH ratchet rotation using a cached OPK from Bob.
     *
     * Computes the intermediate session state (after step 1 only) and pre-builds the
     * EpochRotation ciphertext blob. The caller stores the returned [PendingRotation]
     * and includes [PendingRotation.epochRotationCt] in every outgoing send until Bob acks.
     *
     * Alice MUST NOT commit until she receives a RatchetAck. Call [aliceProcessRatchetAck],
     * which performs step 2 (using Bob's fresh EK from the ack) and returns the final
     * committed session. Until then she continues using the current session for sends/receives.
     *
     * Two-step protocol (§8.4):
     *   Step 1 (here):    KDF_RK(rootKey,  DH(aliceNewEk, bobOpk))  → (rootKey1, chainKey_AB)
     *   Step 2 (on ack):  KDF_RK(rootKey1, DH(aliceNewEk, bobNewEk)) → (rootKey2, chainKey_BA)
     * Both sides contribute a fresh ephemeral per rotation — mutual PFS.
     *
     * @param state          Current session state.
     * @param aliceNewEkPriv Alice's fresh ephemeral X25519 private key for this step.
     *                       The caller's buffer is zeroed; a copy is kept in [PendingRotation].
     * @param aliceNewEkPub  Corresponding public key.
     * @param bobOpkPub      Bob's OPK public key (consumed from cache).
     * @param opkId          ID of the OPK consumed (stored in PendingRotation).
     * @param aliceFp        Alice's fingerprint.
     * @param bobFp          Bob's fingerprint.
     * @return [PendingRotation] with the intermediate session, ack ct, and preserved priv key.
     */
    fun aliceEpochRotation(
        state: SessionState,
        aliceNewEkPriv: ByteArray,
        aliceNewEkPub: ByteArray,
        bobOpkPub: ByteArray,
        opkId: Int,
        aliceFp: ByteArray,
        bobFp: ByteArray,
    ): PendingRotation {
        // Step 1 DH: Alice's ephemeral × Bob's OPK.
        val dhOut = x25519(aliceNewEkPriv, bobOpkPub)
        val ratchetStep = kdfRk(state.rootKey, dhOut)
        // Intermediate routing tokens from rootKey1. The final tokens (from rootKey2) are
        // derived in aliceProcessRatchetAck once Bob's fresh EK is received.
        // recvToken (= T_BA from rootKey1) is used as the AAD anchor in the RatchetAck.
        val newTokenAliceToBob = deriveRoutingToken(ratchetStep.newRootKey, senderFp = aliceFp, recipientFp = bobFp)
        val newTokenBobToAlice = deriveRoutingToken(ratchetStep.newRootKey, senderFp = bobFp, recipientFp = aliceFp)

        val newSession =
            state.copy(
                rootKey = ratchetStep.newRootKey,
                sendChainKey = ratchetStep.newChainKey,  // chainKey_AB; final send chain for Alice
                sendToken = newTokenAliceToBob,
                recvToken = newTokenBobToAlice,  // used as AAD in RatchetAck; overwritten at commit
                myEkPriv = ByteArray(32),  // not stored here — kept in PendingRotation.aliceNewEkPriv
                myEkPub = aliceNewEkPub.copyOf(),
                theirEkPub = bobOpkPub.copyOf(),
                sendSeq = 0L,
                recvSeq = 0L,
            )

        val epochRotationCt =
            buildEpochRotationCt(
                currentRootKey = state.rootKey,
                opkId = opkId,
                newEkPub = aliceNewEkPub,
                aliceFp = aliceFp,
                bobFp = bobFp,
                sendToken = state.sendToken,
            )

        // Keep a copy of aliceNewEkPriv in PendingRotation for step 2 in aliceProcessRatchetAck.
        // Zero the caller's buffer immediately (§5.5, §11).
        val aliceNewEkPrivCopy = aliceNewEkPriv.copyOf()
        dhOut.fill(0)
        aliceNewEkPriv.fill(0)

        return PendingRotation(
            newSession = newSession,
            epochRotationCt = epochRotationCt,
            opkId = opkId,
            aliceNewEkPriv = aliceNewEkPrivCopy,
        )
    }

    /**
     * Bob: process Alice's EpochRotation using a two-step DH for mutual PFS.
     *
     * Immediately switches to the new recvToken (no dual-polling window). Returns
     * (newState, ratchetAckCt). Bob MUST post [ratchetAckCt] on [state.sendToken]
     * (the **pre-rotation** sendToken) so Alice can receive it on her current recvToken.
     *
     * Two-step protocol (§8.4):
     *   Step 1: KDF_RK(rootKey,  DH(bobOpk,    aliceNewEk)) → (rootKey1, chainKey_AB)
     *   Step 2: KDF_RK(rootKey1, DH(bobNewEk,  aliceNewEk)) → (rootKey2, chainKey_BA)
     * Bob generates a fresh ephemeral [bobNewEk] and includes its public key in the
     * RatchetAck so Alice can perform the matching step 2 on commit.
     *
     * @param state           Bob's current session state.
     * @param aliceNewEkPub   Alice's new ephemeral public key (from [decryptEpochRotationCt]).
     * @param bobOpkPriv      Bob's OPK private key for the consumed opkId (zeroed on return).
     * @param aliceFp         Alice's fingerprint.
     * @param bobFp           Bob's fingerprint.
     * @return Pair(newState, ratchetAckCt) where ratchetAckCt must be posted on the pre-rotation sendToken.
     */
    fun bobProcessAliceRotation(
        state: SessionState,
        aliceNewEkPub: ByteArray,
        bobOpkPriv: ByteArray,
        aliceFp: ByteArray,
        bobFp: ByteArray,
    ): Pair<SessionState, ByteArray> {
        // Step 1: Alice's DH contribution (OPK × aliceNewEk).
        val dhOut1 = x25519(bobOpkPriv, aliceNewEkPub)
        val ratchetStep1 = kdfRk(state.rootKey, dhOut1)

        // Step 2: Bob's fresh DH contribution — generates mutual PFS.
        val bobNewEk = generateX25519KeyPair()
        val dhOut2 = x25519(bobNewEk.priv, aliceNewEkPub)
        val ratchetStep2 = kdfRk(ratchetStep1.newRootKey, dhOut2)

        // Final routing tokens derived from rootKey2.
        val newTokenAliceToBob = deriveRoutingToken(ratchetStep2.newRootKey, senderFp = aliceFp, recipientFp = bobFp)
        val newTokenBobToAlice = deriveRoutingToken(ratchetStep2.newRootKey, senderFp = bobFp, recipientFp = aliceFp)

        val newState =
            state.copy(
                rootKey = ratchetStep2.newRootKey,
                recvChainKey = ratchetStep1.newChainKey,  // chainKey_AB (= Alice's send chain)
                sendChainKey = ratchetStep2.newChainKey,  // chainKey_BA (Bob's fresh send chain)
                sendToken = newTokenBobToAlice,
                recvToken = newTokenAliceToBob,
                theirEkPub = aliceNewEkPub.copyOf(),
                myEkPub = bobNewEk.pub.copyOf(),
                myEkPriv = ByteArray(32),
                recvSeq = 0L,
                sendSeq = 0L,
            )

        // The ack is authenticated under K_ack derived from rootKey1 (the intermediate root
        // key that Alice also holds in her pending session). Alice uses the ack to extract
        // bobNewEkPub and compute step 2 herself.
        val intermediateTokenBobToAlice =
            deriveRoutingToken(ratchetStep1.newRootKey, senderFp = bobFp, recipientFp = aliceFp)
        val ratchetAckCt =
            buildRatchetAckCt(
                intermediateRootKey = ratchetStep1.newRootKey,
                bobNewEkPub = bobNewEk.pub,
                bobFp = bobFp,
                aliceFp = aliceFp,
                intermediateSendToken = intermediateTokenBobToAlice,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        dhOut1.fill(0)
        dhOut2.fill(0)
        bobOpkPriv.fill(0)
        bobNewEk.priv.fill(0)

        return newState to ratchetAckCt
    }

    /**
     * Alice: verify Bob's RatchetAck, perform step 2 DH, and atomically commit the rotation.
     *
     * The ack contains Bob's fresh ephemeral public key (bobNewEkPub) in its AEAD plaintext.
     * Alice uses it to perform step 2: KDF_RK(rootKey1, DH(aliceNewEkPriv, bobNewEkPub))
     * → (rootKey2, chainKey_BA). The returned session is the fully committed final state.
     *
     * Throws [AuthenticationException] if the AEAD tag does not verify.
     *
     * @param pendingRotation The stored pending rotation (from [aliceEpochRotation]).
     * @param ackCt           The AEAD blob from the RatchetAckPayload.
     * @param bobFp           Bob's fingerprint.
     * @param aliceFp         Alice's fingerprint.
     * @return The committed new session state (with final rootKey2 and routing tokens).
     */
    fun aliceProcessRatchetAck(
        pendingRotation: PendingRotation,
        ackCt: ByteArray,
        bobFp: ByteArray,
        aliceFp: ByteArray,
    ): SessionState {
        // Decrypt the ack (authenticated under K_ack from rootKey1) to get Bob's fresh EK pub.
        val bobNewEkPub =
            decryptRatchetAckCt(
                intermediateRootKey = pendingRotation.newSession.rootKey,
                ct = ackCt,
                bobFp = bobFp,
                aliceFp = aliceFp,
                intermediateSendToken = pendingRotation.newSession.recvToken,
            )

        // Step 2 DH: KDF_RK(rootKey1, DH(aliceNewEkPriv, bobNewEkPub)) → (rootKey2, chainKey_BA)
        val dhOut2 = x25519(pendingRotation.aliceNewEkPriv, bobNewEkPub)
        val ratchetStep2 = kdfRk(pendingRotation.newSession.rootKey, dhOut2)

        // Final routing tokens from rootKey2.
        val finalTokenAliceToBob =
            deriveRoutingToken(ratchetStep2.newRootKey, senderFp = aliceFp, recipientFp = bobFp)
        val finalTokenBobToAlice =
            deriveRoutingToken(ratchetStep2.newRootKey, senderFp = bobFp, recipientFp = aliceFp)

        val committed =
            pendingRotation.newSession.copy(
                rootKey = ratchetStep2.newRootKey,
                // sendChainKey (chainKey_AB from step 1) carries over unchanged
                recvChainKey = ratchetStep2.newChainKey,  // chainKey_BA from step 2
                sendToken = finalTokenAliceToBob,
                recvToken = finalTokenBobToAlice,
                theirEkPub = bobNewEkPub.copyOf(),
                recvSeq = 0L,
            )

        // Security (§5.5, §11): zero out ephemeral keys after use
        dhOut2.fill(0)
        pendingRotation.aliceNewEkPriv.fill(0)

        return committed
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
 * all-zero nonce is safe (§8.4). Note that while the resulting ciphertext may be
 * re-sent multiple times (e.g., via pendingEpochRotation), the (K_rot, nonce) pair
 * is never used with *different* plaintext. This is safe deterministic replay, not
 * nonce reuse. The protocol ensures only one rotation is pending at a time.
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
    // K_rot is unique per rotation, so a fixed all-zero nonce is safe (§8.4).
    // Repeated posting of the same ciphertext (deterministic replay) is safe,
    // not nonce reuse with fresh plaintext.
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
    // K_rot is unique per rotation, so a fixed all-zero nonce is safe (§8.4).
    // Repeated posting of the same ciphertext (deterministic replay) is safe,
    // not nonce reuse with fresh plaintext.
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
 * K_ack = HKDF(intermediateRootKey, salt=absent, info="Where-v1-RatchetAck", length=32).
 * [intermediateRootKey] is rootKey1 (after step 1 only), known to both parties:
 *   - Bob computes it in [Session.bobProcessAliceRotation].
 *   - Alice has it in [PendingRotation.newSession.rootKey].
 * A successful tag verification proves Bob performed the correct step 1 DH.
 *
 * Like EpochRotation, K_ack is unique per rotation, so a fixed all-zero nonce
 * is safe (§8.4). Repeated posting of the same RatchetAck ciphertext (for
 * lost-ack recovery) is safe deterministic replay, not nonce reuse with fresh plaintext.
 *
 * Plaintext: bobNewEkPub (32 bytes) — Bob's fresh ephemeral pub key for step 2.
 * AAD: bobFp || aliceFp || intermediateSendToken (T_BA from rootKey1).
 */
internal fun buildRatchetAckCt(
    intermediateRootKey: ByteArray,
    bobNewEkPub: ByteArray,
    bobFp: ByteArray,
    aliceFp: ByteArray,
    intermediateSendToken: ByteArray,
): ByteArray {
    val kAck = hkdfSha256(intermediateRootKey, salt = null, INFO_RATCHET_ACK.encodeToByteArray(), 32)
    val aad = bobFp + aliceFp + intermediateSendToken
    // K_ack is unique per rotation, so a fixed all-zero nonce is safe (§8.4).
    // Repeated posting of the same ciphertext (deterministic replay) is safe,
    // not nonce reuse with fresh plaintext.
    val nonce = ByteArray(12)
    return aeadEncrypt(kAck, nonce, bobNewEkPub, aad)
}

/**
 * Verify and decrypt a RatchetAck ct.
 *
 * Returns Bob's fresh ephemeral public key (32 bytes) for Alice's step 2 DH.
 * Throws [AuthenticationException] if the AEAD tag does not verify.
 * Throws [ProtocolException] if the plaintext is not exactly 32 bytes.
 */
internal fun decryptRatchetAckCt(
    intermediateRootKey: ByteArray,
    ct: ByteArray,
    bobFp: ByteArray,
    aliceFp: ByteArray,
    intermediateSendToken: ByteArray,
): ByteArray {
    val kAck = hkdfSha256(intermediateRootKey, salt = null, INFO_RATCHET_ACK.encodeToByteArray(), 32)
    val aad = bobFp + aliceFp + intermediateSendToken
    // K_ack is unique per rotation, so a fixed all-zero nonce is safe (§8.4).
    // Repeated posting of the same ciphertext (deterministic replay) is safe,
    // not nonce reuse with fresh plaintext.
    val nonce = ByteArray(12)
    val plaintext =
        try {
            aeadDecrypt(kAck, nonce, ct, aad)
        } catch (e: Exception) {
            throw AuthenticationException("RatchetAck decryption failed", e)
        }
    if (plaintext.size != 32) {
        throw ProtocolException("unexpected RatchetAck plaintext size: ${plaintext.size} (expected 32 bytes for bobNewEkPub)")
    }
    return plaintext  // bobNewEkPub
}

private fun bytesToInt(
    buf: ByteArray,
    offset: Int,
): Int =
    ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)
