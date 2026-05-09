package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MemoryStorage : RawKeyValueStorage {
    private val data = mutableMapOf<String, String>()

    override fun getString(key: String): String? = data[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        data[key] = value
    }
}

class E2eeManagerTest {
    init {
        initializeE2eeTests()
    }

    private lateinit var aliceStorage: MemoryStorage
    private lateinit var bobStorage: MemoryStorage
    private lateinit var aliceManager: E2eeManager
    private lateinit var bobManager: E2eeManager
    private lateinit var timeProvider: MockTimeProvider

    @BeforeTest
    fun setup() {
        timeProvider = MockTimeProvider()
        TimeSource.setProvider(timeProvider)
        aliceStorage = MemoryStorage()
        bobStorage = MemoryStorage()
        aliceManager = E2eeManager(aliceStorage)
        bobManager = E2eeManager(bobStorage)
    }

    @AfterTest
    fun tearDown() {
        // Reset to default provider to avoid side effects on other tests
        TimeSource.setProvider(
            object : TimeProvider {
                override fun currentTimeSeconds(): Long = DefaultTimeProvider.currentTimeSeconds()

                override fun currentTimeMillis(): Long = DefaultTimeProvider.currentTimeMillis()
            },
        )
    }

    @Test
    fun testKeyExchangeFlow() =
        runBlocking {
            // Alice creates invite
            val qr = aliceManager.createInvite("Alice")

            // Bob scans QR → gets wire payload ready to POST
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)

            // Alice processes Bob's init payload (received from discovery inbox)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            assertNotNull(aliceEntry)

