package net.af0.where.e2ee

/**
 * Session fingerprint: SHA-256(EK.pub). 32 bytes.
 * Used in AAD for location frames, in routing token derivation, and as the session-scoped
 * peer identifier.
 */
internal fun fingerprint(ekPub: ByteArray): ByteArray = sha256(ekPub)

/**
 * Safety number for out-of-band verification.
 * Returns the full 64-byte SHA-512(lower_EK.pub || higher_EK.pub), where
 * "lower/higher" is lexicographic order of the two bootstrap EK.pub values.
 *
 * IMPORTANT: callers must pass the *bootstrap* EK_A.pub and EK_B.pub (the keys
 * exchanged at pairing time), not the current-epoch ephemeral keys. Use
 * SessionState.aliceEkPub / SessionState.bobEkPub, which are stable for the
 * lifetime of the session.
 *
 * Display with formatSafetyNumber() as 12 groups of 5 decimal digits.
 */
fun safetyNumber(
    localEkPub: ByteArray,
    remoteEkPub: ByteArray,
): ByteArray {
    val cmp = localEkPub.compare(remoteEkPub)
    val input = if (cmp <= 0) localEkPub + remoteEkPub else remoteEkPub + localEkPub
    val ikm = sha256(input)
    return hkdfSha256(
        ikm = ikm,
        salt = null,
        info = "Where-v1-SafetyNumber".encodeToByteArray(),
        length = 60,
    )
}

/**
 * Format the 64-byte safety number as 12 groups of 5 decimal digits each.
 * Each group is derived from 5 bytes interpreted as a 40-bit big-endian value,
 * taken modulo 100,000, and zero-padded to 5 digits. Groups are separated by spaces;
 * every 4 groups are followed by a newline.
 */
fun formatSafetyNumber(sn: ByteArray): String {
    require(sn.size == 60) { "safety number must be 60 bytes (12 groups of 5)" }
    val groups =
        (0 until 12).map { i ->
            val offset = i * 5
            val v =
                ((sn[offset].toLong() and 0xFF) shl 32) or
                    ((sn[offset + 1].toLong() and 0xFF) shl 24) or
                    ((sn[offset + 2].toLong() and 0xFF) shl 16) or
                    ((sn[offset + 3].toLong() and 0xFF) shl 8) or
                    (sn[offset + 4].toLong() and 0xFF)
            (v % 100000L).toString().padStart(5, '0')
        }
    return groups.chunked(4).joinToString("\n") { it.joinToString(" ") }
}

private fun ByteArray.compare(other: ByteArray): Int {
    for (i in 0 until minOf(size, other.size)) {
        val a = this[i].toInt() and 0xFF
        val b = other[i].toInt() and 0xFF
        if (a != b) return a - b
    }
    return size - other.size
}
