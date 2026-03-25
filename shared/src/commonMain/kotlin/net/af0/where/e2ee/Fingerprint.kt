package net.af0.where.e2ee

/**
 * Identity fingerprint: SHA-256(IK.pub || SigIK.pub). 32 bytes.
 * Used in AAD for location frames, in routing token derivation, and in signed blobs.
 */
internal fun fingerprint(ikPub: ByteArray, sigIkPub: ByteArray): ByteArray =
    sha256(ikPub + sigIkPub)

/**
 * Safety number for out-of-band verification.
 * Both key-pairs are sorted lexicographically by IK.pub so that both parties
 * compute the same value independently.
 * Returns SHA-256(lower_IK.pub || lower_SigIK.pub || higher_IK.pub || higher_SigIK.pub).
 */
fun safetyNumber(
    localIkPub: ByteArray, localSigIkPub: ByteArray,
    remoteIkPub: ByteArray, remoteSigIkPub: ByteArray,
): ByteArray {
    val cmp = localIkPub.compare(remoteIkPub)
    return if (cmp <= 0) {
        sha256(localIkPub + localSigIkPub + remoteIkPub + remoteSigIkPub)
    } else {
        sha256(remoteIkPub + remoteSigIkPub + localIkPub + localSigIkPub)
    }
}

private fun ByteArray.compare(other: ByteArray): Int {
    for (i in 0 until minOf(size, other.size)) {
        val a = this[i].toInt() and 0xFF
        val b = other[i].toInt() and 0xFF
        if (a != b) return a - b
    }
    return size - other.size
}
