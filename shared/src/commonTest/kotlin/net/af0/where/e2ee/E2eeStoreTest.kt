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
    // Epoch rotation
    // -----------------------------------------------------------------------

    @Test
    fun testEpochRotationEndToEnd() =
        runBlocking {
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
            assertContentEquals(
                aliceAfter.session.sendToken,
                bobAfter.session.recvToken,
                "Alice send = Bob recv after rotation",
            )
            assertContentEquals(
                aliceAfter.session.recvToken,
                bobAfter.session.sendToken,
                "Alice recv = Bob send after rotation",
            )
            assertContentEquals(
                aliceAfter.session.sendChainKey,
                bobAfter.session.recvChainKey,
                "Alice's new send chain must equal Bob's new recv chain",
            )

            // Alice validates Bob's ack
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack!!))
        }

    @Test
    fun testEpochRotationEncryptDecryptAfterRotation() =
        runBlocking {
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
            val (newAliceSess, ct) =
                Session.encryptLocation(
                    aliceCurrent.session,
                    loc,
                    aliceCurrent.session.aliceFp,
                    aliceCurrent.session.bobFp,
                )
            aliceStore.updateSession(aliceEntry.id, newAliceSess)

            // Bob decrypts
            val bobCurrent = bobStore.getFriend(bobEntry.id)!!
            val result =
                Session.decryptLocation(
                    bobCurrent.session,
                    ct,
                    newAliceSess.sendSeq,
                    bobCurrent.session.aliceFp,
                    bobCurrent.session.bobFp,
                )
            assertNotNull(result, "Decryption must succeed after epoch rotation")
            assertEquals(loc.lat, result!!.second.lat, 1e-9)
        }

    @Test
    fun testShouldRotateEpoch() =
        runBlocking {
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

            assertTrue(
                aliceStore.shouldRotateEpoch(aliceEntry.id),
                "Should rotate after ${ E2eeStore.EPOCH_ROTATION_INTERVAL} sends with OPKs available",
            )
        }

    @Test
    fun testOpkPersistence() =
        runBlocking {
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

    @Test
    fun testOkpDepletionFallback() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Generate a small number of OPKs
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 2)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // Verify Alice has 2 OPKs
            var aliceFriend = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(2, aliceFriend.theirOpkPubs.size, "Alice should have 2 OPKs")

            // Rotate epoch using the first OPK
            val rotPayload1 = aliceStore.initiateEpochRotation(aliceEntry.id)
            assertNotNull(rotPayload1, "First epoch rotation should succeed")

            val ack1 = bobStore.processEpochRotation(bobEntry.id, rotPayload1)
            assertNotNull(ack1, "Bob should return a RatchetAck for first rotation")
            aliceStore.processRatchetAck(aliceEntry.id, ack1!!)

            aliceFriend = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(1, aliceFriend.theirOpkPubs.size, "Alice should have 1 OPK remaining after first rotation")

            // Rotate epoch using the second OPK
            val rotPayload2 = aliceStore.initiateEpochRotation(aliceEntry.id)
            assertNotNull(rotPayload2, "Second epoch rotation should succeed")

            val ack2 = bobStore.processEpochRotation(bobEntry.id, rotPayload2)
            assertNotNull(ack2, "Bob should return a RatchetAck for second rotation")
            aliceStore.processRatchetAck(aliceEntry.id, ack2!!)

            aliceFriend = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(0, aliceFriend.theirOpkPubs.size, "Alice should have no OPKs remaining after second rotation")

            // Try to rotate again when OPKs are depleted — should return null
            val rotPayload3 = aliceStore.initiateEpochRotation(aliceEntry.id)
            assertNull(rotPayload3, "Epoch rotation should return null when OPKs are depleted")

            // Verify that encryption/decryption still works with the current session
            val aliceCurrent = aliceStore.getFriend(aliceEntry.id)!!
            val bobCurrent = bobStore.getFriend(bobEntry.id)!!
            val loc = LocationPlaintext(lat = 47.6, lng = -122.3, acc = 10.0, ts = 1_000_000L)

            val (newAliceSess, ct) =
                Session.encryptLocation(
                    aliceCurrent.session,
                    loc,
                    aliceCurrent.session.aliceFp,
                    aliceCurrent.session.bobFp,
                )
            aliceStore.updateSession(aliceEntry.id, newAliceSess)

            val bobUpdated = bobStore.getFriend(bobEntry.id)!!
            val result =
                Session.decryptLocation(
                    bobUpdated.session,
                    ct,
                    newAliceSess.sendSeq,
                    bobUpdated.session.aliceFp,
                    bobUpdated.session.bobFp,
                )
            assertNotNull(result, "Decryption must succeed even after OPK depletion")
            assertEquals(loc.lat, result!!.second.lat, 1e-9)
        }

    @Test
    fun testDualPollingWindow() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Bob generates OPKs, Alice stores them
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // 1. Alice sends a message in epoch 0
            val loc0 = LocationPlaintext(1.0, 1.0, 1.0, 1000L)
            val aliceFriend0 = aliceStore.getFriend(aliceEntry.id)!!
            val (aliceSess0, ct0) =
                Session.encryptLocation(
                    aliceFriend0.session,
                    loc0,
                    aliceFriend0.session.aliceFp,
                    aliceFriend0.session.bobFp,
                )
            aliceStore.updateSession(aliceEntry.id, aliceSess0)

            val payload0 = EncryptedLocationPayload(epoch = aliceSess0.epoch, seq = aliceSess0.sendSeq.toString(), ct = ct0)
            val oldToken = aliceSess0.sendToken.toHex()

            // 2. Alice rotates to epoch 1
            val rotPayload = aliceStore.initiateEpochRotation(aliceEntry.id)!!
            val aliceFriend1 = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(1, aliceFriend1.session.epoch)
            val newToken = aliceFriend1.session.recvToken.toHex() // From Bob's perspective

            // 3. Bob polls old token and sees BOTH the location and the rotation
            val batch = listOf(payload0, rotPayload)
            val result = bobStore.processBatch(bobEntry.id, batch, tokenUsed = oldToken)!!

            assertEquals(1, result.decryptedLocations.size)
            assertEquals(loc0.lat, result.decryptedLocations[0].lat)
            assertNotNull(result.newToken)

            val bobFriend1 = bobStore.getFriend(bobEntry.id)!!
            assertEquals(1, bobFriend1.session.epoch)
            assertNotNull(bobFriend1.session.prevRecvToken)
            assertContentEquals(oldToken.hexToByteArray(), bobFriend1.session.prevRecvToken)

            // 4. Alice sends another message in epoch 0 (simulating delayed arrival)
            val loc0b = LocationPlaintext(2.0, 2.0, 2.0, 1001L)
            val (aliceSess0b, ct0b) = Session.encryptLocation(aliceSess0, loc0b, aliceSess0.aliceFp, aliceSess0.bobFp)
            val payload0b = EncryptedLocationPayload(epoch = aliceSess0b.epoch, seq = aliceSess0b.sendSeq.toString(), ct = ct0b)

            // Bob polls the old token AGAIN
            val result2 = bobStore.processBatch(bobEntry.id, listOf(payload0b), tokenUsed = oldToken)!!
            assertEquals(1, result2.decryptedLocations.size)
            assertEquals(loc0b.lat, result2.decryptedLocations[0].lat)

            // 5. Alice sends a message in epoch 1
            val loc1 = LocationPlaintext(3.0, 3.0, 3.0, 1002L)
            val (aliceSess1, ct1) =
                Session.encryptLocation(
                    aliceFriend1.session,
                    loc1,
                    aliceFriend1.session.aliceFp,
                    aliceFriend1.session.bobFp,
                )
            aliceStore.updateSession(aliceEntry.id, aliceSess1)
            val payload1 = EncryptedLocationPayload(epoch = aliceSess1.epoch, seq = aliceSess1.sendSeq.toString(), ct = ct1)

            // Bob polls the NEW token
            val result3 = bobStore.processBatch(bobEntry.id, listOf(payload1), tokenUsed = newToken)!!
            assertEquals(1, result3.decryptedLocations.size)
            assertEquals(loc1.lat, result3.decryptedLocations[0].lat)

            // 6. Verify that prevRecvToken is cleared after successful decryption on new token
            val bobFriendFinal = bobStore.getFriend(bobEntry.id)!!
            assertNull(bobFriendFinal.session.prevRecvToken)
            assertNull(bobFriendFinal.session.prevRecvChainKey)
        }
}
