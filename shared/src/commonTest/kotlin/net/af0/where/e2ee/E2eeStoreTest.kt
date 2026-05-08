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
    private lateinit var timeProvider: MockTimeProvider

    @BeforeTest
    fun setup() {
        timeProvider = MockTimeProvider()
        TimeSource.setProvider(timeProvider)
        aliceStorage = MemoryStorage()
        bobStorage = MemoryStorage()
        aliceStore = E2eeStore(aliceStorage)
        bobStore = E2eeStore(bobStorage)
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
            val qr = aliceStore.createInvite("Alice")

            // Bob scans QR → gets wire payload ready to POST
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)

            // Alice processes Bob's init payload (received from discovery inbox)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

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
            assertTrue(aliceStore.listPendingInvites().isEmpty())
            val qr = aliceStore.createInvite("Alice")
            assertEquals(1, aliceStore.listPendingInvites().size)
            assertContentEquals(qr.ekPub, aliceStore.listPendingInvites().first().qrPayload.ekPub)
        }

    @Test
    fun testClearInvite() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            assertEquals(1, aliceStore.listPendingInvites().size)
            aliceStore.clearInvite(qr.ekPub)
            assertTrue(aliceStore.listPendingInvites().isEmpty())
        }

    @Test
    fun testPersistence() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceEntryBefore = aliceStore.listFriends().first()

            // Reload Alice's store from same storage
            val reloadedAliceStore = E2eeStore(aliceStorage)
            val aliceEntryAfter = reloadedAliceStore.listFriends().first()

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
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

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
            assertEquals(1, reloadedAliceStore.listPendingInvites().size)
            assertContentEquals(qr.ekPub, reloadedAliceStore.listPendingInvites().first().qrPayload.ekPub)

            // Processing an init against a reloaded store must succeed
            val (initPayload, _) = bobStore.processScannedQr(qr)
            val result = reloadedAliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            assertNotNull(result)
            assertEquals("Bob", result.name)
        }

    @Test
    fun testSelfScanRejected() =
        runBlocking {
            // Alice creates an invite QR.
            val qr = aliceStore.createInvite("Alice")

            // Alice tries to scan her own QR — must be rejected.
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    aliceStore.processScannedQr(qr)
                }
            assertTrue(ex.message?.contains("yourself") == true)

            // Invite must still be present (no side-effects from the rejected scan).
            assertEquals(1, aliceStore.listPendingInvites().size)
            Unit
        }

    @Test
    fun testSelfScanRejectedOnlyWhenEkPubMatches() =
        runBlocking {
            // Alice creates an invite QR.
            val aliceQr = aliceStore.createInvite("Alice")

            // Bob has a different pending invite but scans Alice's QR — must succeed.
            bobStore.createInvite("Bob")
            val (initPayload, bobEntry) = bobStore.processScannedQr(aliceQr)
            // Both components must be non-null
            assertEquals(initPayload.v, 1)
            assertEquals(bobEntry.name, "Alice")
        }

    @Test
    fun testProcessKeyExchangeInitFailsWithoutPendingInvite() =
        runBlocking {
            val qr = bobStore.createInvite("Bob")
            val (initPayload, _) = aliceStore.processScannedQr(qr)
            // freshStore has no pending invite
            val freshStore = E2eeStore(MemoryStorage())
            assertNull(freshStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub))
        }

    // -----------------------------------------------------------------------
    // Role flags
    // -----------------------------------------------------------------------

    @Test
    fun testIsInitiatorFlag() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            assertFalse(bobEntry.isInitiator, "Bob scanned QR — must NOT be initiator")
            assertTrue(aliceEntry.isInitiator, "Alice created QR — must be initiator")
        }

    @Test
    fun testStalenessHeuristic() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Not stale initially
            assertFalse(aliceStore.getFriend(aliceEntry.id)!!.isStale)

            // Test staleness
            val entry = aliceStore.getFriend(aliceEntry.id)!!
            val staleEntry = entry.copy(lastRecvTs = currentTimeSeconds() - FriendEntry.ACK_TIMEOUT_SECONDS - 10)
            assertTrue(staleEntry.isStale, "Should be stale if last message is old")
        }

    @Test
    fun testBatchProcessingWithRatchet() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            val loc1 = MessagePlaintext.Location(1.0, 1.0, 1.0, 1000L)
            val (aliceSess1, msg1) = Session.encryptMessage(aliceEntry.session, loc1)
            aliceStore.updateSession(aliceEntry.id, aliceSess1)

            // Bob processes message 1
            val res1 = bobStore.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(msg1))!!
            assertEquals(1, res1.decryptedLocations.size)
            assertEquals(1.0, res1.decryptedLocations[0].lat)

            val bobFriend1 = bobStore.getFriend(bobEntry.id)!!

            // Bob sends message 2
            val loc2 = MessagePlaintext.Location(2.0, 2.0, 1.0, 2000L)
            val (bobSess2, msg2) = Session.encryptMessage(bobFriend1.session, loc2)
            bobStore.updateSession(bobEntry.id, bobSess2)

            // Alice processes message 2, advancing DH
            val res2 = aliceStore.processBatch(aliceEntry.id, aliceEntry.session.recvToken.toHex(), listOf(msg2))!!
            assertEquals(1, res2.decryptedLocations.size)
            assertEquals(2.0, res2.decryptedLocations[0].lat)

            val aliceFriend2 = aliceStore.getFriend(aliceEntry.id)!!
            assertNotEquals(aliceEntry.session.rootKey.toHex(), aliceFriend2.session.rootKey.toHex(), "Alice's root key should change")

            // Alice sends message 3
            val loc3 = MessagePlaintext.Location(3.0, 3.0, 1.0, 3000L)
            val (aliceSess3, msg3) = Session.encryptMessage(aliceFriend2.session, loc3)
            aliceStore.updateSession(aliceEntry.id, aliceSess3)

            // Bob processes message 3, also advancing DH
            val res3 = bobStore.processBatch(bobFriend1.id, bobFriend1.session.recvToken.toHex(), listOf(msg3))!!
            assertEquals(1, res3.decryptedLocations.size)
            assertEquals(3.0, res3.decryptedLocations[0].lat)

            val bobFriend3 = bobStore.getFriend(bobFriend1.id)!!
            assertEquals(aliceSess3.sendToken.toHex(), bobFriend3.session.recvToken.toHex())
        }

    @Test
    fun testClearSpecificInvite() =
        runBlocking {
            val qr1 = aliceStore.createInvite("Alice 1")
            val qr2 = aliceStore.createInvite("Alice 2")
            assertEquals(2, aliceStore.listPendingInvites().size)

            // Clear the first one specifically
            aliceStore.clearInvite(qr1.ekPub)
            val remaining = aliceStore.listPendingInvites()
            assertEquals(1, remaining.size)
            assertEquals("Alice 2", remaining[0].qrPayload.suggestedName)

            // Clear the second one surgically
            aliceStore.clearInvite(qr2.ekPub)
            assertTrue(aliceStore.listPendingInvites().isEmpty())
        }

    @Test
    fun testMultiplePendingInvites() =
        runBlocking {
            val qr1 = aliceStore.createInvite("Alice 1")
            val qr2 = aliceStore.createInvite("Alice 2")

            val invites = aliceStore.listPendingInvites()
            assertEquals(2, invites.size)
            assertEquals("Alice 1", invites[0].qrPayload.suggestedName)
            assertEquals("Alice 2", invites[1].qrPayload.suggestedName)

            // Bob accepts the first invite
            val (initPayload1, _) = bobStore.processScannedQr(qr1)
            val result1 = aliceStore.processKeyExchangeInit(initPayload1, "Bob 1", qr1.ekPub)
            assertNotNull(result1)

            // Should still have 1 pending invite
            assertEquals(1, aliceStore.listPendingInvites().size)
            assertEquals("Alice 2", aliceStore.listPendingInvites()[0].qrPayload.suggestedName)

            // Bob accepts the second invite
            val (initPayload2, _) = bobStore.processScannedQr(qr2)
            val result2 = aliceStore.processKeyExchangeInit(initPayload2, "Bob 2", qr2.ekPub)
            assertNotNull(result2)

            // No pending invites left
            assertTrue(aliceStore.listPendingInvites().isEmpty())
        }

    @Test
    fun testMaxInvitesCap() =
        runBlocking {
            repeat(E2eeStore.MAX_PENDING_INVITES) {
                aliceStore.createInvite("Alice $it")
            }
            assertEquals(E2eeStore.MAX_PENDING_INVITES, aliceStore.listPendingInvites().size)

            assertFailsWith<IllegalStateException> {
                aliceStore.createInvite("Alice too many")
            }
            Unit
        }

    @Test
    fun testInviteExpiryCleanup() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")

            // Not expired yet
            aliceStore.cleanupExpiredInvites(expirySeconds = 3600)
            assertEquals(1, aliceStore.listPendingInvites().size)

            // Force expiry: expirySeconds=0 makes (now - createdAt > 0) true.
            // Advance time to guarantee now > createdAt.
            timeProvider.advanceSeconds(2)
            aliceStore.cleanupExpiredInvites(expirySeconds = 0)
            assertTrue(aliceStore.listPendingInvites().isEmpty())
        }

    @Test
    fun testExportedInvitePersistence() =
        runBlocking {
            val qrExported = aliceStore.createInvite("Exported")
            val qrNormal = aliceStore.createInvite("Normal")

            // Advance time so that createdAt is definitely in the past relative to now
            timeProvider.advanceSeconds(2)

            // Mark one as exported NOW. Its exportedAt will be currentTimeSeconds().
            aliceStore.markInviteExported(qrExported.ekPub)

            // Run cleanup with expirySeconds=0.
            // For qrNormal: now - createdAt > 0 -> removed.
            // For qrExported: now - exportedAt == 0 -> survives.
            aliceStore.cleanupExpiredInvites(expirySeconds = 0)

            val remaining = aliceStore.listPendingInvites()
            assertEquals(1, remaining.size, "Only the exported invite should survive")
            assertEquals("Exported", remaining[0].qrPayload.suggestedName)
        }

    @Test
    fun testLastDecryptFailedFlag() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Initially false
            assertFalse(bobStore.getFriend(bobEntry.id)!!.lastDecryptFailed)

            // Simulate a decryption failure: Bob receives a message meant for someone else
            // (or just a random payload that doesn't match his header keys).
            val unrelatedLoc = MessagePlaintext.Location(9.9, 9.9, 1.0, 9999L)
            // We need a third person to generate a validly-structured but key-mismatched message
            val charlieStore = E2eeStore(MemoryStorage())
            val charlieQr = charlieStore.createInvite("Charlie")
            val (charlieInit, _) = aliceStore.processScannedQr(charlieQr)
            val charlieEntry = charlieStore.processKeyExchangeInit(charlieInit, "Alice", charlieQr.ekPub)!!

            val (sess, msg) = Session.encryptMessage(charlieEntry.session, unrelatedLoc)

            // Bob tries to process Charlie's message
            bobStore.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(msg))
            assertTrue(bobStore.getFriend(bobEntry.id)!!.lastDecryptFailed, "Should be true after decryption failure")

            // Now Alice sends a valid message to Bob
            val validLoc = MessagePlaintext.Location(1.1, 1.1, 1.0, 1111L)
            val (aliceSess, validMsg) = Session.encryptMessage(aliceEntry.session, validLoc)
            aliceStore.updateSession(aliceEntry.id, aliceSess)

            bobStore.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(validMsg))
            assertFalse(bobStore.getFriend(bobEntry.id)!!.lastDecryptFailed, "Should be reset to false after success")
        }

    @Test
    fun testClearSendTokenPendingAtomicity() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val aliceEntry = aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)!!

            // Set isSendTokenPending to true
            val sessionWithPending = aliceEntry.session.copy(isSendTokenPending = true)
            aliceStore.updateSession(aliceEntry.id, sessionWithPending)

            assertTrue(aliceStore.getFriend(aliceEntry.id)!!.session.isSendTokenPending)

            // First call returns true
            assertTrue(aliceStore.clearSendTokenPending(aliceEntry.id))

            // Second call returns false
            assertFalse(aliceStore.clearSendTokenPending(aliceEntry.id))

            assertFalse(aliceStore.getFriend(aliceEntry.id)!!.session.isSendTokenPending)
        }

    @Test
    fun testCleanupExpiredFriends() =
        runBlocking {
            val qr = aliceStore.createInvite("Alice")
            // Bob scans Alice's QR. Bob's entry for Alice starts as unconfirmed.
            val (_, bobFriendEntry) = bobStore.processScannedQr(qr)

            assertFalse(bobStore.getFriend(bobFriendEntry.id)!!.isConfirmed)

            // Advance time past expiry
            timeProvider.advanceSeconds(3601)

            // Cleanup Bob's store with 3600s threshold
            bobStore.cleanupExpiredInvites(expirySeconds = 3600)

            // Bob's entry for Alice should be removed
            assertNull(bobStore.getFriend(bobFriendEntry.id))
        }
}
