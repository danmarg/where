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
    private lateinit var aliceIdentity: IdentityKeys
    private lateinit var bobIdentity: IdentityKeys
    private lateinit var aliceStorage: MemoryStorage
    private lateinit var bobStorage: MemoryStorage
    private lateinit var aliceStore: E2eeStore
    private lateinit var bobStore: E2eeStore

    @BeforeTest
    fun setup() {
        aliceIdentity = IdentityKeys(generateX25519KeyPair(), generateEd25519KeyPair())
        bobIdentity = IdentityKeys(generateX25519KeyPair(), generateEd25519KeyPair())
        aliceStorage = MemoryStorage()
        bobStorage = MemoryStorage()
        aliceStore = E2eeStore(aliceStorage, aliceIdentity)
        bobStore = E2eeStore(bobStorage, bobIdentity)
    }

    @Test
    fun testKeyExchangeFlow() {
        val aliceId = fingerprint(aliceIdentity.ik.pub, aliceIdentity.sigIk.pub).toHex().substring(0, 20)
        val bobId = fingerprint(bobIdentity.ik.pub, bobIdentity.sigIk.pub).toHex().substring(0, 20)

        // Alice creates invite
        val qr = aliceStore.createInvite("Alice")

        // Bob scans QR → gets wire payload ready to POST
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)

        // Alice processes Bob's init payload (received from discovery inbox)
        val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")

        assertNotNull(aliceEntry)
        assertEquals(aliceId, bobEntry.id)
        assertEquals(bobId, aliceEntry.id)
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
        val reloadedAliceStore = E2eeStore(aliceStorage, aliceIdentity)
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
        val reloadedAliceStore = E2eeStore(aliceStorage, aliceIdentity)
        assertTrue(reloadedAliceStore.listFriends().isEmpty())
    }

    @Test
    fun testPendingInviteNotPersisted() {
        aliceStore.createInvite("Alice")

        // Reloading should have no pending invite — single-session design
        val reloadedAliceStore = E2eeStore(aliceStorage, aliceIdentity)
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
        // aliceStore has no pending invite of its own, but has Alice's identity
        val freshStore = E2eeStore(MemoryStorage(), aliceIdentity)
        assertNull(freshStore.processKeyExchangeInit(initPayload, "Bob"))
    }
}
