package net.af0.where.e2ee

/*
 * Ratchet KDF functions.
 *
 * KDF_RK – DH ratchet step. Inputs the current root key as HKDF salt and a fresh DH output
 * as IKM. Produces 96 bytes: [new_root_key (32) || new_chain_key (32) || new_header_key (32)].
 *
 * KDF_CK – Symmetric chain step using HMAC-SHA-256:
 *   message_key    = HMAC(chain_key, 0x01)
 *   new_chain_key  = HMAC(chain_key, 0x02)
 *   message_nonce  = HKDF-SHA-256(ikm=message_key, info="Where-v1-MsgNonce", length=12)
 *   The old chain key MUST be discarded immediately after this call.
 */

/**
 * DH ratchet step.
 * @param rootKey      Current 32-byte root key (used as HKDF salt).
 * @param dhOutput     32-byte X25519 shared secret.
 */
internal fun kdfRk(
    rootKey: RootKey,
    dhOutput: SharedSecret,
): RatchetStep {
    val out =
        hkdfSha256(
            ikm = dhOutput.bytes,
            salt = rootKey.bytes,
            info = INFO_RATCHET_STEP.encodeToByteArray(),
            length = 96,
        )
    try {
        return RatchetStep(
            newRootKey = RootKey(out.copyOfRange(0, 32)),
            newChainKey = ChainKey(out.copyOfRange(32, 64)),
            newHeaderKey = HeaderKey(out.copyOfRange(64, 96)),
        )
    } finally {
        out.zeroize()
    }
}

/**
 * One DH-then-KDF_RK sub-step (§8.3/§4.4): computes the DH output against remotePub using
 * localPriv, validates it isn't a low-order-point collapse (§4.3), derives the next
 * root/chain/header key from currentRootKey, and zeroizes the intermediate DH output.
 *
 * This is the arithmetic shared by all three places a DH ratchet sub-step happens:
 * Session.performDhRatchet's receive sub-step (localPriv = the existing session key),
 * its send sub-step (localPriv = a freshly generated key), and Alice's bootstrap eager
 * ratchet in KeyExchange.aliceProcessInit (also a freshly generated key). Only this
 * inner step is shared — the surrounding state wiring (header keys, tokens, pn/pr,
 * epoch-0 vs epoch-1 field handling) differs enough between call sites that unifying
 * it would obscure more than it simplifies.
 */
internal fun dhRatchetSubStep(
    localPriv: DhPrivateKey,
    remotePub: DhPublicKey,
    currentRootKey: RootKey,
): RatchetStep {
    val dhOut = dh(localPriv, remotePub)
    KeyExchange.requireNonZeroSharedSecret(dhOut)
    return try {
        kdfRk(currentRootKey, dhOut)
    } finally {
        dhOut.zeroize()
    }
}

internal fun kdfCk(chainKey: ChainKey): ChainStep {
    val messageKey = hmacSha256(chainKey.bytes, byteArrayOf(0x01))
    val newChainKey = hmacSha256(chainKey.bytes, byteArrayOf(0x02))
    val nonce =
        hkdfSha256(
            ikm = messageKey,
            salt = null,
            info = INFO_MSG_NONCE.encodeToByteArray(),
            length = 12,
        )
    return ChainStep(
        newChainKey = ChainKey(newChainKey),
        messageKey = MessageKey(messageKey),
        messageNonce = MessageNonce(nonce),
    )
}

/**
 * Derive the routing token for one direction from [ikm].
 * Call it through [Parties.sendToken] / [Parties.recvToken], which fix the direction.
 *
 * info = "Where-v1-RoutingToken" || sender (32 bytes) || recipient (32 bytes)
 *
 * Returns 16 bytes.
 */
internal fun deriveRoutingToken(
    ikm: TokenSeed,
    sender: Fingerprint,
    recipient: Fingerprint,
): RoutingToken {
    val info = INFO_ROUTING_TOKEN.encodeToByteArray() + sender.bytes + recipient.bytes
    return RoutingToken(hkdfSha256(ikm = (ikm as KeyBytes).bytes, salt = null, info = info, length = 16))
}

/**
 * Message AAD. Call it through [Parties.sendAad] / [Parties.recvAad], which fix the
 * direction. An empty [ackRemoteDhPub] is encoded as 32 zero bytes.
 */
internal fun buildMessageAad(
    sender: Fingerprint,
    recipient: Fingerprint,
    seq: Long,
    dhPub: DhPublicKey,
    ackRemoteDhPub: DhPublicKey,
): ByteArray {
    val paddedAck = if (ackRemoteDhPub.isEmpty()) ByteArray(32) else ackRemoteDhPub.bytes
    return AAD_PREFIX.encodeToByteArray() +
        intToBeBytes(PROTOCOL_VERSION) +
        sender.bytes +
        recipient.bytes +
        longToBeBytes(seq) +
        dhPub.bytes +
        paddedAck
}

// ---------------------------------------------------------------------------
// Encoding helpers
// ---------------------------------------------------------------------------

internal fun intToBeBytes(v: Int): ByteArray =
    byteArrayOf(
        (v shr 24).toByte(),
        (v shr 16).toByte(),
        (v shr 8).toByte(),
        v.toByte(),
    )

internal fun longToBeBytes(v: Long): ByteArray =
    byteArrayOf(
        (v shr 56).toByte(),
        (v shr 48).toByte(),
        (v shr 40).toByte(),
        (v shr 32).toByte(),
        (v shr 24).toByte(),
        (v shr 16).toByte(),
        (v shr 8).toByte(),
        v.toByte(),
    )

internal fun bytesToLong(bytes: ByteArray): Long {
    require(bytes.size >= 8) { "at least 8 bytes required to decode long" }
    return ((bytes[0].toLong() and 0xFF) shl 56) or
        ((bytes[1].toLong() and 0xFF) shl 48) or
        ((bytes[2].toLong() and 0xFF) shl 40) or
        ((bytes[3].toLong() and 0xFF) shl 32) or
        ((bytes[4].toLong() and 0xFF) shl 24) or
        ((bytes[5].toLong() and 0xFF) shl 16) or
        ((bytes[6].toLong() and 0xFF) shl 8) or
        (bytes[7].toLong() and 0xFF)
}
