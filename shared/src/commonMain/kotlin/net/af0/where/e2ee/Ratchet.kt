package net.af0.where.e2ee

/*
 * Ratchet KDF functions.
 *
 * KDF_RK – DH ratchet step. Inputs the current root key as HKDF salt and a fresh DH output
 * as IKM. Produces 64 bytes: [new_root_key (32) || new_chain_key (32)].
 *
 * KDF_CK – Symmetric chain step. A single HKDF-SHA-256 call producing 76 bytes:
 *   [new_chain_key (32) || message_key (32) || message_nonce (12)].
 *   The old chain key MUST be discarded immediately after this call.
 */

/**
 * DH ratchet step.
 * @param rootKey      Current 32-byte root key (used as HKDF salt).
 * @param dhOutput     32-byte X25519 shared secret.
 */
internal fun kdfRk(
    rootKey: ByteArray,
    dhOutput: ByteArray,
): RatchetStep {
    val out =
        hkdfSha256(
            ikm = dhOutput,
            salt = rootKey,
            info = INFO_RATCHET_STEP.encodeToByteArray(),
            length = 64,
        )
    return RatchetStep(
        newRootKey = out.copyOfRange(0, 32),
        newChainKey = out.copyOfRange(32, 64),
    )
}

/**
 * Symmetric chain step.
 * @param chainKey  Current 32-byte chain key. MUST be zeroed by caller after this returns.
 */
internal fun kdfCk(chainKey: ByteArray): ChainStep {
    val out =
        hkdfSha256(
            ikm = chainKey,
            salt = null,
            info = INFO_MSG_STEP.encodeToByteArray(),
            length = 76,
        )
    val step =
        ChainStep(
            newChainKey = out.copyOfRange(0, 32),
            messageKey = out.copyOfRange(32, 64),
            messageNonce = out.copyOfRange(64, 76),
        )
    out.fill(0)
    return step
}

/**
 * Derive the routing token for a given ratchet state and direction from the root key.
 *
 * The direction is implicit in the (senderFp, recipientFp) pair: each party uses themselves
 * as sender and the peer as recipient. This eliminates the boolean ambiguity and makes
 * the direction semantically clear at each call site.
 *
 * info = "Where-v1-RoutingToken" || senderFp (32 bytes) || recipientFp (32 bytes)
 *
 * Returns 16 bytes.
 */
internal fun deriveRoutingToken(
    rootKey: ByteArray,
    senderFp: ByteArray,
    recipientFp: ByteArray,
): ByteArray {
    val info = INFO_ROUTING_TOKEN.encodeToByteArray() + senderFp + recipientFp
    return hkdfSha256(ikm = rootKey, salt = null, info = info, length = 16)
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
