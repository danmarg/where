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
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.discoveryToken().toHex())

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
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.discoveryToken().toHex())
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
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.discoveryToken().toHex())

            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 2. Alice disables sharing
            aliceStore.updateFriend(aliceToBobId) { it.copy(sharingEnabled = false) }

            // 3. Bob sends a message to Alice (triggers DH ratchet on Alice's side)
            // To trigger a REAL DH ratchet, Alice must first send a message to Bob,
            // then Bob sends a message back with his NEW DH key.

            // Alice -> Bob (DH=A0)
            val (aState, aMsg) = Session.encryptMessage(aliceStore.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceStore.updateSession(aliceToBobId, aState)
            bobStore.processBatch(bobToAliceId, bobStore.getFriend(bobToAliceId)!!.session.recvToken.toHex(), listOf(aMsg))

            // Bob -> Alice (DH=B1). This will trigger isNewDhEpoch and commitRatchetSend at Alice.
            val (bState, bMsg) = Session.encryptMessage(bobStore.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobStore.updateSession(bobToAliceId, bState)

            val aliceRecvToken = aliceStore.getFriend(aliceToBobId)!!.session.recvToken.toHex()
            fakeMailbox.polls[aliceRecvToken] = mutableListOf(bMsg)

            // 4. Alice polls. She should see the new DH, ratchet, and trigger an automated keepalive.
            // The keepalive itself triggers finalizeTokenTransition.
            // Alice's pollFriend detects Bob's new DH key in the batch,
            // which triggers Alice to rotate her own keys and set isSendTokenPending = true.
            // It then immediately calls sendKeepalive() which should clear the flag.
            aliceClient.pollFriend(aliceToBobId)

            // Assert: Alice should have posted at least one keepalive to advance Bob's recvToken
            val aliceFinal = aliceStore.getFriend(aliceToBobId)!!
            assertFalse(aliceFinal.session.isSendTokenPending, "isSendTokenPending should be cleared after finishing transitions")

            // Check that something was posted to Bob
            println("[DEBUG] FakeMailbox posts: ${fakeMailbox.posts.size}")
            assertTrue(fakeMailbox.posts.isNotEmpty(), "Alice should have posted at least one message during transition")
            // We expect Alice to rotate because bob advanced his epoch.
            assertTrue(aliceFinal.session.sendSeq > 0, "Alice should have sent a keepalive (sendSeq > 0)")
        }
}
