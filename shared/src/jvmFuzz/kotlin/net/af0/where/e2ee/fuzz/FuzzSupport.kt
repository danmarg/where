package net.af0.where.e2ee.fuzz

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import net.af0.where.e2ee.WhereException

/**
 * Reads structured fields from one fuzz input. Reads past the end give zeros or fewer bytes, so
 * every input decodes to something and mutations anywhere stay meaningful.
 */
internal class FuzzInput(private val data: ByteArray) {
    private var pos = 0

    val remaining: Int get() = data.size - pos

    fun byte(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else 0

    fun long(): Long {
        var r = 0L
        repeat(8) { r = (r shl 8) or byte().toLong() }
        return r
    }

    fun bytes(n: Int): ByteArray {
        val end = minOf(data.size, pos + maxOf(0, n))
        return data.copyOfRange(pos, end).also { pos = end }
    }

    /** Exactly [n] bytes, zero-filled past the end of the input. */
    fun fixed(n: Int): ByteArray = bytes(n).copyOf(n)

    /** A length-prefixed field of at most [max] bytes. */
    fun sized(max: Int): ByteArray = bytes(byte() % (max + 1))

    fun rest(): ByteArray = bytes(remaining)
}

/**
 * The property every target checks: a malformed input is refused with a typed [WhereException].
 * Returns null for that case; any other exception reaches Jazzer (or the corpus test) as a
 * finding.
 */
internal inline fun <T> refusedOnlyWithWhereException(block: () -> T): T? =
    try {
        block()
    } catch (e: WhereException) {
        null
    }

internal fun fuzzCheck(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw FuzzPropertyViolation(message())
}

internal class FuzzPropertyViolation(message: String) : AssertionError(message)

internal object Libsodium {
    init {
        LibsodiumInitializer.initializeWithCallback {}
        check(LibsodiumInitializer.isInitialized()) { "libsodium did not initialize" }
    }

    fun ensure() = Unit
}
