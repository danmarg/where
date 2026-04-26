package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FakeMailboxClient : MailboxClient {
    val polls = mutableMapOf<String, MutableList<MailboxPayload>>()
    val posts = mutableListOf<Triple<String, String, MailboxPayload>>()
    var pollCount = 0

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
    ): List<MailboxPayload> {
        pollCount++
        return polls.remove(token) ?: emptyList()
    }
}

/**
 * A self-routing mailbox: post() drops the payload into the same inbox that poll() reads,
 * so both sides of a session can share one instance and messages are delivered automatically.
 */
class RelayMailboxClient : MailboxClient {
    val inbox = mutableMapOf<String, MutableList<MailboxPayload>>()
    val history = mutableListOf<Pair<String, MailboxPayload>>()

    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        inbox.getOrPut(token) { mutableListOf() }.add(payload)
        history.add(token to payload)
    }

    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> = inbox.remove(token) ?: emptyList()

    override suspend fun ack(
        baseUrl: String,
        token: String,
        count: Int,
    ) {}
}

class TokenTransitionTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testTokenFollowLimit() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val fakeMailbox = FakeMailboxClient()
            val client = LocationClient("http://fake", bobStore, fakeMailbox)

            // 1. Establish session
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceFriendId = aliceStore.listFriends().first().id
            val bobFriendId = bobEntry.id

            // 2. Alice sends msg1 (triggers rotation to token1)
            val (session1, msg1) =
                Session.encryptMessage(
                    aliceStore.getFriend(aliceFriendId)!!.session,
                    MessagePlaintext.Location(1.0, 1.0, 1.0, 100),
                )
            aliceStore.updateSession(aliceFriendId, session1)

            // 3. Alice sends msg2 (triggers rotation to token2)
            val (session2, msg2) =
                Session.encryptMessage(
                    aliceStore.getFriend(aliceFriendId)!!.session,
                    MessagePlaintext.Location(2.0, 2.0, 2.0, 200),
                )
            aliceStore.updateSession(aliceFriendId, session2)

            // 4. Setup mock responses
            val token0 = bobStore.getFriend(bobFriendId)!!.session.recvToken.toHex()
            val token1 = session1.sendToken.toHex() // Alice's sendToken matches Bob's recvToken

            fakeMailbox.polls[token0] = mutableListOf(msg1)
            fakeMailbox.polls[token1] = mutableListOf(msg2)

            // 5. Poll! Should follow from token0 -> token1 and get both locations
            val locations = client.pollFriend(bobFriendId)

            assertEquals(2, locations.size)
            assertEquals(1.0, locations[0].lat)
            assertEquals(2.0, locations[1].lat)
            assertEquals(2, fakeMailbox.pollCount, "Should have followed exactly one rotation (2 polls total)")

            // Verify final state
            val bobFinal = bobStore.getFriend(bobFriendId)!!
            assertEquals(token1, bobFinal.session.recvToken.toHex())
        }

    @Test
    fun testTokenFollowLimitExceeded() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val fakeMailbox = FakeMailboxClient()
            val client = LocationClient("http://fake", bobStore, fakeMailbox)

            // Setup a friend
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val bobFriendId = bobEntry.id
            val aliceFriendId = aliceStore.listFriends().first().id

            // Simulate 5 rotations (exceeds MAX_TOKEN_FOLLOWS_PER_POLL=2)
            var currentToken = bobStore.getFriend(bobFriendId)!!.session.recvToken.toHex()

            for (i in 1..5) {
                val (sess, msg) =
                    Session.encryptMessage(
                        aliceStore.getFriend(aliceFriendId)!!.session,
                        MessagePlaintext.Location(i.toDouble(), 0.0, 0.0, 0),
                    )
                aliceStore.updateSession(aliceFriendId, sess)
                fakeMailbox.polls[currentToken] = mutableListOf(msg)
                currentToken = sess.sendToken.toHex()
            }

            // Poll! It should stop after MAX_TOKEN_FOLLOWS_PER_POLL (2) follows.
            // That means 2 successful follows + the initial poll = 3 poll calls?
            // Wait, loop is `while (follows < MAX_TOKEN_FOLLOWS_PER_POLL)`.
            // follows=0: poll token0 -> success, follows=1
            // follows=1: poll token1 -> success, follows=2
            // follows=2: LOOP ENDS.
            val locations = client.pollFriend(bobFriendId)

            assertEquals(2, locations.size, "Should have stopped after 2 follows")
            assertEquals(2, fakeMailbox.pollCount)
        }

    // Verify that pollFriend sends an immediate keepalive whenever it detects a new remote DH
    // key, regardless of the sharingEnabled flag. This covers the global-pause scenario where
    // sharingEnabled remains true (its default) but the user has paused Android sharing —
    // previously only the sharingEnabled=false branch fired the keepalive, which was dead code.
    @Test
    fun testTransitionKeepaliveWhenSharingEnabled() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val fakeMailbox = FakeMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, fakeMailbox)

            // 1. Establish session
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // sharingEnabled stays true (default) — this is the production state when sharing
            // is globally paused on Android via pausedFriendIds, not via sharingEnabled.

            // 2. Trigger a real DH ratchet: Alice sends first, Bob ratchets and replies with DH=B1.
            val (aState, aMsg) = Session.encryptMessage(aliceStore.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceStore.updateSession(aliceToBobId, aState)
            bobStore.processBatch(bobToAliceId, bobStore.getFriend(bobToAliceId)!!.session.recvToken.toHex(), listOf(aMsg))

            val (bState, bMsg) = Session.encryptMessage(bobStore.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobStore.updateSession(bobToAliceId, bState)

            val aliceRecvToken = aliceStore.getFriend(aliceToBobId)!!.session.recvToken.toHex()
            fakeMailbox.polls[aliceRecvToken] = mutableListOf(bMsg)

            // 3. Alice polls. She sees Bob's new DH key and must immediately send a keepalive
            // because she is paused (simulated by passing pausedFriendIds).
            aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))

            val aliceFinal = aliceStore.getFriend(aliceToBobId)!!
            assertFalse(aliceFinal.session.isSendTokenPending, "isSendTokenPending should be cleared after finishing transitions")
            assertTrue(fakeMailbox.posts.isNotEmpty(), "Alice should have posted at least one keepalive during token transition")
            assertTrue(aliceFinal.session.sendSeq > 0, "Alice should have advanced sendSeq")
        }

    @Test
    fun testTransitionKeepaliveWhenSharingDisabled() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val fakeMailbox = FakeMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, fakeMailbox)

            // 1. Establish session
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 2. Alice explicitly disables per-friend sharing
            aliceStore.updateFriend(aliceToBobId) { it.copy(sharingEnabled = false) }

            // 3. Trigger a real DH ratchet: Alice sends first, Bob ratchets and replies with DH=B1.
            val (aState, aMsg) = Session.encryptMessage(aliceStore.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceStore.updateSession(aliceToBobId, aState)
            bobStore.processBatch(bobToAliceId, bobStore.getFriend(bobToAliceId)!!.session.recvToken.toHex(), listOf(aMsg))

            val (bState, bMsg) = Session.encryptMessage(bobStore.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobStore.updateSession(bobToAliceId, bState)

            val aliceRecvToken = aliceStore.getFriend(aliceToBobId)!!.session.recvToken.toHex()
            fakeMailbox.polls[aliceRecvToken] = mutableListOf(bMsg)

            // 4. Alice polls. Keepalive must fire even with sharingEnabled=false.
            aliceClient.pollFriend(aliceToBobId)

            val aliceFinal = aliceStore.getFriend(aliceToBobId)!!
            assertFalse(aliceFinal.session.isSendTokenPending, "isSendTokenPending should be cleared after finishing transitions")
            assertTrue(fakeMailbox.posts.isNotEmpty(), "Alice should have posted at least one keepalive during token transition")
            assertTrue(aliceFinal.session.sendSeq > 0, "Alice should have advanced sendSeq")
        }

    // Simulate N complete DH-ratchet exchange rounds using a self-routing relay mailbox.
    // Models a long pause where one side keeps sending and the other service was dead.
    // Key property: after draining the relay, both sides can still exchange messages correctly —
    // i.e., no permanent token desync regardless of how many epochs accumulated.
    //
    // Note: we don't assert sendToken==recvToken equality at every intermediate step because the
    // Double Ratchet intentionally keeps the SENDER one epoch ahead of the peer's recvToken until
    // the peer processes the next message. The meaningful invariant is that messages sent by Alice
    // are always decryptable by Bob once the relay is fully drained.
    @Test
    fun testMultiRoundRatchetStability() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, relay)
            val bobClient = LocationClient("http://fake", bobStore, relay)

            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceToBobId = aliceStore.listFriends().first().id

            // Prime the ratchet chain: Alice sends her first keepalive (which carries DH_A1 from
            // aliceProcessInit), then both sides drain keepalives until the relay is empty.
            aliceClient.sendKeepalive(aliceToBobId)
            repeat(4) {
                bobClient.poll()
                aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))
            }

            // N additional exchange rounds — each simulates one maintenance-poll cycle during a pause.
            // After each round we verify the chain is still healthy by sending a location end-to-end.
            repeat(5) { round ->
                // Alice sends a keepalive (models Android's 30-min maintenance poll keepalive).
                aliceClient.sendKeepalive(aliceToBobId)

                // Drain: both sides exchange resulting keepalives until the relay is empty.
                repeat(4) {
                    bobClient.poll()
                    aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))
                }

                // End-to-end health check: Alice sends a location and Bob can decrypt it after
                // one more round of polls (the location may land on a token Bob fetches next).
                aliceClient.sendLocation(round.toDouble(), 0.0)
                val allUpdates = mutableListOf<net.af0.where.model.UserLocation>()
                repeat(4) {
                    allUpdates.addAll(bobClient.poll())
                    aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))
                }
                assertTrue(
                    allUpdates.any { it.lat == round.toDouble() },
                    "Round $round: Bob should receive Alice's location",
                )
            }

            // Final state: neither side should be stuck with a pending token transition.
            val alice = aliceStore.getFriend(aliceToBobId)!!
            assertFalse(alice.session.isSendTokenPending, "Alice isSendTokenPending should be clear after all rounds")
        }

    // After pollFriend triggers a DH ratchet (and our fix immediately sends the keepalive),
    // a subsequent sendLocation must arrive on the correct token so the peer can decrypt it.
    // Models the "resume after pause" path: service restarts, first poll fires keepalive,
    // then the user's first location send goes through without a full extra poll cycle.
    @Test
    fun testSendLocationAfterPollFriendDhRatchet() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, relay)
            val bobClient = LocationClient("http://fake", bobStore, relay)

            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // Prime: Alice sends her bootstrap keepalive (carries DH_A1), Bob processes it and
            // ratchets, generating DH_B2. After this initial exchange both sides have live sessions.
            aliceClient.sendKeepalive(aliceToBobId)
            repeat(4) {
                bobClient.poll()
                aliceClient.poll()
            }

            // Simulate: Bob (iOS) continues sending heartbeat keepalives during Android's pause.
            // Each send advances Bob's symmetric chain; Bob's DH key only rotates after he receives
            // something from Alice, so he keeps using DH_B2 for now.
            bobClient.sendKeepalive(bobToAliceId)

            // Android service restarts and calls poll() immediately.
            // pollFriend must detect that Bob's remoteDhPub changed (or the keepalive carries
            // a recognised new DH key), and our fix sends the ratchet-completion keepalive inside
            // the same poll() call so isSendTokenPending is cleared before sendLocation runs.
            aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))

            val aliceAfterPoll = aliceStore.getFriend(aliceToBobId)!!
            assertFalse(aliceAfterPoll.session.isSendTokenPending, "isSendTokenPending must be cleared by pollFriend's keepalive before sendLocation")

            // Alice's first location send after resuming.
            aliceClient.sendLocation(37.0, -122.0)

            // Bob drains: he processes any ratchet keepalives from Alice and then finds the location.
            val allUpdates = mutableListOf<net.af0.where.model.UserLocation>()
            repeat(4) {
                allUpdates.addAll(bobClient.poll())
                aliceClient.poll()
            }
            assertTrue(allUpdates.isNotEmpty(), "Bob should receive Alice's location")
            assertEquals(37.0, allUpdates.first().lat, "Bob should see the correct latitude")
        }
}
