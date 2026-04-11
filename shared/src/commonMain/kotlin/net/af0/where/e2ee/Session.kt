package net.af0.where.e2ee

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * High-level session operations: encrypt a location update and decrypt an incoming update.
 *
 * All functions are pure (immutable SessionState in, new SessionState out) so callers
 * can easily audit state transitions and write deterministic tests.
 */
@OptIn(ExperimentalEncodingApi::class)
object Session {
    private const val AAD_PREFIX = "Where-v1-Location"
    private const val PROTOCOL_VERSION = 1

    // §7.4: pad plaintext to a fixed block size for traffic-analysis resistance.
    internal const val PADDING_SIZE = 512

    /**
     * Maximum allowed gap between the last received seq and the incoming seq.
     */
    private const val MAX_GAP = 1024L

    /**
     * Encrypt one location update for a single peer.
     *
     * Returns the updated SessionState and the ciphertext.
     *
     * Preparatory DH: If [nextOpkId] is provided, the DH parameters are included in the
     * encrypted body of the CURRENT message, but the DH ratchet is performed for the
     * NEXT message.
     */
    fun encryptLocation(
        state: SessionState,
        location: LocationPlaintext,
        senderFp: ByteArray,
        recipientFp: ByteArray,
        nextRecvToken: ByteArray,
        nextOpkId: Int? = null,
        nextBobOpkPub: ByteArray? = null,
        aliceNewEkPriv: ByteArray? = null,
        aliceNewEkPub: ByteArray? = null,
    ): Pair<SessionState, ByteArray> {
        require(state.sendSeq != Long.MAX_VALUE) {
            "seq overflow — session must be re-keyed before sending more messages"
        }

        // Current message is ALWAYS encrypted with the current chain key.
        val step = kdfCk(state.sendChainKey)
        val seq = state.sendSeq + 1
        val aad = buildLocationAad(senderFp, recipientFp, seq)

        val plaintext = padToFixedSize(encodeLocation(location, nextRecvToken, nextOpkId, aliceNewEkPub), PADDING_SIZE)
        val ct = aeadEncrypt(step.messageKey, step.messageNonce, plaintext, aad)

        // The state returned is for the NEXT message.
        var newState =
            state.copy(
                sendChainKey = step.newChainKey,
                sendSeq = seq,
                sendToken = nextRecvToken.copyOf()
            )

        // If we are rotating, the NEXT message will use the new root/chain keys.
        if (nextOpkId != null && nextBobOpkPub != null && aliceNewEkPriv != null && aliceNewEkPub != null) {
            val dhOut = x25519(aliceNewEkPriv, nextBobOpkPub)
            val ratchetStep = kdfRk(newState.rootKey, dhOut)
            newState = newState.copy(
                rootKey = ratchetStep.newRootKey,
                sendChainKey = ratchetStep.newChainKey
            )
            dhOut.fill(0)
            aliceNewEkPriv.fill(0)
        }

        // Security (§5.5, §11): zero out ephemeral keys after use
        step.messageKey.fill(0)
        step.messageNonce.fill(0)
        plaintext.fill(0)

        return newState to ct
    }

    /**
     * Decrypt one incoming location frame.
     *
     * Returns the updated SessionState and the plaintext location.
     */
    @Throws(IllegalArgumentException::class)
    fun decryptLocation(
        state: SessionState,
        ct: ByteArray,
        seq: Long,
        senderFp: ByteArray,
        recipientFp: ByteArray,
        bobOpkPrivGetter: (Int) -> ByteArray?,
    ): Pair<SessionState, LocationPlaintext> {
        require(seq > state.recvSeq) { "replay — seq $seq must be greater than state.recvSeq ${state.recvSeq}" }

        val stepsNeeded = seq - state.recvSeq
        require(stepsNeeded <= MAX_GAP + 1) {
            "seq gap ${stepsNeeded - 1} exceeds maximum $MAX_GAP — session may be desynchronized"
        }

        val aad = buildLocationAad(senderFp, recipientFp, seq)

        var chainKey = state.recvChainKey.copyOf()
        var step: ChainStep? = null
        repeat(stepsNeeded.toInt()) {
            step = kdfCk(chainKey)
            chainKey.fill(0)
            chainKey = step!!.newChainKey
        }
        val finalStep = step!!

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

        val obj = Json.decodeFromString<JsonObject>(unpadded.decodeToString())
        val location = decodeLocationFromObj(obj)
        val nextToken = Base64.decode(obj["next_token"]!!.jsonPrimitive.content)

        var newState =
            state.copy(
                recvChainKey = chainKey, // advanced by kdfCk above
                recvSeq = seq,
                recvToken = nextToken
            )

        // If the message contained integrated DH parameters, advance the DH ratchet for the NEXT message.
        val ratchet = obj["ratchet"]?.let { if (it is JsonObject) it else null }
        if (ratchet != null) {
            val opkId = ratchet["opk_id"]!!.jsonPrimitive.long.toInt()
            val aliceNewEkPub = Base64.decode(ratchet["ek_pub"]!!.jsonPrimitive.content)
            val bobOpkPriv = bobOpkPrivGetter(opkId)
            if (bobOpkPriv != null) {
                val dhOut = x25519(bobOpkPriv, aliceNewEkPub)
                val ratchetStep = kdfRk(newState.rootKey, dhOut)
                newState = newState.copy(
                    rootKey = ratchetStep.newRootKey,
                    recvChainKey = ratchetStep.newChainKey
                )
                dhOut.fill(0)
                bobOpkPriv.fill(0)
            }
        }

        // Security (§5.5, §11): zero out ephemeral keys after use
        finalStep.messageKey.fill(0)
        finalStep.messageNonce.fill(0)
        plaintext.fill(0)
        unpadded.fill(0)

        return newState to location
    }

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

    private fun encodeLocation(
        loc: LocationPlaintext,
        nextToken: ByteArray,
        nextOpkId: Int?,
        aliceNewEkPub: ByteArray?
    ): ByteArray =
        buildJsonObject {
            put("lat", loc.lat)
            put("lng", loc.lng)
            put("acc", loc.acc)
            put("ts", loc.ts)
            put("next_token", Base64.encode(nextToken))
            if (nextOpkId != null && aliceNewEkPub != null) {
                put("ratchet", buildJsonObject {
                    put("opk_id", nextOpkId)
                    put("ek_pub", Base64.encode(aliceNewEkPub))
                })
            }
        }.let { Json.encodeToString(it) }.encodeToByteArray()

    private fun decodeLocationFromObj(obj: JsonObject): LocationPlaintext {
        return LocationPlaintext(
            lat = obj["lat"]!!.jsonPrimitive.double,
            lng = obj["lng"]!!.jsonPrimitive.double,
            acc = obj["acc"]!!.jsonPrimitive.double,
            ts = obj["ts"]!!.jsonPrimitive.long,
        )
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
