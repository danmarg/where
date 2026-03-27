package net.af0.where.e2ee

/**
 * Operations for building and verifying authenticated PreKeyBundle messages (§5.3, §9.3).
 *
 * A PreKeyBundle is Bob's batch of One-Time Pre-Key (OPK) public keys, authenticated with
 * HMAC-SHA-256 using kBundle (a session-derived key), so Alice can verify the keys are
 * authentic before using them for DH epoch rotation.
 */
object PreKeyBundleOps {
    private const val PROTOCOL_VERSION = 1

    /**
     * Compute the HMAC-SHA-256 MAC for a PreKeyBundle.
     *
     * @param token   The routing token (16 bytes) for this friendship session.
     * @param opks    The OPKs to include in the bundle.
     * @param kBundle 32-byte session-derived bundle authentication key.
     * @return 32-byte HMAC-SHA-256.
     */
    fun buildMac(
        token: ByteArray,
        opks: List<OPK>,
        kBundle: ByteArray,
    ): ByteArray {
        require(token.size == 16) { "token must be 16 bytes, got ${token.size}" }
        return hmacSha256(kBundle, signedData(token, opks))
    }

    /**
     * Verify a PreKeyBundle MAC.
     *
     * @param token   The routing token (16 bytes).
     * @param opks    The OPKs in the bundle.
     * @param mac     32-byte HMAC-SHA-256.
     * @param kBundle 32-byte session-derived bundle authentication key.
     * @return true iff the MAC is valid.
     */
    fun verify(
        token: ByteArray,
        opks: List<OPK>,
        mac: ByteArray,
        kBundle: ByteArray,
    ): Boolean {
        require(token.size == 16) { "token must be 16 bytes, got ${token.size}" }
        val expected = hmacSha256(kBundle, signedData(token, opks))
        if (expected.size != mac.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor mac[i].toInt())
        return diff == 0
    }

    /**
     * Canonical binary encoding for PreKeyBundle authentication.
     * Format: v(4) + token(16) + n_keys(4) + for each OPK in ascending id order: id(4) + pub(32).
     */
    internal fun signedData(
        token: ByteArray,
        opks: List<OPK>,
    ): ByteArray {
        val sorted = opks.sortedBy { it.id }
        var data = intToBeBytes(PROTOCOL_VERSION) + token + intToBeBytes(sorted.size)
        for (opk in sorted) {
            require(opk.pub.size == 32) { "OPK pub must be 32 bytes, got ${opk.pub.size}" }
            data += intToBeBytes(opk.id) + opk.pub
        }
        return data
    }
}
