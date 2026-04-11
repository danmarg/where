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
    // Ratchet rotation
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

            // Alice's session is unchanged before rotation
            val aliceBefore = aliceStore.getFriend(aliceEntry.id)!!
            assertNull(aliceBefore.pendingRotation, "No pending rotation before initiation")

            // Alice initiates rotation — stores PendingRotation, session unchanged
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)
            assertNotNull(rotPayload, "Alice should produce an EpochRotationPayload")

            val aliceAfterInit = aliceStore.getFriend(aliceEntry.id)!!
            assertNotNull(aliceAfterInit.pendingRotation, "Pending rotation must be stored")
            assertEquals(2, aliceAfterInit.theirOpkPubs.size, "One OPK consumed — 2 remaining")
            // Session unchanged until acked
            assertContentEquals(aliceBefore.session.sendToken, aliceAfterInit.session.sendToken,
                "Alice sendToken must not change before ack")

            // Bob processes the rotation — immediately switches session
            val ack = bobStore.processEpochRotation(bobEntry.id, rotPayload!!)
            assertNotNull(ack, "Bob must return a RatchetAck")

            val bobAfter = bobStore.getFriend(bobEntry.id)!!
            // Bob's new tokens match Alice's pending session
            assertContentEquals(
                aliceAfterInit.pendingRotation!!.newSession.sendToken,
                bobAfter.session.recvToken,
                "Alice pending sendToken = Bob new recvToken",
            )
            assertContentEquals(
                aliceAfterInit.pendingRotation!!.newSession.sendChainKey,
                bobAfter.session.recvChainKey,
                "Alice new send chain must equal Bob's new recv chain",
            )

            // Alice commits pending rotation upon receiving the RatchetAck
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack!!))

            val aliceCommitted = aliceStore.getFriend(aliceEntry.id)!!
            assertNull(aliceCommitted.pendingRotation, "PendingRotation cleared after commit")
            assertContentEquals(
                aliceCommitted.session.sendToken,
                bobAfter.session.recvToken,
                "Alice sendToken = Bob recvToken after commit",
            )
            assertContentEquals(
                aliceCommitted.session.recvToken,
                bobAfter.session.sendToken,
                "Alice recvToken = Bob sendToken after commit",
            )
        }

    @Test
    fun testEpochRotationEncryptDecryptAfterRotation() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // Alice initiates rotation, Bob processes and returns ack, Alice commits
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)!!
            val ack = bobStore.processEpochRotation(bobEntry.id, rotPayload)!!
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack))

            // Alice sends a location using the committed session
            val aliceCurrent = aliceStore.getFriend(aliceEntry.id)!!
            val loc = LocationPlaintext(lat = 51.5, lng = -0.1, acc = 5.0, ts = 1_000_000L)
            val (newAliceSess, ct) =
                Session.encryptLocation(
                    aliceCurrent.session,
                    loc,
                    aliceCurrent.session.aliceFp,
                    aliceCurrent.session.bobFp,
                )
            aliceStore.updateSession(aliceEntry.id, newAliceSess)

            // Bob decrypts using his updated session
            val bobCurrent = bobStore.getFriend(bobEntry.id)!!
            val result =
                Session.decryptLocation(
                    bobCurrent.session,
                    ct,
                    newAliceSess.sendSeq,
                    bobCurrent.session.aliceFp,
                    bobCurrent.session.bobFp,
                )
            assertNotNull(result, "Decryption must succeed after ratchet rotation")
            assertEquals(loc.lat, result!!.second.lat, 1e-9)
        }

    @Test
    fun testShouldInitiateRotation() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // No OPKs yet — should not rotate
            assertFalse(aliceStore.shouldInitiateRotation(aliceEntry.id))

            // Add OPKs
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = E2eeStore.OPK_BATCH_SIZE)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // Now OPKs available and no pending rotation — should initiate
            assertTrue(aliceStore.shouldInitiateRotation(aliceEntry.id))

            // Initiate a rotation — pending rotation stored, should NOT initiate again
            aliceStore.initiateRotation(aliceEntry.id)
            assertFalse(
                aliceStore.shouldInitiateRotation(aliceEntry.id),
                "Should not initiate a second rotation while one is in flight",
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

            // Rotate using the first OPK
            val rotPayload1 = aliceStore.initiateRotation(aliceEntry.id)
            assertNotNull(rotPayload1, "First rotation should succeed")

            val ack1 = bobStore.processEpochRotation(bobEntry.id, rotPayload1)
            assertNotNull(ack1, "Bob should return a RatchetAck for first rotation")
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack1!!))

            aliceFriend = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(1, aliceFriend.theirOpkPubs.size, "Alice should have 1 OPK remaining after first rotation")
            assertNull(aliceFriend.pendingRotation, "PendingRotation cleared after first ack")

            // Rotate using the second OPK
            val rotPayload2 = aliceStore.initiateRotation(aliceEntry.id)
            assertNotNull(rotPayload2, "Second rotation should succeed")

            val ack2 = bobStore.processEpochRotation(bobEntry.id, rotPayload2)
            assertNotNull(ack2, "Bob should return a RatchetAck for second rotation")
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack2!!))

            aliceFriend = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(0, aliceFriend.theirOpkPubs.size, "Alice should have no OPKs remaining after second rotation")
            assertNull(aliceFriend.pendingRotation, "PendingRotation cleared after second ack")

            // Try to rotate again when OPKs are depleted — should return null
            val rotPayload3 = aliceStore.initiateRotation(aliceEntry.id)
            assertNull(rotPayload3, "Rotation should return null when OPKs are depleted")

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
    fun testAtomicTokenSwitch() =
        runBlocking {
            // Verifies the ack-triggered atomic token switch: no dual-polling window.
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Bob generates OPKs, Alice stores them
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // 1. Alice sends a location message on the pre-rotation session
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
            // Bob's old sendToken = Alice's old recvToken (T_BA_old): ack must be addressed here
            val bobOldSendToken = aliceFriend0.session.recvToken.toHex()
            val oldSendToken = aliceSess0.sendToken.toHex()

            // 2. Alice initiates rotation (session unchanged, pendingRotation stored)
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)!!
            val aliceWithPending = aliceStore.getFriend(aliceEntry.id)!!
            assertNotNull(aliceWithPending.pendingRotation)
            // Alice's session sendToken is still the old one
            assertEquals(oldSendToken, aliceWithPending.session.sendToken.toHex())

            // 3. Bob processes a batch containing [location, epochRotation] in the same poll
            val locPayload = EncryptedLocationPayload(seq = aliceSess0.sendSeq.toString(), ct = ct0)
            val batch = listOf(locPayload, rotPayload)
            val result = bobStore.processBatch(bobEntry.id, batch)!!

            // Location decrypted before rotation was applied
            assertEquals(1, result.decryptedLocations.size)
            assertEquals(loc0.lat, result.decryptedLocations[0].lat)

            // Bob's outgoing should include RatchetAck + PreKeyBundle (both on old sendToken)
            val ackEntry = result.outgoing.firstOrNull { it.payload is RatchetAckPayload }
            assertNotNull(ackEntry, "Bob must produce a RatchetAck in outgoing")
            // The ack is sent on Bob's pre-rotation sendToken = Alice's pre-rotation recvToken (T_BA_old)
            assertEquals(bobOldSendToken, ackEntry!!.token, "RatchetAck must be addressed to Bob's pre-rotation sendToken")

            // Bob's session is now on the new tokens (immediate switch — no dual-polling)
            val bobAfter = bobStore.getFriend(bobEntry.id)!!
            assertFalse(
                bobAfter.session.recvToken.toHex() == aliceFriend0.session.sendToken.toHex(),
                "Bob's recvToken must have switched to the new token",
            )

            // 4. Alice processes the RatchetAck → commits pending rotation
            val ackPayload = ackEntry.payload as RatchetAckPayload
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ackPayload))

            val aliceCommitted = aliceStore.getFriend(aliceEntry.id)!!
            assertNull(aliceCommitted.pendingRotation)
            // Alice and Bob now agree on routing tokens
            assertContentEquals(
                aliceCommitted.session.sendToken,
                bobAfter.session.recvToken,
                "Alice sendToken = Bob recvToken after commit",
            )
            assertContentEquals(
                aliceCommitted.session.recvToken,
                bobAfter.session.sendToken,
                "Alice recvToken = Bob sendToken after commit",
            )
        }
}
