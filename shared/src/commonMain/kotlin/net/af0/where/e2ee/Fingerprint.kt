package net.af0.where.e2ee

/**
 * Session fingerprint: SHA-256(EK.pub). 32 bytes.
 * Used in AAD for location frames, in routing token derivation, and as the session-scoped
 * peer identifier.
 */
internal fun fingerprint(ekPub: ByteArray): ByteArray = sha256(ekPub)

/**
 * Safety number for out-of-band verification.
 * Returns the first 12 bytes of SHA-256(lower_EK.pub || higher_EK.pub), where
 * "lower/higher" is lexicographic order of the two EK.pub values.
 * Display as 6 groups of 4 decimal digits.
 */
fun safetyNumber(
    localEkPub: ByteArray,
    remoteEkPub: ByteArray,
): ByteArray {
    val cmp = localEkPub.compare(remoteEkPub)
    val full = if (cmp <= 0) sha256(localEkPub + remoteEkPub) else sha256(remoteEkPub + localEkPub)
    return full.copyOfRange(0, 12)
}

/**
 * Format 12-byte safety number as 6 groups of 4 decimal digits.
 */
fun formatSafetyNumber(sn: ByteArray): String {
    require(sn.size == 12) { "safety number must be 12 bytes" }
    val out = StringBuilder()
    for (i in 0 until 6) {
        val b0 = sn[i * 2].toInt() and 0xFF
        val b1 = sn[i * 2 + 1].toInt() and 0xFF
        val val16 = (b0 shl 8) or b1
        // (b0 << 8 | b1) % 10000 gives 4 digits
        out.append((val16 % 10000).toString().padStart(4, '0'))
        if (i < 5) out.append(" ")
    }
    return out.toString()
}

private fun ByteArray.compare(other: ByteArray): Int {
    for (i in 0 until minOf(size, other.size)) {
        val a = this[i].toInt() and 0xFF
        val b = other[i].toInt() and 0xFF
        if (a != b) return a - b
    }
    return size - other.size
}
