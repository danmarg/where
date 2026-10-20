package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.PADDING_SIZE
import net.af0.where.e2ee.Session

/** Session.unpad against a plain reference: the content ends at the last nonzero byte, which must be 0x80. */
object UnpadFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val marker = data.indexOfLast { it != 0.toByte() }
        val expected = if (marker >= 0 && data[marker] == 0x80.toByte()) data.copyOfRange(0, marker) else null
        val actual = refusedOnlyWithWhereException { Session.unpad(data.copyOf()) }
        if (expected == null) {
            fuzzCheck(actual == null) { "accepted bad padding" }
            return
        }
        fuzzCheck(actual != null && actual.contentEquals(expected)) { "unpad returned ${actual?.size} bytes, expected ${expected.size}" }
        if (expected.isNotEmpty() && expected.size < PADDING_SIZE) {
            fuzzCheck(Session.unpad(Session.padToFixedSize(expected, PADDING_SIZE)).contentEquals(expected)) { "pad/unpad round trip" }
        }
    }
}
