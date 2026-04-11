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
            // Initial tokens are T_BA_0 for Bob send and Alice recv.
            // Wait, KeyExchange.kt said tokenBobToAlice is Bob send.
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
    // OPK management
    // -----------------------------------------------------------------------

    @Test
    fun testGenerateOpkBundle() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (_, bobEntry) = bobStore.processScannedQr(qr)

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 5)
            assertNotNull(bundle, "Bob should be able to generate an OPK bundle")
            assertEquals(5, bundle!!.keys.size)

            val updatedBob = bobStore.getFriend(bobEntry.id)!!
            assertEquals(5, updatedBob.myOpkPrivs.size)
            assertEquals(6, updatedBob.nextOpkId) // started at 1, added 5 → next is 6
        }

    @Test
    fun testAliceCannotGenerateOpkBundle() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Alice is the initiator — generateOpkBundle should return null for her
            assertNull(aliceStore.generateOpkBundle(aliceEntry.id))
        }

    @Test
    fun testStoreOpkBundle() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 3)!!
            val stored = aliceStore.storeOpkBundle(aliceEntry.id, bundle)
            assertTrue(stored, "Alice should accept a valid MAC'd OPK bundle")

            val updatedAlice = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(3, updatedAlice.theirOpkPubs.size)
        }

    @Test
    fun testStoreOpkBundleRejectsBadMac() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 2)!!
            // Corrupt the MAC
            val tampered = bundle.mac.copyOf()
            tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
            val badBundle = bundle.copy(mac = tampered)

            assertFalse(aliceStore.storeOpkBundle(aliceEntry.id, badBundle))
            // Alice's cache should be untouched
            assertEquals(0, aliceStore.getFriend(aliceEntry.id)!!.theirOpkPubs.size)
        }

    @Test
    fun testShouldReplenishOpks() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (_, bobEntry) = bobStore.processScannedQr(qr)

            // Below threshold initially
            assertTrue(bobStore.shouldReplenishOpks(bobEntry.id))

            // Generate enough to exceed threshold
            bobStore.generateOpkBundle(bobEntry.id, count = E2eeStore.OPK_REPLENISH_THRESHOLD)
            assertFalse(bobStore.shouldReplenishOpks(bobEntry.id))
        }

    // -----------------------------------------------------------------------
    // Per-message token rotation & integrated DH
    // -----------------------------------------------------------------------

    @Test
    fun testIntegratedDhRatchet() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Bob generates OPKs, Alice stores them
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            val aliceBefore = aliceStore.getFriend(aliceEntry.id)!!

            // Alice sends a message with integrated DH
            val opk = aliceBefore.theirOpkPubs.minBy { it.key }
            val aliceNewEk = generateX25519KeyPair()
            val nextToken = randomBytes(16)
            val loc = LocationPlaintext(1.0, 1.0, 1.0, 1000L)

            val (newAliceSess, ct) = Session.encryptLocation(
                aliceBefore.session, loc, aliceBefore.session.aliceFp, aliceBefore.session.bobFp, nextToken,
                nextOpkId = opk.key, nextBobOpkPub = opk.value, aliceNewEkPriv = aliceNewEk.priv, aliceNewEkPub = aliceNewEk.pub
            )

            aliceStore.updateFriend(aliceBefore.copy(session = newAliceSess, theirOpkPubs = aliceBefore.theirOpkPubs - opk.key))

            val msg = EncryptedLocationPayload(seq = newAliceSess.sendSeq.toString(), ct = ct)

            // Bob processes batch
            val result = bobStore.processBatch(bobEntry.id, listOf(msg))!!
            assertEquals(1, result.decryptedLocations.size)

            val aliceAfter = aliceStore.getFriend(aliceEntry.id)!!
            val bobAfter = bobStore.getFriend(bobEntry.id)!!

            assertContentEquals(aliceAfter.session.rootKey, bobAfter.session.rootKey)
            assertContentEquals(aliceAfter.session.sendToken, bobAfter.session.recvToken)
            assertContentEquals(nextToken, bobAfter.session.recvToken)
            assertEquals(0, bobAfter.myOpkPrivs.size, "OPK should be consumed")
        }
}
