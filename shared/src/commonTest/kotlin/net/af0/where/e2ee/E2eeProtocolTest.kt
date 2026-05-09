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
            seenRemoteDhPubs = seenKeys,
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

        // Mock headers for different buckets
        val hCurrent = Session.DecryptedHeader(remoteDhPubCurrent, 10, 5) // Bucket 1
        val hLast = Session.DecryptedHeader(remoteDhPubLast, 8, 4)       // Bucket 0
        val hAncient = Session.DecryptedHeader(remoteDhPubAncient, 6, 3) // Bucket 3 (was -1)
        val hNew = Session.DecryptedHeader(remoteDhPubNew, 12, 6)         // Bucket 2

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
        assertEquals(3, E2eeProtocol.bucketForHeader(session, hAncient))
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

        // Expected order: 0 (Last), 1 (Current), 2 (New), 3 (Ancient)
        assertEquals(hLast, sorted[0].first)
        assertEquals(hCurrent, sorted[1].first)
        assertEquals(hNew, sorted[2].first)
        assertEquals(hAncient, sorted[3].first)
    }
}
