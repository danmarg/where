package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class Issue210Test {
    init {
        initializeE2eeTests()
    }

    @Test
    fun reproduceStaleTokenReuseAfterCrash() = runBlocking {
        val aliceStore = E2eeStore(MemoryStorage())
        val bobStore = E2eeStore(MemoryStorage())
        val fakeMailbox = FakeMailboxClient()
        val aliceClient = LocationClient("http://fake", aliceStore, fakeMailbox)

        // 1. Establish session
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        aliceStore.processKeyExchangeInit(initPayload, "Bob")
        val aliceToBobId = aliceStore.listFriends().first().id
        val bobToAliceId = bobEntry.id

        // 2. Perform a successful exchange so Bob is polling Alice's sendToken
        val (aState, aMsg) = Session.encryptMessage(aliceStore.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
        aliceStore.updateSession(aliceToBobId, aState)
        bobStore.processBatch(bobToAliceId, bobStore.getFriend(bobToAliceId)!!.session.recvToken.toHex(), listOf(aMsg))

        // 3. Trigger a DH ratchet (Bob sends a new DH key to Alice)
        val (bState, bMsg) = Session.encryptMessage(bobStore.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
        bobStore.updateSession(bobToAliceId, bState)
        fakeMailbox.polls[aliceStore.getFriend(aliceToBobId)!!.session.recvToken.toHex()] = mutableListOf(bMsg)

        // Alice polls, which triggers ratchet and set isSendTokenPending = true.
        aliceClient.pollFriend(aliceToBobId)
        
        var aliceFriend = aliceStore.getFriend(aliceToBobId)!!
        assertTrue(aliceFriend.session.isSendTokenPending, "Flag should be pending after ratchet but before first send")
        val newToken = aliceFriend.session.sendToken.toHex()
        val oldToken = aliceFriend.session.prevSendToken.toHex()
        assertNotEquals(newToken, oldToken)

        // 4. Alice sends the first message (transition flush). It goes to oldToken.
        val postsBefore = fakeMailbox.posts.size
        aliceClient.sendLocationToFriend(aliceToBobId, 1.0, 1.0)
        
        // We expect at least one post to oldToken
        val transitionPosts = fakeMailbox.posts.drop(postsBefore)
        assertTrue(transitionPosts.any { it.second == oldToken }, "Should have posted to oldToken during transition")
        
        // Bob receives the flush and rotates
        val flushPost = transitionPosts.find { it.second == oldToken }!!
        bobStore.processBatch(bobToAliceId, oldToken, listOf(flushPost.third))
        assertEquals(newToken, bobStore.getFriend(bobToAliceId)!!.session.recvToken.toHex(), "Bob should have rotated to newToken after receiving flush")

        // 5. SIMULATE CRASH: manually revert state to isSendTokenPending = true (but outbox is already null)
        aliceFriend = aliceStore.getFriend(aliceToBobId)!!
        assertFalse(aliceFriend.session.isSendTokenPending, "Flag should have been cleared by successful send")
        
        val crashedSession = aliceFriend.session.copy(isSendTokenPending = true)
        aliceStore.updateSession(aliceToBobId, crashedSession)
        
        // 6. Alice tries to send a SECOND message after "restart".
        // With the fix, this should detect the crash and finalize the transition before sending.
        val postsBefore2 = fakeMailbox.posts.size
        aliceClient.sendLocationToFriend(aliceToBobId, 2.0, 2.0)
        
        val recoveryPosts = fakeMailbox.posts.drop(postsBefore2)
        
        // All posts in recovery should use newToken
        for (post in recoveryPosts) {
            assertEquals(newToken, post.second, "All posts after recovery must use newToken")
        }
        
        // Also verify the flag is cleared now
        aliceFriend = aliceStore.getFriend(aliceToBobId)!!
        assertFalse(aliceFriend.session.isSendTokenPending, "Flag should be cleared after recovery")
    }
}
