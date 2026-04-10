package net.af0.where.e2ee

/**
 * Session fingerprint: SHA-256(EK.pub). 32 bytes.
 * Used in AAD for location frames, in routing token derivation, and as the session-scoped
 * peer identifier.
 */
internal fun fingerprint(ekPub: ByteArray): ByteArray = sha256(ekPub)

/**
 * Safety number for out-of-band verification.
 * Returns the full 32-byte SHA-256(lower_EK.pub || higher_EK.pub), where
 * "lower/higher" is lexicographic order of the two bootstrap EK.pub values.
 *
 * IMPORTANT: callers must pass the *bootstrap* EK_A.pub and EK_B.pub (the keys
 * exchanged at pairing time), not the current-epoch ephemeral keys. Use
 * SessionState.aliceEkPub / SessionState.bobEkPub, which are stable for the
 * lifetime of the session.
 *
 * Display with formatSafetyNumber() as 8 groups of 5 decimal digits.
 */
fun safetyNumber(
    localEkPub: ByteArray,
    remoteEkPub: ByteArray,
): ByteArray {
    val cmp = localEkPub.compare(remoteEkPub)
    return if (cmp <= 0) sha256(localEkPub + remoteEkPub) else sha256(remoteEkPub + localEkPub)
}

/**
 * Format 32-byte safety number as two lines of 4 groups of 5 decimal digits each.
 * Each group is derived from 4 bytes interpreted as uint32, taken modulo 100000,
 * and zero-padded to 5 digits. Groups within a line are separated by spaces;
 * lines are separated by newlines.
 */
fun formatSafetyNumber(sn: ByteArray): String {
    require(sn.size == 32) { "safety number must be 32 bytes" }
    val groups = (0 until 8).map { i ->
        val offset = i * 4
        val v = ((sn[offset].toLong() and 0xFF) shl 24) or
            ((sn[offset + 1].toLong() and 0xFF) shl 16) or
            ((sn[offset + 2].toLong() and 0xFF) shl 8) or
            (sn[offset + 3].toLong() and 0xFF)
        (v % 100000L).toString().padStart(5, '0')
    }
    return groups.take(4).joinToString(" ") + "\n" + groups.drop(4).joinToString(" ")
}

private fun ByteArray.compare(other: ByteArray): Int {
    for (i in 0 until minOf(size, other.size)) {
        val a = this[i].toInt() and 0xFF
        val b = other[i].toInt() and 0xFF
        if (a != b) return a - b
    }
    return size - other.size
}
