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
            // The pending session is intermediate (rootKey1 only); step 2 runs on commit.
            // The step-1 chain key relationship still holds: Alice's pending sendChainKey
            // (chainKey_AB) must equal Bob's new recvChainKey.
            assertContentEquals(
                aliceAfterInit.pendingRotation!!.newSession.sendChainKey,
                bobAfter.session.recvChainKey,
                "Alice pending sendChainKey (chainKey_AB) must equal Bob's new recvChainKey",
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
            // Mutual PFS: step 2 gives each side a fresh chain key from their own ephemeral.
            assertContentEquals(
                aliceCommitted.session.recvChainKey,
                bobAfter.session.sendChainKey,
                "Alice recvChainKey (chainKey_BA) must equal Bob's sendChainKey after commit",
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
    fun testEpochRotationAtomicSequenceReset() =
        runBlocking {
            // Verifies that sequence numbers (sendSeq and recvSeq) reset to 0 on BOTH sides
            // after rotation and increment correctly thereafter, preventing replay/gap issues.
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // 1. Advance sequence in Epoch 0
            val loc0 = LocationPlaintext(1.0, 1.0, 1.0, 1000L)
            val alice0 = aliceStore.getFriend(aliceEntry.id)!!
            val (sess0_1, ct0_1) = Session.encryptLocation(alice0.session, loc0, alice0.session.aliceFp, alice0.session.bobFp)
            aliceStore.updateSession(aliceEntry.id, sess0_1)
            assertEquals(1L, sess0_1.sendSeq)

            // 2. Perform Epoch Rotation
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)!!
            val ack = bobStore.processEpochRotation(bobEntry.id, rotPayload)!!
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, ack))

            val alice1 = aliceStore.getFriend(aliceEntry.id)!!
            val bob1 = bobStore.getFriend(bobEntry.id)!!

            // sendSeq and recvSeq must be reset to 0
            assertEquals(0L, alice1.session.sendSeq)
            assertEquals(0L, alice1.session.recvSeq)
            assertEquals(0L, bob1.session.sendSeq)
            assertEquals(0L, bob1.session.recvSeq)

            // 3. Advance sequence in Epoch 1 (Alice to Bob)
            val loc1 = LocationPlaintext(2.0, 2.0, 1.0, 2000L)
            val (sess1_1, ct1_1) = Session.encryptLocation(alice1.session, loc1, alice1.session.aliceFp, alice1.session.bobFp)
            aliceStore.updateSession(aliceEntry.id, sess1_1)
            assertEquals(1L, sess1_1.sendSeq)

            val (bobSess1_1, pt1_1) = Session.decryptLocation(bob1.session, ct1_1, 1L, bob1.session.aliceFp, bob1.session.bobFp)
            bobStore.updateSession(bobEntry.id, bobSess1_1)
            assertEquals(1L, bobSess1_1.recvSeq)
            assertEquals(loc1.lat, pt1_1.lat)

            // 4. Advance sequence in Epoch 1 (Bob to Alice)
            val locB1 = LocationPlaintext(3.0, 3.0, 1.0, 3000L)
            val bobAfterRecv = bobStore.getFriend(bobEntry.id)!!
            val (bobSessB1, ctB1) = Session.encryptLocation(bobAfterRecv.session, locB1, bobAfterRecv.session.bobFp, bobAfterRecv.session.aliceFp)
            bobStore.updateSession(bobEntry.id, bobSessB1)
            assertEquals(1L, bobSessB1.sendSeq)

            val aliceAfterSend = aliceStore.getFriend(aliceEntry.id)!!
            val (aliceSessB1, ptB1) = Session.decryptLocation(aliceAfterSend.session, ctB1, 1L, aliceAfterSend.session.bobFp, aliceAfterSend.session.aliceFp)
            aliceStore.updateSession(aliceEntry.id, aliceSessB1)
            assertEquals(1L, aliceSessB1.recvSeq)
            assertEquals(locB1.lat, ptB1.lat)
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
            assertTrue(reloadedAlice.lastAckTs != Long.MAX_VALUE, "Alice's lastAckTs should be set")
        }

    @Test
    fun testStalenessHeuristic() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            // Not stale initially
            assertFalse(aliceStore.getFriend(aliceEntry.id)!!.isStale)

            // Simulate stale rotation (manual timestamp manipulation in FriendEntry is not possible as it's private in store,
            // but we can test the logic in FriendEntry directly).
            val entry = aliceStore.getFriend(aliceEntry.id)!!
            val stalePending = entry.pendingRotation?.copy(createdAt = currentTimeSeconds() - FriendEntry.STALE_THRESHOLD_SECONDS - 10)
            val staleEntry = entry.copy(pendingRotation = stalePending)
            // Wait, pendingRotation is null initially. Let's initiate rotation.
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)
            aliceStore.initiateRotation(aliceEntry.id)
            
            val entryWithRot = aliceStore.getFriend(aliceEntry.id)!!
            assertNotNull(entryWithRot.pendingRotation)
            assertFalse(entryWithRot.isStale)

            val staleRot = entryWithRot.pendingRotation!!.copy(createdAt = currentTimeSeconds() - FriendEntry.STALE_THRESHOLD_SECONDS - 10)
            val staleEntryRot = entryWithRot.copy(pendingRotation = staleRot)
            assertTrue(staleEntryRot.isStale, "Should be stale if pending rotation is old")

            // Test ack staleness
            val entryWithStaleAck = entry.copy(lastAckTs = currentTimeSeconds() - FriendEntry.ACK_TIMEOUT_SECONDS - 10)
            assertTrue(entryWithStaleAck.isStale, "Should be stale if last ack is old")
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
            val result = bobStore.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), batch)!!

            // Location decrypted before rotation was applied
            assertEquals(1, result.decryptedLocations.size)
            assertEquals(loc0.lat, result.decryptedLocations[0].lat)

            // Bob's outgoing should include PreKeyBundle + RatchetAck (both on old sendToken).
            // Ordering is critical: PreKeyBundle must be POSTed before RatchetAck so Alice
            // sees the new OPKs before she commits and switches tokens.
            val bundleEntry = result.outgoing.firstOrNull { it.payload is PreKeyBundlePayload }
            assertNotNull(bundleEntry, "Bob must produce a PreKeyBundle in outgoing")
            val ackEntry = result.outgoing.firstOrNull { it.payload is RatchetAckPayload }
            assertNotNull(ackEntry, "Bob must produce a RatchetAck in outgoing")

            val bundleIdx = result.outgoing.indexOf(bundleEntry)
            val ackIdx = result.outgoing.indexOf(ackEntry)
            assertTrue(bundleIdx < ackIdx, "PreKeyBundle must be ordered before RatchetAck")

            // The ack is sent on Bob's pre-rotation sendToken = Alice's pre-rotation recvToken (T_BA_old)
            assertEquals(bobOldSendToken, ackEntry!!.token, "RatchetAck must be addressed to Bob's pre-rotation sendToken")
            assertEquals(bobOldSendToken, bundleEntry!!.token, "PreKeyBundle must be addressed to Bob's pre-rotation sendToken")

            // Bob's session is now on the new tokens (immediate switch — no dual-polling)
            val bobAfter = bobStore.getFriend(bobEntry.id)!!
            assertFalse(
                bobAfter.session.recvToken.toHex() == aliceFriend0.session.sendToken.toHex(),
                "Bob's recvToken must have switched to the new token",
            )
            // Bob must have stored a PendingAck for lost-ack recovery.
            assertNotNull(bobAfter.pendingAck, "Bob must store PendingAck after EpochRotation")

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

    @Test
    fun testPendingAckSurvivesRestart() =
        runBlocking {
            // Verifies that PendingAck is persisted: if the app restarts between Bob processing
            // the EpochRotation and Alice receiving the ack, Bob still re-posts on the next poll.
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            val loc0 = LocationPlaintext(9.0, 9.0, 1.0, 9000L)
            val aliceFriend0 = aliceStore.getFriend(aliceEntry.id)!!
            val (aliceSess0, ct0) = Session.encryptLocation(
                aliceFriend0.session, loc0,
                aliceFriend0.session.aliceFp, aliceFriend0.session.bobFp,
            )
            aliceStore.updateSession(aliceEntry.id, aliceSess0)
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)!!
            val locPayload = EncryptedLocationPayload(seq = aliceSess0.sendSeq.toString(), ct = ct0)
            bobStore.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(locPayload, rotPayload))

            // Confirm PendingAck is set before "restart"
            assertNotNull(bobStore.getFriend(bobEntry.id)!!.pendingAck)

            // Simulate app restart: create a new store backed by the same storage (load() runs in init)
            val bobStoreAfterRestart = E2eeStore(bobStorage)

            // PendingAck must survive the round-trip through serialization
            val bobReloaded = bobStoreAfterRestart.getFriend(bobEntry.id)!!
            assertNotNull(bobReloaded.pendingAck, "PendingAck must survive serialization round-trip")
            assertContentEquals(
                bobStore.getFriend(bobEntry.id)!!.pendingAck!!.ackCt,
                bobReloaded.pendingAck!!.ackCt,
                "ackCt must be identical after reload",
            )
            assertEquals(
                bobStore.getFriend(bobEntry.id)!!.pendingAck!!.sendToken,
                bobReloaded.pendingAck!!.sendToken,
                "sendToken must be identical after reload",
            )
            assertEquals(
                bobStore.getFriend(bobEntry.id)!!.pendingAck!!.expectedRecvToken,
                bobReloaded.pendingAck!!.expectedRecvToken,
                "expectedRecvToken must be identical after reload",
            )
        }

    @Test
    fun testLostRatchetAckRecovery() =
        runBlocking {
            // Simulates the lost-ack scenario:
            // Bob processes EpochRotation and switches to T_AB_new, but his RatchetAck is
            // never received by Alice. Since Bob now polls T_AB_new, he will never see
            // Alice's retried EpochRotation on T_AB_old. Recovery: Bob stores a PendingAck
            // and re-posts it on every poll. Alice eventually receives it on T_BA_old and
            // commits. Bob clears PendingAck when he sees Alice's first message on T_AB_new.

            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!

            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // Alice initiates rotation and sends a batch with [location, EpochRotation].
            val loc0 = LocationPlaintext(2.0, 3.0, 1.0, 1000L)
            val aliceFriend0 = aliceStore.getFriend(aliceEntry.id)!!
            val (aliceSess0, ct0) = Session.encryptLocation(
                aliceFriend0.session, loc0,
                aliceFriend0.session.aliceFp, aliceFriend0.session.bobFp,
            )
            aliceStore.updateSession(aliceEntry.id, aliceSess0)
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)!!

            // Bob processes the batch — switches to T_AB_new, sends ack on T_BA_old.
            val locPayload = EncryptedLocationPayload(seq = aliceSess0.sendSeq.toString(), ct = ct0)
            bobStore.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(locPayload, rotPayload))

            // Bob has a PendingAck stored (ack was "lost" — not processed by Alice yet).
            val bobWithPending = bobStore.getFriend(bobEntry.id)!!
            assertNotNull(bobWithPending.pendingAck, "Bob must store a PendingAck after processing EpochRotation")
            val pendingAck = bobWithPending.pendingAck!!

            // The pending ack is addressed to T_BA_old (Alice's current recvToken).
            val aliceFriend1 = aliceStore.getFriend(aliceEntry.id)!!
            assertEquals(
                aliceFriend1.session.recvToken.toHex(),
                pendingAck.sendToken,
                "PendingAck must target Alice's current recvToken (T_BA_old)",
            )
            // Alice has NOT committed yet.
            assertNotNull(aliceFriend1.pendingRotation, "Alice must still have pendingRotation (ack not received)")

            // Alice receives the re-posted ack and commits.
            val repostedAck = RatchetAckPayload(ct = pendingAck.ackCt)
            assertTrue(aliceStore.processRatchetAck(aliceEntry.id, repostedAck), "Alice must accept re-posted ack")
            assertNull(aliceStore.getFriend(aliceEntry.id)!!.pendingRotation, "Alice must commit after receiving re-posted ack")

            // Alice now sends on the new token (T_AB_new). Bob receives it and clears PendingAck.
            val aliceCommitted = aliceStore.getFriend(aliceEntry.id)!!
            val loc1 = LocationPlaintext(4.0, 5.0, 1.0, 2000L)
            val (aliceSess1, ct1) = Session.encryptLocation(
                aliceCommitted.session, loc1,
                aliceCommitted.session.aliceFp, aliceCommitted.session.bobFp,
            )
            aliceStore.updateSession(aliceEntry.id, aliceSess1)

            val result = bobStore.processBatch(
                bobEntry.id,
                bobStore.getFriend(bobEntry.id)!!.session.recvToken.toHex(),
                listOf(EncryptedLocationPayload(seq = aliceSess1.sendSeq.toString(), ct = ct1)),
            )!!
            assertEquals(1, result.decryptedLocations.size, "Bob must decrypt Alice's post-commit location")
            assertEquals(loc1.lat, result.decryptedLocations[0].lat)

            // Bob's PendingAck is cleared — he knows Alice committed.
            assertNull(bobStore.getFriend(bobEntry.id)!!.pendingAck, "Bob must clear PendingAck after Alice's first post-commit message")
        }

    @Test
    fun testPendingAckNotClearedOnOldToken() =
        runBlocking {
            // Setup Bob and Alice
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob")!!
            val bundle = bobStore.generateOpkBundle(bobEntry.id, count = 1)!!
            aliceStore.storeOpkBundle(aliceEntry.id, bundle)

            // 1. Bob processes EpochRotation, switches to T_AB_new
            val loc0 = LocationPlaintext(1.0, 1.0, 1.0, 1000L)
            val aliceFriend0 = aliceStore.getFriend(aliceEntry.id)!!
            val (aliceSess0, ct0) = Session.encryptLocation(
                aliceFriend0.session, loc0,
                aliceFriend0.session.aliceFp, aliceFriend0.session.bobFp,
            )
            aliceStore.updateSession(aliceEntry.id, aliceSess0)
            val rotPayload = aliceStore.initiateRotation(aliceEntry.id)!!

            val oldToken = bobEntry.session.recvToken.toHex()
            bobStore.processBatch(bobEntry.id, oldToken, listOf(EncryptedLocationPayload(seq = aliceSess0.sendSeq.toString(), ct = ct0), rotPayload))

            val bobAfterRot = bobStore.getFriend(bobEntry.id)!!
            assertNotNull(bobAfterRot.pendingAck)
            val expectedNewToken = bobAfterRot.pendingAck!!.expectedRecvToken
            val oldTokenFromSession = bobAfterRot.session.recvToken.toHex()
            
            // The current session's recvToken IS the new one
            assertEquals(expectedNewToken, oldTokenFromSession)
            // It MUST be different from the oldToken we used to poll
            assertFalse(expectedNewToken == oldToken, "New token must differ from old token")

            // 2. Bob receives a delayed location on the OLD token.
            val result = bobStore.processBatch(
                bobEntry.id,
                oldToken, // NOT expectedNewToken
                listOf()
            )!!

            // We can't easily decrypt for the old epoch once rotated without keeping the old session.
            // But we can verify that IF decryptedLocations was non-empty, it still wouldn't clear if the token is wrong.

            // Let's use a trick: Step 2 decrypts locations using the CURRENT session.
            // If we send a message on the NEW epoch, but with the OLD token, it would decrypt
            // but SHOULD NOT clear pendingAck.

            val aliceCommitted = aliceStore.getFriend(aliceEntry.id)!!
            // Wait, we need Alice to commit to get the new session
            val ackPayload = RatchetAckPayload(ct = bobAfterRot.pendingAck!!.ackCt)
            aliceStore.processRatchetAck(aliceEntry.id, ackPayload)
            val aliceCommittedSess = aliceStore.getFriend(aliceEntry.id)!!

            val locNewEpoch = LocationPlaintext(2.0, 2.0, 1.0, 2000L)
            val (aliceSessNew, ctNew) = Session.encryptLocation(
                aliceCommittedSess.session, locNewEpoch,
                aliceCommittedSess.session.aliceFp, aliceCommittedSess.session.bobFp,
            )

            // Receive message on the OLD token but with NEW session (simulates server/proxy weirdness)
            bobStore.processBatch(
                bobEntry.id,
                oldToken,
                listOf(EncryptedLocationPayload(seq = aliceSessNew.sendSeq.toString(), ct = ctNew))
            )!!

            // If it decrypted (it should, as seq is correct for the current session),
            // it MUST NOT clear pendingAck because the token is wrong.
            assertNotNull(bobStore.getFriend(bobEntry.id)!!.pendingAck, "PendingAck must not be cleared by messages on the old token")

            // 3. Receive a FRESH message on the NEW token → CLEAR pendingAck
            val locNewEpoch2 = LocationPlaintext(3.0, 3.0, 1.0, 3000L)
            val (aliceSessNew2, ctNew2) = Session.encryptLocation(
                aliceSessNew, locNewEpoch2,
                aliceCommittedSess.session.aliceFp, aliceCommittedSess.session.bobFp,
            )

            bobStore.processBatch(
                bobEntry.id,
                expectedNewToken,
                listOf(EncryptedLocationPayload(seq = aliceSessNew2.sendSeq.toString(), ct = ctNew2))
            )!!
            assertNull(bobStore.getFriend(bobEntry.id)!!.pendingAck, "PendingAck must be cleared by messages on the new token")
        }
}
