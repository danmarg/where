package net.af0.where.e2ee

/**
 * Pure-Kotlin HKDF-SHA-256 (RFC 5869) built on top of the platform hmacSha256 primitive.
 * Having HKDF in common code (rather than duplicated in each platform) keeps the protocol
 * logic auditable in one place.
 *
 * @param ikm  Input key material
 * @param salt Salt (use null or empty to default to 32 zero bytes per RFC 5869 §2.2)
 * @param info Context / domain-separation string
 * @param length Number of output bytes requested (must be ≤ 255 * 32)
 */
internal fun hkdfSha256(
    ikm: ByteArray,
    salt: ByteArray?,
    info: ByteArray,
    length: Int,
): ByteArray {
    require(length > 0) { "length must be > 0" }
    require(length <= 255 * 32) { "length exceeds HKDF maximum" }

    // Extract
    val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
    val prk = hmacSha256(effectiveSalt, ikm)

    // Expand
    val result = ByteArray(length)
    var t = ByteArray(0)
    var pos = 0
    var i = 1
    try {
        while (pos < length) {
            val stepInput = t + info + byteArrayOf(i.toByte())
            val nextT = hmacSha256(prk, stepInput)
            stepInput.fill(0)
            t.fill(0)
            t = nextT
            val toCopy = minOf(t.size, length - pos)
            t.copyInto(result, pos, 0, toCopy)
            pos += toCopy
            i++
        }
    } finally {
        prk.fill(0)
        t.fill(0)
    }
    return result
}
