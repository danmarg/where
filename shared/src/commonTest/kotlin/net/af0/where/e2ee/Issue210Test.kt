package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class Issue210Test {
    init {
        initializeE2eeTests()
    }

    @Test
    fun reproduceStaleTokenReuseAfterCrash() =
        runBlocking {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val fakeMailbox = FakeMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceManager, fakeMailbox)

            // 1. Establish session
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 2. Perform a successful exchange so Bob is polling Alice's sendToken
            val (aState, aMsg) = Session.encryptMessage(aliceManager.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceManager.updateSession(aliceToBobId, aState)
            bobManager.processBatch(bobToAliceId, bobManager.getFriend(bobToAliceId)!!.session.recvToken.toHex(), listOf(aMsg))

            // 3. Trigger a DH ratchet (Bob sends a new DH key to Alice)
            val (bState, bMsg) = Session.encryptMessage(bobManager.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobManager.updateSession(bobToAliceId, bState)
            fakeMailbox.polls[aliceManager.getFriend(aliceToBobId)!!.session.recvToken.toHex()] = mutableListOf(bMsg)

            // Alice polls, which triggers ratchet and set isSendTokenPending = true.
            aliceClient.pollFriend(aliceToBobId)

            var aliceFriend = aliceManager.getFriend(aliceToBobId)!!
            assertTrue(aliceFriend.session.isSendTokenPending, "Flag should be pending after ratchet but before first send")
            val oldToken = aliceFriend.session.prevSendToken.toHex()

            // 4. Alice sends the first message (transition flush). It goes to oldToken.
            val postsBefore = fakeMailbox.posts.size
            aliceClient.sendLocationToFriend(aliceToBobId, 1.0, 1.0)

            // We expect at least one post to oldToken
            val transitionPosts = fakeMailbox.posts.drop(postsBefore)
            assertTrue(transitionPosts.any { it.second == oldToken }, "Should have posted to oldToken during transition")

            // Bob receives everything to clear the mailbox
            transitionPosts.forEach { post ->
                bobManager.processBatch(bobToAliceId, post.second, listOf(post.third))
            }

            // 5. SIMULATE CRASH: manually revert state to isSendTokenPending = true (but outbox is already null)
            aliceFriend = aliceManager.getFriend(aliceToBobId)!!
            assertFalse(aliceFriend.session.isSendTokenPending, "Flag should have been cleared by successful send")

            val crashedSession = aliceFriend.session.copy(isSendTokenPending = true)
            aliceManager.updateSession(aliceToBobId, crashedSession)

            // 6. Alice tries to send a SECOND message after "restart".
            // With the fix, this should detect the crash and finalize the transition before sending.
            val postsBefore2 = fakeMailbox.posts.size
            aliceClient.sendLocationToFriend(aliceToBobId, 2.0, 2.0)

            // Verify the flag is cleared now
            aliceFriend = aliceManager.getFriend(aliceToBobId)!!
            assertFalse(aliceFriend.session.isSendTokenPending, "Flag should be cleared after recovery")

            // Bob should be able to receive the new location
            val recoveryPosts = fakeMailbox.posts.drop(postsBefore2)
            recoveryPosts.forEach { post ->
                bobManager.processBatch(bobToAliceId, post.second, listOf(post.third))
            }
            val bobFriend = bobManager.getFriend(bobToAliceId)!!
            assertEquals(2.0, bobFriend.lastLat)
        }
}
