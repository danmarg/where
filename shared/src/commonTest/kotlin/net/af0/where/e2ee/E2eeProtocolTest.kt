package net.af0.where.e2ee

import kotlin.test.*

class E2eeProtocolTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testBucketSortingOrder() {
        val rootKey = ByteArray(32) { 0x01.toByte() }
        val localKeyPair = generateX25519KeyPair()
        val remoteDhPubCurrent = ByteArray(32) { 0x02.toByte() }
        val remoteDhPubLast = ByteArray(32) { 0x03.toByte() }
        val remoteDhPubAncient = ByteArray(32) { 0x04.toByte() }
        val remoteDhPubNew = ByteArray(32) { 0x05.toByte() }

        val seenKeys = LinkedHashSet<String>()
        seenKeys.add(remoteDhPubAncient.toHex())

        val session = SessionState(
            rootKey = rootKey,
            recvChainKey = ByteArray(32),
            sendChainKey = ByteArray(32),
            headerKey = ByteArray(32) { 0x10.toByte() },
            nextHeaderKey = ByteArray(32) { 0x11.toByte() },
            localDhPriv = localKeyPair.priv,
            localDhPub = localKeyPair.pub,
            remoteDhPub = remoteDhPubCurrent,
            lastRemoteDhPub = remoteDhPubLast,
            retiredDhPubs = seenKeys,
            isAlice = true,
            aliceFp = ByteArray(32),
            bobFp = ByteArray(32),
            sendToken = ByteArray(16),
            recvToken = ByteArray(16),
            prevSendToken = ByteArray(16),
            aliceEkPub = ByteArray(32),
            bobEkPub = ByteArray(32),
            sendSeq = 0,
            recvSeq = 0,
            isSendTokenPending = false
        )

        val dummyAck = ByteArray(32)

        // Mock headers for different buckets
        val hCurrent = Session.DecryptedHeader(remoteDhPubCurrent, dummyAck, 10, 5) // Bucket 1
        val hLast = Session.DecryptedHeader(remoteDhPubLast, dummyAck, 8, 4)       // Bucket 0
        val hAncient = Session.DecryptedHeader(remoteDhPubAncient, dummyAck, 6, 3) // Bucket -1
        val hNew = Session.DecryptedHeader(remoteDhPubNew, dummyAck, 12, 6)         // Bucket 2

        // Payloads (mapped by their headers)
        val pCurrent = EncryptedMessagePayload(1, byteArrayOf(), byteArrayOf())
        val pLast = EncryptedMessagePayload(1, byteArrayOf(), byteArrayOf())
        val pAncient = EncryptedMessagePayload(1, byteArrayOf(), byteArrayOf())
        val pNew = EncryptedMessagePayload(1, byteArrayOf(), byteArrayOf())

        // Input list in random order
        val input = listOf(
            hCurrent to pCurrent,
            hAncient to pAncient,
            hNew to pNew,
            hLast to pLast
        )

        // Verify buckets
        assertEquals(1, E2eeProtocol.bucketForHeader(session, hCurrent))
        assertEquals(0, E2eeProtocol.bucketForHeader(session, hLast))
        assertEquals(-1, E2eeProtocol.bucketForHeader(session, hAncient))
        assertEquals(2, E2eeProtocol.bucketForHeader(session, hNew))

        // Simulated sort using the same logic as E2eeProtocol.decryptAndSort
        val sorted = input.sortedWith { (h1, _), (h2, _) ->
            val b1 = E2eeProtocol.bucketForHeader(session, h1)
            val b2 = E2eeProtocol.bucketForHeader(session, h2)

            if (b1 != b2) {
                b1.compareTo(b2)
            } else if (b1 == 2) {
                if (h1.pn != h2.pn) h1.pn.compareTo(h2.pn) else h1.seq.compareTo(h2.seq)
            } else {
                h1.seq.compareTo(h2.seq)
            }
        }

        // Expected chronological order: -1 (Ancient), 0 (Last), 1 (Current), 2 (New)
        assertEquals(hAncient, sorted[0].first)
        assertEquals(hLast, sorted[1].first)
        assertEquals(hCurrent, sorted[2].first)
        assertEquals(hNew, sorted[3].first)
    }
}
