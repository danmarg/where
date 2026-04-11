package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MemoryStorage : E2eeStorage {
    private val data = mutableMapOf<String, String>()

    override fun getString(key: String): String? = data[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        data[key] = value
    }
}

class E2eeStoreTest {
    init {
        initializeE2eeTests()
    }

    private lateinit var aliceStorage: MemoryStorage
    private lateinit var bobStorage: MemoryStorage
    private lateinit var aliceStore: E2eeStore
    private lateinit var bobStore: E2eeStore

    @BeforeTest
    fun setup() {
        aliceStorage = MemoryStorage()
        bobStorage = MemoryStorage()
        aliceStore = E2eeStore(aliceStorage)
        bobStore = E2eeStore(bobStorage)
    }

    @Test
    fun testKeyExchangeFlow() =
        runBlocking {
            // Alice creates invite
            val qr = aliceStore.createInvite("Alice")

            // Bob scans QR → gets wire payload ready to POST
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)

            // Alice processes Bob's init payload (received from discovery inbox)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")

            assertNotNull(aliceEntry)

            // Friend IDs are based on aliceFp = SHA-256(EK_A.pub)
            val expectedAliceFp = sha256(qr.ekPub).toHex()
            assertEquals(expectedAliceFp, bobEntry.id)
            assertEquals(expectedAliceFp, aliceEntry.id)
            assertEquals("Alice", bobEntry.name)
            assertEquals("Bob", aliceEntry.name)

