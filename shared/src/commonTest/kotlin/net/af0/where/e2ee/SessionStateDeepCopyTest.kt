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
 * SessionState.keyFields() in Types.kt) — a field added to the constructor but missed
 * in deepCopy() fails silently there. This test catches that by asserting every field is a
 * genuinely distinct buffer after copying.
 */
class SessionStateDeepCopyTest {
    private fun bytes(tag: Int): ByteArray = ByteArray(4) { tag.toByte() }

    private fun sampleState(): SessionState =
        SessionState(
            rootKey = RootKey(bytes(1)),
            sendChainKey = ChainKey(bytes(2)),
            recvChainKey = ChainKey(bytes(3)),
            sendToken = RoutingToken(bytes(4)),
            recvToken = RoutingToken(bytes(5)),
            sendSeq = 0L,
            recvSeq = 0L,
            localDhPriv = DhPrivateKey(bytes(6)),
            localDhPub = DhPublicKey(bytes(7)),
            remoteDhPub = DhPublicKey(bytes(8)),
            aliceEkPub = DhPublicKey(bytes(9)),
            bobEkPub = DhPublicKey(bytes(10)),
            aliceFp = Fingerprint(bytes(11)),
            bobFp = Fingerprint(bytes(12)),
            localFp = Fingerprint(bytes(13)),
            remoteFp = Fingerprint(bytes(14)),
            prevSendToken = RoutingToken(bytes(15)),
            prevSendHeaderKey = HeaderKey(bytes(16)),
            isAlice = true,
            skippedMessageKeys = mapOf("k" to SkippedMessageKey(bytes(17))),
            headerKey = HeaderKey(bytes(18)),
            sendHeaderKey = HeaderKey(bytes(19)),
            nextHeaderKey = HeaderKey(bytes(20)),
        )

    @Test
    fun deepCopyProducesIndependentBuffersForEveryByteArrayField() {
        val original = sampleState()
        val copy = original.deepCopy()

        assertNotSame(original.rootKey.bytes, copy.rootKey.bytes)
        assertNotSame(original.sendChainKey.bytes, copy.sendChainKey.bytes)
        assertNotSame(original.recvChainKey.bytes, copy.recvChainKey.bytes)
        assertNotSame(original.sendToken.bytes, copy.sendToken.bytes)
        assertNotSame(original.recvToken.bytes, copy.recvToken.bytes)
        assertNotSame(original.localDhPriv.bytes, copy.localDhPriv.bytes)
        assertNotSame(original.localDhPub.bytes, copy.localDhPub.bytes)
        assertNotSame(original.remoteDhPub.bytes, copy.remoteDhPub.bytes)
        assertNotSame(original.aliceEkPub.bytes, copy.aliceEkPub.bytes)
        assertNotSame(original.bobEkPub.bytes, copy.bobEkPub.bytes)
        assertNotSame(original.aliceFp.bytes, copy.aliceFp.bytes)
        assertNotSame(original.bobFp.bytes, copy.bobFp.bytes)
        assertNotSame(original.localFp.bytes, copy.localFp.bytes)
        assertNotSame(original.remoteFp.bytes, copy.remoteFp.bytes)
        assertNotSame(original.prevSendToken.bytes, copy.prevSendToken.bytes)
        assertNotSame(original.prevSendHeaderKey.bytes, copy.prevSendHeaderKey.bytes)
        assertNotSame(original.headerKey.bytes, copy.headerKey.bytes)
        assertNotSame(original.sendHeaderKey.bytes, copy.sendHeaderKey.bytes)
        assertNotSame(original.nextHeaderKey.bytes, copy.nextHeaderKey.bytes)
        assertNotSame(original.skippedMessageKeys.getValue("k").bytes, copy.skippedMessageKeys.getValue("k").bytes)

        // Content must still match despite being distinct buffers.
        assertTrue(original == copy, "deep copy must remain equal in content")

        // Mutating (zeroizing) one copy's key material must not affect the other.
        copy.rootKey.zeroize()
        assertFalse(original.rootKey.bytes.all { it == 0.toByte() }, "zeroizing the copy's rootKey must not affect the original")
    }
}
