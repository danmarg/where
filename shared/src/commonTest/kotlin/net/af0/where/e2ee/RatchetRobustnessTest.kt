package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MockMailboxClient : MailboxClient {
    var nextPostShouldFail = false
    val posts = mutableListOf<Triple<String, String, MailboxPayload>>()

    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        if (nextPostShouldFail) {
            nextPostShouldFail = false
            throw ServerException(500, "Simulated failure")
        }
        posts.add(Triple(baseUrl, token, payload))
    }

    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> = emptyList()

    override suspend fun ack(
        baseUrl: String,
        token: String,
        count: Int,
    ) {}
}

class RatchetRobustnessTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testTheory4_KeepaliveFailureRecovery() =
        runTest {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val mailbox = MockMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceManager, mailbox)

            // 1. Establish session
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 2. Bob ratchets (Alice receives Bob's message)
            val (bState, bMsg) = Session.encryptMessage(bobManager.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobManager.updateSession(bobToAliceId, bState)
            aliceManager.processBatch(aliceToBobId, aliceManager.getFriend(aliceToBobId)!!.session.recvToken.toHex(), listOf(bMsg))

            val aliceBefore = aliceManager.getFriend(aliceToBobId)!!
            assertTrue(aliceBefore.session.isSendTokenPending, "Alice should be pending transition after receiving Bob's DH key")
            val prevToken = aliceBefore.session.prevSendToken.toHex()
            val nextToken = aliceBefore.session.sendToken.toHex()
            assertNotEquals(prevToken, nextToken)

            // 3. Alice sends a Location update.
            // We want the FIRST post (the Location) to succeed, but the SECOND post (the Keepalive in finalize) to FAIL.
            // Since finalizeTokenTransition is called AFTER the first post succeeds, we can set the fail flag now.
            mailbox.nextPostShouldFail = true // This will affect the next post Alice does.

            // Wait, if we set it now, the Location post will fail.
            // We need to trigger the failure ONLY for the Keepalive.
            // Let's use a more sophisticated mock.
            val trickyMailbox =
                object : MailboxClient {
                    var postCount = 0
                    var failOnPostIndex = -1
                    val posts = mutableListOf<Triple<String, String, MailboxPayload>>()

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {
                        postCount++
                        if (postCount == failOnPostIndex) {
                            throw ServerException(500, "Simulated failure")
                        }
                        posts.add(Triple(baseUrl, token, payload))
                    }

                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ) = emptyList<MailboxPayload>()

                    override suspend fun ack(
                        baseUrl: String,
                        token: String,
                        count: Int,
                    ) {}
                }
            val aliceClientTricky = LocationClient("http://fake", aliceManager, trickyMailbox)

            trickyMailbox.failOnPostIndex = 2 // 1st is Location, 2nd is Keepalive

            aliceClientTricky.sendLocation(37.0, -122.0)

            // 4. Verify state
            val aliceAfterFirstSend = aliceManager.getFriend(aliceToBobId)!!
            assertFalse(aliceAfterFirstSend.session.isSendTokenPending, "isSendTokenPending should be cleared even if Keepalive failed")
            assertNotNull(aliceAfterFirstSend.outbox, "Outbox should contain the failed Keepalive")
            assertEquals(nextToken, aliceAfterFirstSend.outbox!!.token, "Outbox should be targeted at the NEW token")
            assertEquals(1, trickyMailbox.posts.size, "Only the first post (Location) should have succeeded")
            assertEquals(prevToken, trickyMailbox.posts[0].second, "Location should have been posted to the PREV token")

            // 5. Recovery! Run processOutboxes
            aliceClientTricky.poll() // poll calls processOutboxes

            val aliceAfterRecovery = aliceManager.getFriend(aliceToBobId)!!
            assertNull(aliceAfterRecovery.outbox, "Outbox should be cleared after recovery")
            assertEquals(2, trickyMailbox.posts.size, "Keepalive should have been posted during recovery")
            assertEquals(nextToken, trickyMailbox.posts[1].second, "Keepalive should have been posted to the NEW token")
        }

    @Test
    fun testRecoveryFromInitialPostFailure() =
        runTest {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val mailbox = MockMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceManager, mailbox)

            // 1. Establish session and ratchet Alice
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobManager.listFriends().first().id

            val (bState, bMsg) = Session.encryptMessage(bobManager.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobManager.updateSession(bobToAliceId, bState)
            aliceManager.processBatch(aliceToBobId, aliceManager.getFriend(aliceToBobId)!!.session.recvToken.toHex(), listOf(bMsg))

            // 2. Alice sends Location, but it FAILS.
            mailbox.nextPostShouldFail = true
            try {
                aliceClient.sendLocation(37.0, -122.0)
                fail("Should have thrown")
            } catch (e: ServerException) {
                // expected
            }

            // 3. Verify state: outbox has Location, pending is STILL TRUE
            val aliceAfterFail = aliceManager.getFriend(aliceToBobId)!!
            assertTrue(aliceAfterFail.session.isSendTokenPending, "isSendTokenPending should still be true")
            assertNotNull(aliceAfterFail.outbox, "Outbox should contain the failed Location update")
            val prevToken = aliceAfterFail.session.prevSendToken.toHex()
            assertEquals(prevToken, aliceAfterFail.outbox!!.token, "Failed Location should be targeted at PREV token")

            // 4. Recovery!
            aliceClient.poll()

            // 5. Verify: Location posted to PREV, Keepalive posted to NEW, pending is FALSE
            val aliceFinal = aliceManager.getFriend(aliceToBobId)!!
            assertFalse(aliceFinal.session.isSendTokenPending, "isSendTokenPending should be cleared after recovery")
            assertNull(aliceFinal.outbox, "Outbox should be cleared")

            assertEquals(2, mailbox.posts.size)
            assertEquals(prevToken, mailbox.posts[0].second, "Location should be on PREV token")
            assertEquals(aliceFinal.session.sendToken.toHex(), mailbox.posts[1].second, "Keepalive should be on NEW token")
        }

    @Test
    fun testNeedsRatchetRestartRecovery() =
        runTest {
            // 1. Establish session
            val storage = MemoryStorage()
            val aliceManager = E2eeManager(storage)
            val bobManager = E2eeManager(MemoryStorage())

            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 2. Alice sends a keepalive to prime the ratchet (carries DH_A1)
            val (aState1, aMsg1) = Session.encryptMessage(aliceManager.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceManager.updateSession(aliceToBobId, aState1)
            bobManager.processBatch(bobToAliceId, bobManager.getFriend(bobToAliceId)!!.session.recvToken.toHex(), listOf(aMsg1))

            // 3. Bob sends back (carries DH_B1, advancing Bob's epoch)
            val (bState1, bMsg1) = Session.encryptMessage(bobManager.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobManager.updateSession(bobToAliceId, bState1)

            // 4. Alice receives Bob's message — she ratchets her send chain, but needsRatchet stays false
            aliceManager.processBatch(aliceToBobId, aliceManager.getFriend(aliceToBobId)!!.session.recvToken.toHex(), listOf(bMsg1))

            // Manually force needsRatchet=true to simulate a "stale" or "lost keys" condition
            // that we want to test recovery for.
            aliceManager.updateFriend(aliceToBobId) { it.copy(session = it.session.copy(needsRatchet = true)) }

            val aliceBeforeRestart = aliceManager.getFriend(aliceToBobId)!!
            assertTrue(aliceBeforeRestart.session.needsRatchet, "Alice should have needsRatchet=true (manually set for test)")

            // 5. Simulate restart: create a brand-new E2eeManager from the same storage.
            //    The persisted state still has needsRatchet=true.
            val aliceManagerAfterRestart = E2eeManager(storage)
            val aliceAfterRestart = aliceManagerAfterRestart.getFriend(aliceToBobId)!!
            assertTrue(aliceAfterRestart.session.needsRatchet, "needsRatchet must survive serialization/restart")

            // 6. Setup client with tracking mailbox
            // NOTE: We toggle sharingEnabled = false for Alice to ensure the automated
            // keepalive fires after poll. LocationClient gates automated keepalives
            // on (!sharingEnabled || isPaused) to prevent loops during active sharing (§5.3).
            aliceManagerAfterRestart.updateFriend(aliceToBobId) { it.copy(sharingEnabled = false) }

            val trackingMailbox =
                object : MailboxClient {
                    val posts = mutableListOf<Triple<String, String, MailboxPayload>>()

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {
                        posts.add(Triple(baseUrl, token, payload))
                    }

                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> = emptyList()

                    override suspend fun ack(
                        baseUrl: String,
                        token: String,
                        count: Int,
                    ) {}
                }
            val aliceClientAfterRestart = LocationClient("http://fake", aliceManagerAfterRestart, trackingMailbox)

            // 7. Call poll() — the automated keepalive should fire POST-poll because needsRatchet=true
            // (sharingEnabled=true but isPaused=default(false), so it will fire)
            aliceClientAfterRestart.poll()

            // 8. Verify: keepalive was posted (automated keepalive fired)
            assertTrue(trackingMailbox.posts.isNotEmpty(), "Automated keepalive must fire after poll when needsRatchet=true")

            // 9. Verify: needsRatchet is now cleared
            val aliceFinal = aliceManagerAfterRestart.getFriend(aliceToBobId)!!
            assertFalse(aliceFinal.session.needsRatchet, "needsRatchet should be cleared after pre-poll keepalive")
            assertFalse(aliceFinal.session.isSendTokenPending, "isSendTokenPending should be cleared after keepalive")
        }
}