            // Friend IDs are based on aliceFp = SHA-256(EK_A.pub)
            val expectedAliceFp = fingerprint(qr.ekPub).toHex()
            assertEquals(expectedAliceFp, bobEntry.id)
            assertEquals(expectedAliceFp, aliceEntry.id)
            assertEquals("Alice", bobEntry.name)
            assertEquals("Bob", aliceEntry.name)
        }

    @Test
    fun testPendingInviteAccessibleBeforeExchange() =
        runBlocking {
            assertTrue(aliceManager.listPendingInvites().isEmpty())
            val qr = aliceManager.createInvite("Alice")
            assertEquals(1, aliceManager.listPendingInvites().size)
            assertContentEquals(qr.ekPub, aliceManager.listPendingInvites().first().qrPayload.ekPub)
        }

    @Test
    fun testClearInvite() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            assertEquals(1, aliceManager.listPendingInvites().size)
            aliceManager.clearInvite(qr.ekPub)
            assertTrue(aliceManager.listPendingInvites().isEmpty())
        }

    @Test
    fun testPersistence() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceEntryBefore = aliceManager.listFriends().first()

            // Reload Alice's store from same storage
            val reloadedAliceManager = E2eeManager(aliceStorage)
            val aliceEntryAfter = reloadedAliceManager.listFriends().first()

            assertEquals(aliceEntryBefore.id, aliceEntryAfter.id)
            assertEquals(aliceEntryBefore.name, aliceEntryAfter.name)

            // SessionState has transient fields (localDhPriv), so equals() might fail.
            // But we ensure stable fields are equal.
            assertEquals(aliceEntryBefore.session.rootKey.toHex(), aliceEntryAfter.session.rootKey.toHex())
            assertEquals(aliceEntryBefore.session.sendToken.toHex(), aliceEntryAfter.session.sendToken.toHex())
        }

    @Test
    fun testDeleteFriend() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            aliceManager.deleteFriend(aliceEntry.id)
            assertTrue(aliceManager.listFriends().isEmpty())
            assertNull(aliceManager.getFriend(aliceEntry.id))

            // Verify persistence after delete
            val reloadedAliceManager = E2eeManager(aliceStorage)
            assertTrue(reloadedAliceManager.listFriends().isEmpty())
        }

    @Test
    fun testPendingInvitePersisted() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")

            // Reloading should have the pending invite persisted
            val reloadedAliceManager = E2eeManager(aliceStorage)
            assertEquals(1, reloadedAliceManager.listPendingInvites().size)
            assertContentEquals(qr.ekPub, reloadedAliceManager.listPendingInvites().first().qrPayload.ekPub)

            // Processing an init against a reloaded store must succeed
            val (initPayload, _) = bobManager.processScannedQr(qr)
            val result = reloadedAliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            assertNotNull(result)
            assertEquals("Bob", result.name)
        }

    @Test
    fun testSelfScanRejected() =
        runBlocking {
            // Alice creates an invite QR.
            val qr = aliceManager.createInvite("Alice")

            // Alice tries to scan her own QR — must be rejected.
            assertFailsWith<SelfPairingException> {
                aliceManager.processScannedQr(qr)
            }

            // Invite must still be present (no side-effects from the rejected scan).
            assertEquals(1, aliceManager.listPendingInvites().size)
            Unit
        }

    @Test
    fun testSelfScanRejectedOnlyWhenEkPubMatches() =
        runBlocking {
            // Alice creates an invite QR.
            val aliceQr = aliceManager.createInvite("Alice")

            // Bob has a different pending invite but scans Alice's QR — must succeed.
            bobManager.createInvite("Bob")
            val (initPayload, bobEntry) = bobManager.processScannedQr(aliceQr)
            // Both components must be non-null
            assertEquals(initPayload.v, 1)
            assertEquals(bobEntry.name, "Alice")
        }

    @Test
    fun testProcessKeyExchangeInitFailsWithoutPendingInvite() =
        runBlocking {
            val qr = bobManager.createInvite("Bob")
            val (initPayload, _) = aliceManager.processScannedQr(qr)
            // freshStore has no pending invite
            val freshStore = E2eeManager(MemoryStorage())
            assertNull(freshStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub))
        }

    // -----------------------------------------------------------------------
    // Role flags
    // -----------------------------------------------------------------------

    @Test
    fun testIsInitiatorFlag() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            assertFalse(bobEntry.isInitiator, "Bob scanned QR — must NOT be initiator")
            assertTrue(aliceEntry.isInitiator, "Alice created QR — must be initiator")
        }

    @Test
    fun testStalenessHeuristic() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Not stale initially
            assertFalse(aliceManager.getFriend(aliceEntry.id)!!.isStale)

            // Test staleness
            val entry = aliceManager.getFriend(aliceEntry.id)!!
            val staleEntry = entry.copy(lastRecvTs = currentTimeSeconds() - FriendEntry.ACK_TIMEOUT_SECONDS - 10)
            assertTrue(staleEntry.isStale, "Should be stale if last message is old")
        }

    @Test
    fun testBatchProcessingWithRatchet() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            val loc1 = MessagePlaintext.Location(1.0, 1.0, 1.0, 1000L)
            val (aliceSess1, msg1) = Session.encryptMessage(aliceEntry.session, loc1)
            aliceManager.updateSession(aliceEntry.id, aliceSess1)

            // Bob processes message 1
            val res1 = bobManager.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(msg1))!!
            assertEquals(1, res1.decryptedLocations.size)
            assertEquals(1.0, res1.decryptedLocations[0].lat)

            val bobFriend1 = bobManager.getFriend(bobEntry.id)!!

            // Bob sends message 2
            val loc2 = MessagePlaintext.Location(2.0, 2.0, 1.0, 2000L)
            val (bobSess2, msg2) = Session.encryptMessage(bobFriend1.session, loc2)
            bobManager.updateSession(bobEntry.id, bobSess2)

            // Alice processes message 2, advancing DH
            val res2 = aliceManager.processBatch(aliceEntry.id, aliceEntry.session.recvToken.toHex(), listOf(msg2))!!
            assertEquals(1, res2.decryptedLocations.size)
            assertEquals(2.0, res2.decryptedLocations[0].lat)

            val aliceFriend2 = aliceManager.getFriend(aliceEntry.id)!!
            assertNotEquals(aliceEntry.session.rootKey.toHex(), aliceFriend2.session.rootKey.toHex(), "Alice's root key should change")

            // Alice sends message 3
            val loc3 = MessagePlaintext.Location(3.0, 3.0, 1.0, 3000L)
            val (aliceSess3, msg3) = Session.encryptMessage(aliceFriend2.session, loc3)
            aliceManager.updateSession(aliceEntry.id, aliceSess3)

            // Bob processes message 3, also advancing DH
            val res3 = bobManager.processBatch(bobFriend1.id, bobFriend1.session.recvToken.toHex(), listOf(msg3))!!
            assertEquals(1, res3.decryptedLocations.size)
            assertEquals(3.0, res3.decryptedLocations[0].lat)

            val bobFriend3 = bobManager.getFriend(bobFriend1.id)!!
            assertEquals(aliceSess3.sendToken.toHex(), bobFriend3.session.recvToken.toHex())
        }

    @Test
    fun testClearSpecificInvite() =
        runBlocking {
            val qr1 = aliceManager.createInvite("Alice 1")
            val qr2 = aliceManager.createInvite("Alice 2")
            assertEquals(2, aliceManager.listPendingInvites().size)

            // Clear the first one specifically
            aliceManager.clearInvite(qr1.ekPub)
            val remaining = aliceManager.listPendingInvites()
            assertEquals(1, remaining.size)
            assertEquals("Alice 2", remaining[0].qrPayload.suggestedName)

            // Clear the second one surgically
            aliceManager.clearInvite(qr2.ekPub)
            assertTrue(aliceManager.listPendingInvites().isEmpty())
        }

    @Test
    fun testMultiplePendingInvites() =
        runBlocking {
            val qr1 = aliceManager.createInvite("Alice 1")
            val qr2 = aliceManager.createInvite("Alice 2")

            val invites = aliceManager.listPendingInvites()
            assertEquals(2, invites.size)
            assertEquals("Alice 1", invites[0].qrPayload.suggestedName)
            assertEquals("Alice 2", invites[1].qrPayload.suggestedName)

            // Bob accepts the first invite
            val (initPayload1, _) = bobManager.processScannedQr(qr1)
            val result1 = aliceManager.processKeyExchangeInit(initPayload1, "Bob 1", qr1.ekPub)
            assertNotNull(result1)

            // Should still have 1 pending invite
            assertEquals(1, aliceManager.listPendingInvites().size)
            assertEquals("Alice 2", aliceManager.listPendingInvites()[0].qrPayload.suggestedName)

            // Bob accepts the second invite
            val (initPayload2, _) = bobManager.processScannedQr(qr2)
            val result2 = aliceManager.processKeyExchangeInit(initPayload2, "Bob 2", qr2.ekPub)
            assertNotNull(result2)

            // No pending invites left
            assertTrue(aliceManager.listPendingInvites().isEmpty())
        }

    @Test
    fun testMaxInvitesCap() =
        runBlocking {
            repeat(E2eeManager.MAX_PENDING_INVITES) {
                aliceManager.createInvite("Alice $it")
            }
            assertEquals(E2eeManager.MAX_PENDING_INVITES, aliceManager.listPendingInvites().size)

            assertFailsWith<IllegalStateException> {
                aliceManager.createInvite("Alice too many")
            }
            Unit
        }

    @Test
    fun testInviteExpiryCleanup() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")

            // Not expired yet
            aliceManager.cleanupExpiredInvites(expirySeconds = 3600)
            assertEquals(1, aliceManager.listPendingInvites().size)

            // Force expiry: expirySeconds=0 makes (now - createdAt > 0) true.
            // Advance time to guarantee now > createdAt.
            timeProvider.advanceSeconds(2)
            aliceManager.cleanupExpiredInvites(expirySeconds = 0)
            assertTrue(aliceManager.listPendingInvites().isEmpty())
        }

    @Test
    fun testExportedInvitePersistence() =
        runBlocking {
            val qrExported = aliceManager.createInvite("Exported")
            val qrNormal = aliceManager.createInvite("Normal")

            // Advance time so that createdAt is definitely in the past relative to now
            timeProvider.advanceSeconds(2)

            // Mark one as exported NOW. Its exportedAt will be currentTimeSeconds().
            aliceManager.markInviteExported(qrExported.ekPub)

            // Run cleanup with expirySeconds=0.
            // For qrNormal: now - createdAt > 0 -> removed.
            // For qrExported: now - exportedAt == 0 -> survives.
            aliceManager.cleanupExpiredInvites(expirySeconds = 0)

            val remaining = aliceManager.listPendingInvites()
            assertEquals(1, remaining.size, "Only the exported invite should survive")
            assertEquals("Exported", remaining[0].qrPayload.suggestedName)
        }

    @Test
    fun testLastDecryptFailedFlag() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Initially false
            assertFalse(bobManager.getFriend(bobEntry.id)!!.lastDecryptFailed)

            // Simulate a decryption failure: Bob receives a message meant for someone else
            // (or just a random payload that doesn't match his header keys).
            val unrelatedLoc = MessagePlaintext.Location(9.9, 9.9, 1.0, 9999L)
            // We need a third person to generate a validly-structured but key-mismatched message
            val charlieStore = E2eeManager(MemoryStorage())
            val charlieQr = charlieStore.createInvite("Charlie")
            val (charlieInit, _) = aliceManager.processScannedQr(charlieQr)
            val charlieEntry = charlieStore.processKeyExchangeInit(charlieInit, "Alice", charlieQr.ekPub)!!

            val (sess, msg) = Session.encryptMessage(charlieEntry.session, unrelatedLoc)

            // Bob tries to process Charlie's message
            bobManager.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(msg))
            assertTrue(bobManager.getFriend(bobEntry.id)!!.lastDecryptFailed, "Should be true after decryption failure")

            // Now Alice sends a valid message to Bob
            val validLoc = MessagePlaintext.Location(1.1, 1.1, 1.0, 1111L)
            val (aliceSess, validMsg) = Session.encryptMessage(aliceEntry.session, validLoc)
            aliceManager.updateSession(aliceEntry.id, aliceSess)

            bobManager.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(validMsg))
            assertFalse(bobManager.getFriend(bobEntry.id)!!.lastDecryptFailed, "Should be reset to false after success")
        }

    @Test
    fun testClearSendTokenPendingAtomicity() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Set isSendTokenPending to true
            val sessionWithPending = aliceEntry.session.copy(isSendTokenPending = true)
            aliceManager.updateSession(aliceEntry.id, sessionWithPending)

            assertTrue(aliceManager.getFriend(aliceEntry.id)!!.session.isSendTokenPending)

            // First call returns true
            assertTrue(aliceManager.clearSendTokenPending(aliceEntry.id))

            // Second call returns false
            assertFalse(aliceManager.clearSendTokenPending(aliceEntry.id))

            assertFalse(aliceManager.getFriend(aliceEntry.id)!!.session.isSendTokenPending)
        }

    @Test
    fun testCleanupExpiredFriends() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            // Bob scans Alice's QR. Bob's entry for Alice starts as unconfirmed.
            val (_, bobFriendEntry) = bobManager.processScannedQr(qr)

            assertFalse(bobManager.getFriend(bobFriendEntry.id)!!.isConfirmed)

            // Advance time past expiry
            timeProvider.advanceSeconds(3601)

            // Cleanup Bob's store with 3600s threshold
            bobManager.cleanupExpiredInvites(expirySeconds = 3600)

            // Bob's entry for Alice should be removed
            assertNull(bobManager.getFriend(bobFriendEntry.id))
        }

    @Test
    fun testAbandonPendingTransitionRollsBackToken() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Alice sends a message to Bob
            val payload1 = MessagePlaintext.Location(1.0, 2.0, 1.0, 1000)
            aliceManager.encryptAndStore(aliceEntry.id, payload1)

            val afterSend = aliceManager.getFriend(aliceEntry.id)!!
            assertTrue(afterSend.session.isSendTokenPending)
            val prevToken = afterSend.session.prevSendToken
            assertFalse(prevToken.contentEquals(afterSend.session.sendToken))

            // Abandon the transition
            aliceManager.abandonPendingTransition(aliceEntry.id)

            val afterAbandon = aliceManager.getFriend(aliceEntry.id)!!
            assertFalse(afterAbandon.session.isSendTokenPending)
            assertContentEquals(prevToken, afterAbandon.session.sendToken)
            assertNull(afterAbandon.outbox)

            // Send another message
            val payload2 = MessagePlaintext.Location(1.1, 2.1, 1.0, 1001)
            aliceManager.encryptAndStore(aliceEntry.id, payload2)

            val afterSecondSend = aliceManager.getFriend(aliceEntry.id)!!
            assertFalse(afterSecondSend.session.isSendTokenPending)
            assertContentEquals(prevToken, afterSecondSend.session.sendToken)
        }

    @Test
    fun testAbandonPendingTransitionTwice() =
        runBlocking {
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            val aliceEntry = aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Alice sends a message to Bob
            val payload1 = MessagePlaintext.Location(1.0, 2.0, 1.0, 1000)
            aliceManager.encryptAndStore(aliceEntry.id, payload1)

            val afterSend = aliceManager.getFriend(aliceEntry.id)!!
            val prevToken = afterSend.session.prevSendToken

            // Abandon the transition
            aliceManager.abandonPendingTransition(aliceEntry.id)

            val afterAbandon1 = aliceManager.getFriend(aliceEntry.id)!!
            assertContentEquals(prevToken, afterAbandon1.session.sendToken)

            // Abandon again
            aliceManager.abandonPendingTransition(aliceEntry.id)

            val afterAbandon2 = aliceManager.getFriend(aliceEntry.id)!!
            assertContentEquals(prevToken, afterAbandon2.session.sendToken)
            assertFalse(afterAbandon2.session.isSendTokenPending)
        }
}