            // Both sides must derive the same session keys
            assertContentEquals(bobEntry.session.rootKey, aliceEntry.session.rootKey)
            assertContentEquals(bobEntry.session.sendToken, aliceEntry.session.recvToken, "Bob send = Alice recv")
            assertContentEquals(bobEntry.session.recvToken, aliceEntry.session.sendToken, "Bob recv = Alice send")
        }

    @Test
    fun testPendingInviteAccessibleBeforeExchange() =
        runBlocking {
            assertNull(aliceStore.pendingQrPayload())
            val qr = aliceStore.createInvite("Alice")
            assertNotNull(aliceStore.pendingQrPayload())
            assertContentEquals(qr.ekPub, aliceStore.pendingQrPayload()!!.ekPub)
        }

    @Test
    fun testClearInvite() =
        runBlocking {
            aliceStore.createInvite("Alice")
            assertNotNull(aliceStore.pendingQrPayload())
            aliceStore.clearInvite()
            assertNull(aliceStore.pendingQrPayload())
        }

    @Test
    fun testPersistence() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob")

            val aliceEntryBefore = aliceStore.listFriends().first()

            // Reload Alice's store from same storage
            val reloadedAliceStore = E2eeStore(aliceStorage)
            val aliceEntryAfter = reloadedAliceStore.listFriends().first()

            assertEquals(aliceEntryBefore, aliceEntryAfter)
            assertEquals(aliceEntryBefore.id, aliceEntryAfter.id)
        }

    @Test
    fun testDeleteFriend() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            aliceStore.deleteFriend(aliceEntry.id)
            assertTrue(aliceStore.listFriends().isEmpty())
            assertNull(aliceStore.getFriend(aliceEntry.id))

            // Verify persistence after delete
            val reloadedAliceStore = E2eeStore(aliceStorage)
            assertTrue(reloadedAliceStore.listFriends().isEmpty())
        }

    @Test
    fun testPendingInvitePersisted() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")

            // Reloading should have the pending invite persisted
            val reloadedAliceStore = E2eeStore(aliceStorage)
            assertNotNull(reloadedAliceStore.pendingQrPayload())
            assertContentEquals(qr.ekPub, reloadedAliceStore.pendingQrPayload()!!.ekPub)

            // Processing an init against a reloaded store must succeed
            val (initPayload, _) = bobStore.processScannedQr(qr)
            val result = reloadedAliceStore.processKeyExchangeInit(initPayload, "Bob")
            assertNotNull(result)
            assertEquals("Bob", result.name)
        }

    @Test
    fun testProcessKeyExchangeInitFailsWithoutPendingInvite() =
        runBlocking {
            val qr = bobStore.createInvite("Bob")
            val (initPayload, _) = aliceStore.processScannedQr(qr)
            // freshStore has no pending invite
            val freshStore = E2eeStore(MemoryStorage())
            assertNull(freshStore.processKeyExchangeInit(initPayload, "Bob"))
        }

    // -----------------------------------------------------------------------
    // Role flags
    // -----------------------------------------------------------------------

    @Test
    fun testIsInitiatorFlag() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            assertFalse(bobEntry.isInitiator, "Bob scanned QR — must NOT be initiator")
            assertTrue(aliceEntry.isInitiator, "Alice created QR — must be initiator")
        }

    // -----------------------------------------------------------------------
    // Per-message token rotation (§8.3)
    // -----------------------------------------------------------------------

    @Test
    fun testPerMessageTokenRotationEndToEnd() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Alice and Bob start with matching initial tokens
            val aliceFriend0 = aliceStore.getFriend(aliceEntry.id)!!
            val bobFriend0 = bobStore.getFriend(bobEntry.id)!!
            assertContentEquals(aliceFriend0.session.sendToken, bobFriend0.session.recvToken, "Initial send/recv must match")

            // Alice encrypts a message; her sendToken advances to the next token
            val loc1 = LocationPlaintext(1.0, 2.0, 3.0, 1000L)
            val oldSendToken = aliceFriend0.session.sendToken.copyOf()
            val (aliceSess1, ct1) = Session.encryptLocation(
                aliceFriend0.session, loc1,
                aliceFriend0.session.aliceFp, aliceFriend0.session.bobFp,
            )
            aliceStore.updateSession(aliceEntry.id, aliceSess1)

            // sendToken must have changed
            assertFalse(aliceSess1.sendToken.contentEquals(oldSendToken), "sendToken must advance after encrypt")

            // Bob receives the message via processBatch
            val payload1 = EncryptedLocationPayload(seq = aliceSess1.sendSeq.toString(), ct = ct1)
            val result1 = bobStore.processBatch(bobEntry.id, listOf(payload1))!!
            assertEquals(1, result1.decryptedLocations.size)
            assertEquals(loc1.lat, result1.decryptedLocations[0].lat)

            // Bob's recvToken must now equal Alice's new sendToken
            val bobFriend1 = bobStore.getFriend(bobEntry.id)!!
            assertContentEquals(aliceSess1.sendToken, bobFriend1.session.recvToken,
                "Bob's recvToken must advance to Alice's new sendToken")

            // Alice sends a second message; Bob follows the chain
            val loc2 = LocationPlaintext(4.0, 5.0, 6.0, 2000L)
            val (aliceSess2, ct2) = Session.encryptLocation(
                aliceSess1, loc2,
                aliceSess1.aliceFp, aliceSess1.bobFp,
            )
            aliceStore.updateSession(aliceEntry.id, aliceSess2)

            val payload2 = EncryptedLocationPayload(seq = aliceSess2.sendSeq.toString(), ct = ct2)
            val result2 = bobStore.processBatch(bobEntry.id, listOf(payload2))!!
            assertEquals(1, result2.decryptedLocations.size)
            assertEquals(loc2.lat, result2.decryptedLocations[0].lat)

            val bobFriend2 = bobStore.getFriend(bobEntry.id)!!
            assertContentEquals(aliceSess2.sendToken, bobFriend2.session.recvToken,
                "Bob's recvToken must advance again after second message")
        }

    @Test
    fun testSingleTokenInvariant() =
        runBlocking {
            // Verify Bob never needs to maintain more than one recvToken at any time.
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            var aliceSess = aliceStore.getFriend(aliceEntry.id)!!.session
            repeat(20) { i ->
                val loc = LocationPlaintext(i.toDouble(), 0.0, 0.0, i.toLong())
                val (newSess, ct) = Session.encryptLocation(aliceSess, loc, aliceSess.aliceFp, aliceSess.bobFp)
                aliceSess = newSess
                aliceStore.updateSession(aliceEntry.id, aliceSess)

                val payload = EncryptedLocationPayload(seq = newSess.sendSeq.toString(), ct = ct)
                val result = bobStore.processBatch(bobEntry.id, listOf(payload))!!
                assertEquals(1, result.decryptedLocations.size, "Should decrypt message $i")

                // Bob has exactly one recvToken — no prev token state exists
                val bobFriend = bobStore.getFriend(bobEntry.id)!!
                assertContentEquals(aliceSess.sendToken, bobFriend.session.recvToken,
                    "Bob's recvToken must match Alice's current sendToken after message $i")
            }
        }
}
