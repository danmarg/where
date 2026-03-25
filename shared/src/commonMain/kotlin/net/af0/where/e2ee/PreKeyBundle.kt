package net.af0.where.e2ee

/**
 * Operations for building and verifying signed PreKeyBundle messages (§5.3, §9.3).
 *
 * A PreKeyBundle is Bob's batch of One-Time Pre-Key (OPK) public keys, signed with his
 * Ed25519 SigIK, so that Alice can verify the keys are authentic before using them for
 * DH epoch rotation.
 */
object PreKeyBundleOps {

    private const val PROTOCOL_VERSION = 1

    /**
     * Compute the Ed25519 signature for a PreKeyBundle.
     *
     * @param token     The routing token (16 bytes) for this friendship session.
     * @param opks      The OPKs to include in the bundle.
     * @param sigIkPriv Bob's Ed25519 signing private key (32 bytes).
     * @return 64-byte Ed25519 signature.
     */
    fun buildSignature(token: ByteArray, opks: List<OPK>, sigIkPriv: ByteArray): ByteArray {
        require(token.size == 16) { "token must be 16 bytes, got ${token.size}" }
        return ed25519Sign(sigIkPriv, signedData(token, opks))
    }

    /**
     * Verify a PreKeyBundle signature.
     *
     * @param token     The routing token (16 bytes) that was used when signing.
     * @param opks      The OPKs in the bundle.
     * @param sig       64-byte Ed25519 signature.
     * @param sigIkPub  Bob's Ed25519 signing public key (32 bytes).
     * @return true iff the signature is valid.
     */
    fun verify(token: ByteArray, opks: List<OPK>, sig: ByteArray, sigIkPub: ByteArray): Boolean {
        require(token.size == 16) { "token must be 16 bytes, got ${token.size}" }
        return ed25519Verify(sigIkPub, signedData(token, opks), sig)
    }

    /**
     * Canonical binary encoding for PreKeyBundle signatures.
     *
     * Format (total: 24 + 36*n bytes):
     *   v        (4 bytes, big-endian uint32 = 1)
     *   token    (16 bytes)
     *   n_keys   (4 bytes, big-endian uint32)
     *   for each OPK in ascending id order:
     *     id     (4 bytes, big-endian uint32)
     *     pub    (32 bytes)
     *
     * OPKs are sorted by id to ensure the encoding is deterministic regardless of input order.
     */
    internal fun signedData(token: ByteArray, opks: List<OPK>): ByteArray {
        val sorted = opks.sortedBy { it.id }
        var data = intToBeBytes(PROTOCOL_VERSION) + token + intToBeBytes(sorted.size)
        for (opk in sorted) {
            require(opk.pub.size == 32) { "OPK pub must be 32 bytes, got ${opk.pub.size}" }
            data += intToBeBytes(opk.id) + opk.pub
        }
        return data
    }
}
