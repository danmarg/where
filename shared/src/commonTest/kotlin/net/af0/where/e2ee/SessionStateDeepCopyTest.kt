package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Regression guard for deepCopy() (§5.5): every ByteArray field in SessionState must be
 * copied into a fresh buffer, not merely referenced, or zeroizing one copy's key material
 * would corrupt the other copy's live session state. This is a maintenance trap because
 * deepCopy()'s field list can't be derived from a single source of truth (Kotlin's data
 * class copy() requires named arguments) the way equals()/hashCode() now are (see
 * SessionState.byteArrayFields() in Types.kt) — a field added to the constructor but missed
 * in deepCopy() fails silently there. This test catches that by asserting every field is a
 * genuinely distinct buffer after copying.
 */
class SessionStateDeepCopyTest {
    private fun bytes(tag: Int): ByteArray = ByteArray(4) { tag.toByte() }

    private fun sampleState(): SessionState =
        SessionState(
            rootKey = bytes(1),
            sendChainKey = bytes(2),
            recvChainKey = bytes(3),
            sendToken = bytes(4),
            recvToken = bytes(5),
            sendSeq = 0L,
            recvSeq = 0L,
            localDhPriv = bytes(6),
            localDhPub = bytes(7),
            remoteDhPub = bytes(8),
            aliceEkPub = bytes(9),
            bobEkPub = bytes(10),
            aliceFp = bytes(11),
            bobFp = bytes(12),
            localFp = bytes(13),
            remoteFp = bytes(14),
            prevSendToken = bytes(15),
            prevSendHeaderKey = bytes(16),
            isAlice = true,
            skippedMessageKeys = mapOf("k" to bytes(17)),
            headerKey = bytes(18),
            sendHeaderKey = bytes(19),
            nextHeaderKey = bytes(20),
        )

    @Test
    fun deepCopyProducesIndependentBuffersForEveryByteArrayField() {
        val original = sampleState()
        val copy = original.deepCopy()

        assertNotSame(original.rootKey, copy.rootKey)
        assertNotSame(original.sendChainKey, copy.sendChainKey)
        assertNotSame(original.recvChainKey, copy.recvChainKey)
        assertNotSame(original.sendToken, copy.sendToken)
        assertNotSame(original.recvToken, copy.recvToken)
        assertNotSame(original.localDhPriv, copy.localDhPriv)
        assertNotSame(original.localDhPub, copy.localDhPub)
        assertNotSame(original.remoteDhPub, copy.remoteDhPub)
        assertNotSame(original.aliceEkPub, copy.aliceEkPub)
        assertNotSame(original.bobEkPub, copy.bobEkPub)
        assertNotSame(original.aliceFp, copy.aliceFp)
        assertNotSame(original.bobFp, copy.bobFp)
        assertNotSame(original.localFp, copy.localFp)
        assertNotSame(original.remoteFp, copy.remoteFp)
        assertNotSame(original.prevSendToken, copy.prevSendToken)
        assertNotSame(original.prevSendHeaderKey, copy.prevSendHeaderKey)
        assertNotSame(original.headerKey, copy.headerKey)
        assertNotSame(original.sendHeaderKey, copy.sendHeaderKey)
        assertNotSame(original.nextHeaderKey, copy.nextHeaderKey)
        assertNotSame(original.skippedMessageKeys.getValue("k"), copy.skippedMessageKeys.getValue("k"))

        // Content must still match despite being distinct buffers.
        assertTrue(original == copy, "deep copy must remain equal in content")

        // Mutating (zeroizing) one copy's key material must not affect the other.
        copy.rootKey.zeroize()
        assertFalse(original.rootKey.all { it == 0.toByte() }, "zeroizing the copy's rootKey must not affect the original")
    }
}
