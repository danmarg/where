package net.af0.where.e2ee

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
    fun testKeyExchangeFlow() {
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
        assertContentEquals(bobEntry.session.routingToken, aliceEntry.session.routingToken)
    }

    @Test
    fun testPendingInviteAccessibleBeforeExchange() {
        assertNull(aliceStore.pendingQrPayload)
        val qr = aliceStore.createInvite("Alice")
        assertNotNull(aliceStore.pendingQrPayload)
        assertContentEquals(qr.ekPub, aliceStore.pendingQrPayload!!.ekPub)
    }

    @Test
    fun testClearInvite() {
        aliceStore.createInvite("Alice")
        assertNotNull(aliceStore.pendingQrPayload)
        aliceStore.clearInvite()
        assertNull(aliceStore.pendingQrPayload)
    }

    @Test
    fun testPersistence() {
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
    fun testDeleteFriend() {
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
    fun testPendingInviteNotPersisted() {
        aliceStore.createInvite("Alice")

        // Reloading should have no pending invite — single-session design
        val reloadedAliceStore = E2eeStore(aliceStorage)
        assertNull(reloadedAliceStore.pendingQrPayload)

        // Processing an init against a reloaded store (no pending invite) must return null
        val qr = bobStore.createInvite("Bob") // use bobStore to generate a QR as a stand-in
        val (initPayload, _) = aliceStore.processScannedQr(qr) // alice acts as "Bob" here
        val result = reloadedAliceStore.processKeyExchangeInit(initPayload, "Someone")
        assertNull(result)
    }

    @Test
    fun testProcessKeyExchangeInitFailsWithoutPendingInvite() {
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
    fun testIsInitiatorFlag() {
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
    fun testGenerateOpkBundle() {
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
    fun testAliceCannotGenerateOpkBundle() {
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, _) = bobStore.processScannedQr(qr)
        val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

        // Alice is the initiator — generateOpkBundle should return null for her
        assertNull(aliceStore.generateOpkBundle(aliceEntry.id))
    }

    @Test
    fun testStoreOpkBundle() {
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
    fun testStoreOpkBundleRejectsBadMac() {
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
    fun testShouldReplenishOpks() {
        val qr = aliceStore.createInvite("Alice")
        val (_, bobEntry) = bobStore.processScannedQr(qr)

        // Below threshold initially
        assertTrue(bobStore.shouldReplenishOpks(bobEntry.id))

        // Generate enough to exceed threshold
        bobStore.generateOpkBundle(bobEntry.id, count = E2eeStore.OPK_REPLENISH_THRESHOLD)
        assertFalse(bobStore.shouldReplenishOpks(bobEntry.id))
    }

    // -----------------------------------------------------------------------
    // Epoch rotation
    // -----------------------------------------------------------------------

    @Test
    fun testEpochRotationEndToEnd() {
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

        // Bob generates OPKs, Alice stores them
        val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 3)!!
        aliceStore.storeOpkBundle(aliceEntry.id, bundle)

        val aliceBefore = aliceStore.getFriend(aliceEntry.id)!!
        assertEquals(0, aliceBefore.session.epoch)

        // Alice initiates epoch rotation
        val rotPayload = aliceStore.initiateEpochRotation(aliceEntry.id)
        assertNotNull(rotPayload, "Alice should produce an EpochRotationPayload")

        val aliceAfter = aliceStore.getFriend(aliceEntry.id)!!
        assertEquals(1, aliceAfter.session.epoch, "Alice's epoch must advance to 1")
        assertEquals(2, aliceAfter.theirOpkPubs.size, "One OPK consumed — 2 remaining")

        // Bob processes the rotation
        val ack = bobStore.processEpochRotation(bobEntry.id, rotPayload!!)
        assertNotNull(ack, "Bob must return a RatchetAck")

        val bobAfter = bobStore.getFriend(bobEntry.id)!!
        assertEquals(1, bobAfter.session.epoch, "Bob's epoch must match Alice's")
        assertContentEquals(aliceAfter.session.routingToken, bobAfter.session.routingToken,
            "Routing tokens must match after rotation")
        assertContentEquals(aliceAfter.session.sendChainKey, bobAfter.session.recvChainKey,
            "Alice's new send chain must equal Bob's new recv chain")

        // Alice validates Bob's ack
        assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack!!))
    }

    @Test
    fun testEpochRotationEncryptDecryptAfterRotation() {
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

        val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
        aliceStore.storeOpkBundle(aliceEntry.id, bundle)

        // Rotate
        val rotPayload = aliceStore.initiateEpochRotation(aliceEntry.id)!!
        bobStore.processEpochRotation(bobEntry.id, rotPayload)

        // Alice sends a location after rotation
        val aliceCurrent = aliceStore.getFriend(aliceEntry.id)!!
        val loc = net.af0.where.e2ee.LocationPlaintext(lat = 51.5, lng = -0.1, acc = 5.0, ts = 1_000_000L)
        val (newAliceSess, ct) = Session.encryptLocation(
            aliceCurrent.session, loc,
            aliceCurrent.session.aliceFp, aliceCurrent.session.bobFp
        )
        aliceStore.updateSession(aliceEntry.id, newAliceSess)

        // Bob decrypts
        val bobCurrent = bobStore.getFriend(bobEntry.id)!!
        val result = Session.decryptLocation(
            bobCurrent.session, ct, newAliceSess.sendSeq,
            bobCurrent.session.aliceFp, bobCurrent.session.bobFp
        )
        assertNotNull(result, "Decryption must succeed after epoch rotation")
        assertEquals(loc.lat, result!!.second.lat, 1e-9)
    }

    @Test
    fun testShouldRotateEpoch() {
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

        // No OPKs yet — should not rotate
        assertFalse(aliceStore.shouldRotateEpoch(aliceEntry.id))

        // Add OPKs
        val bundle = bobStore.generateOpkBundle(bobEntry.id, count = E2eeStore.OPK_BATCH_SIZE)!!
        aliceStore.storeOpkBundle(aliceEntry.id, bundle)

        // sendSeq=0 — still not due
        assertFalse(aliceStore.shouldRotateEpoch(aliceEntry.id))

        // Advance sendSeq to the rotation interval
        var sess = aliceStore.getFriend(aliceEntry.id)!!.session
        val loc = net.af0.where.e2ee.LocationPlaintext(0.0, 0.0, 0.0, 0L)
        repeat(E2eeStore.EPOCH_ROTATION_INTERVAL.toInt()) {
            val (next, _) = Session.encryptLocation(sess, loc, sess.aliceFp, sess.bobFp)
            sess = next
        }
        aliceStore.updateSession(aliceEntry.id, sess)

        assertTrue(aliceStore.shouldRotateEpoch(aliceEntry.id), "Should rotate after ${ E2eeStore.EPOCH_ROTATION_INTERVAL} sends with OPKs available")
    }

    @Test
    fun testOpkPersistence() {
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

        val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 4)!!
        aliceStore.storeOpkBundle(aliceEntry.id, bundle)

        // Reload both stores
        val reloadedBobStore = E2eeStore(bobStorage)
        val reloadedAliceStore = E2eeStore(aliceStorage)

        val reloadedBob = reloadedBobStore.getFriend(bobEntry.id)!!
        val reloadedAlice = reloadedAliceStore.getFriend(aliceEntry.id)!!

        assertEquals(4, reloadedBob.myOpkPrivs.size, "Bob's OPK privkeys must persist")
        assertEquals(4, reloadedAlice.theirOpkPubs.size, "Alice's cached OPK pubkeys must persist")
        assertEquals(5, reloadedBob.nextOpkId, "Bob's next OPK ID must persist")
    }
}
