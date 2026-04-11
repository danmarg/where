package net.af0.where.e2ee

/*
 * Ratchet KDF functions.
 *
 * KDF_CK – Symmetric chain step. A single HKDF-SHA-256 call producing 76 bytes:
 *   [new_chain_key (32) || message_key (32) || message_nonce (12)].
 *   The old chain key MUST be discarded immediately after this call.
 */

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
