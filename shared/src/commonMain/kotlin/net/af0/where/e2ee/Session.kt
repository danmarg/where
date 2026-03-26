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
    // 256 bytes guarantees the PKCS#7 pad count fits in one byte (range [1, 255])
    // since encoded location JSON is always < 256 bytes.
    private const val PADDING_SIZE = 256

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
        val ct = aesgcmEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        val newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
            )
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
     * @return Pair(newState, plaintext) or null if the frame is a replay.
     * @throws IllegalArgumentException if GCM authentication fails or the seq gap is too large.
     */
    fun decryptLocation(
        state: SessionState,
        ct: ByteArray,
        seq: Long,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): Pair<SessionState, LocationPlaintext>? {
        if (seq <= state.recvSeq) return null // replay — drop silently

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
        val plaintext = aesgcmDecrypt(finalStep.messageKey, finalStep.messageNonce, ct, aad)
        val location = decodeLocation(unpad(plaintext))

        val newState =
            state.copy(
                recvChainKey = chainKey,
                recvSeq = seq,
            )
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
        val newToken = deriveRoutingToken(ratchetStep.newRootKey, newEpoch, senderFp, recipientFp)
        return state.copy(
            rootKey = ratchetStep.newRootKey,
            sendChainKey = ratchetStep.newChainKey, // Alice's new send chain
            // recvChainKey is unchanged — Bob's outgoing chain has not rotated
            routingToken = newToken,
            epoch = newEpoch,
            myEkPriv = aliceNewEkPriv.copyOf(),
            myEkPub = aliceNewEkPub.copyOf(),
        )
    }

    /**
     * Bob: process an EpochRotation by computing the matching DH step.
     *
     * Updates Bob's receive chain key (= Alice's new send chain) so he can decrypt
     * Alice's messages in the new epoch. Bob's own send chain is not affected.
     *
     * @param state       Bob's current session state.
     * @param aliceNewEkPub Alice's new ephemeral public key (from EpochRotation message).
     * @param bobOpkPriv  Bob's OPK private key for the consumed opk_id. MUST be deleted by
     *                    caller immediately after this call.
     * @param senderFp    Alice's fingerprint.
     * @param recipientFp Bob's fingerprint.
     */
    fun bobProcessEpochRotation(
        state: SessionState,
        aliceNewEkPub: ByteArray,
        bobOpkPriv: ByteArray,
        newEpoch: Int,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): SessionState {
        val dhOut = x25519(bobOpkPriv, aliceNewEkPub)
        val ratchetStep = kdfRk(state.rootKey, dhOut)
        val newToken = deriveRoutingToken(ratchetStep.newRootKey, newEpoch, senderFp, recipientFp)
        return state.copy(
            rootKey = ratchetStep.newRootKey,
            recvChainKey = ratchetStep.newChainKey, // Bob's new recv chain (= Alice's new send)
            // sendChainKey is unchanged — Bob's own outgoing chain has not rotated
            routingToken = newToken,
            epoch = newEpoch,
            theirEkPub = aliceNewEkPub.copyOf(),
        )
    }

    // ---------------------------------------------------------------------------
    // Signed blob builders (canonical byte encoding per §9.3)
    // ---------------------------------------------------------------------------

    /**
     * Build the 116-byte canonical blob signed in an EpochRotation message.
     * Caller signs this with Ed25519(SigIK.priv, blob).
     */
    fun epochRotationSignedBlob(
        epoch: Int,
        opkId: Int,
        newEkPub: ByteArray,
        ts: Long,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): ByteArray =
        intToBeBytes(PROTOCOL_VERSION) +
            intToBeBytes(epoch) +
            intToBeBytes(opkId) +
            newEkPub + // 32 bytes
            longToBeBytes(ts) +
            senderFp + // 32 bytes
            recipientFp // 32 bytes

    /**
     * Build the 80-byte canonical blob signed in a RatchetAck message.
     * Caller signs this with Ed25519(SigIK.priv, blob).
     */
    fun ratchetAckSignedBlob(
        epochSeen: Int,
        ts: Long,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ): ByteArray =
        intToBeBytes(PROTOCOL_VERSION) +
            intToBeBytes(epochSeen) +
            longToBeBytes(ts) +
            senderFp +
            recipientFp

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
     * PKCS#7 padding to a fixed size.
     * The pad byte value equals the number of padding bytes appended, so unpadding
     * is unambiguous even if the plaintext ends with a zero byte.
     */
    private fun padToFixedSize(
        data: ByteArray,
        size: Int,
    ): ByteArray {
        require(data.size in 1 until size) { "plaintext (${data.size} bytes) must be in [1, ${size - 1}]" }
        val padByte = (size - data.size).toByte()
        return data.copyOf(size).also { padded ->
            for (i in data.size until size) padded[i] = padByte
        }
    }

    private fun unpad(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "padded data is empty" }
        val padCount = data.last().toInt() and 0xFF
        require(padCount > 0 && padCount <= data.size) { "invalid PKCS#7 padding byte: $padCount" }
        for (i in data.size - padCount until data.size) {
            require(data[i] == padCount.toByte()) { "invalid PKCS#7 padding at index $i" }
        }
        return data.copyOfRange(0, data.size - padCount)
    }
}
