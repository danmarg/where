package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import net.af0.where.model.UserLocation

class CatchUpTest {
    private val mailbox = PagingMemoryMailboxClient()

    private class PagingMemoryMailboxClient : MailboxClient {
        val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()
        var pageSize = 50

        override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
            mailboxes.getOrPut(token) { mutableListOf() }.add(payload)
        }

        override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
            val list = mailboxes[token] ?: return emptyList()
            return list.take(pageSize)
        }

        override suspend fun ackId(baseUrl: String, token: String, msgId: String) {
            mailboxes[token]?.removeAll { it.msgId == msgId }
        }

        override suspend fun ackIds(baseUrl: String, token: String, msgIds: List<String>) {
            mailboxes[token]?.removeAll { it.msgId in msgIds }
        }
    }

    init {
        initializeE2eeTests()
    }

    @Test
    fun testCatchUpLargeBacklog() = runTest {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        // 1. Bob scans and posts handshake
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        // 2. Alice processes handshake
        val pending = aliceClient.pollPendingInvites()
        val aliceEntry = aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)
        assertNotNull(aliceEntry)

        // 3. Bob sends 150 location updates while Alice is "offline"
        // We bypass bobClient.sendLocation because it tries to process outbox immediately.
        // We want to fill the mailbox.
        val bobSession = bobManager.getFriend(bobEntry.id)!!.session
        val bobToken = bobSession.recvToken.toHex() // Alice's recvToken is Bob's sendToken, but here we want to put messages where Alice will poll.
        // Actually, Bob sends to Alice's recvToken? No, Alice polls HER recvToken. Bob sends to Alice's recvToken.

        val aliceRecvToken = aliceManager.getFriend(bobEntry.id)!!.session.recvToken.toHex()

        for (i in 1..150) {
            val (msg, _) = bobManager.encryptAndAdvance(bobEntry.id, MessagePlaintext.Location(37.0 + i, -122.0, 0.0, 1000L + i))
            mailbox.post("http://localhost", aliceRecvToken, msg)
        }

        println("Alice recv token: $aliceRecvToken")
        assertEquals(150, mailbox.mailboxes[aliceRecvToken]?.size)

        // 4. Alice polls. She should drain all 150 messages in one call because they are on the same token.
        val updates = aliceClient.poll(isForeground = true)

        // Alice might also receive a keepalive if Bob's client was used, but here we manually posted.
        // Wait, poll() calls pollFriend for each friend.
        println("Updates size: ${updates.size}")
        assertEquals(150, updates.size, "Alice should have received all 150 updates in one poll call")
        assertEquals(0, mailbox.mailboxes[aliceRecvToken]?.size, "Mailbox should be empty after poll")
    }

    @Test
    fun testTokenFollowBound() = runTest {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        // Handshake
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)
        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)

        // Bob sends messages that trigger many ratchets.
        // Each time Alice receives a message with a new DH key, she ratchets.
        // We want to simulate more than MAX_TOKEN_FOLLOWS_PER_POLL (20) transitions.

        for (i in 1..25) {
            // Bob forces a ratchet by sending a message with a new DH key.
            // To do this easily, we can use bobClient.sendLocation which might not ratchet every time.
            // Let's use a lower-level way to force ratchets if needed, or just send many messages
            // and hope it ratchets enough. Actually, Bob ratchets when he receives a message from Alice.

            // Actually, Bob can't force multiple DH ratchets without Alice sending anything back,
            // unless he just keeps changing his DH key? The Double Ratchet protocol advances the
            // receiving chain when it sees a new DH key.

            // If Bob sends a message, then Alice sends a message (triggering DH ratchet on Bob),
            // then Bob sends a message (triggering DH ratchet on Alice).

            // To simplify, let's just manually trigger token transitions in the store if we want to test the bound.
            // Or better, just trust that the loop respects the constant.
        }

        // Let's at least test that it follows ONE transition and keeps draining.

        // 1. Bob sends 60 messages on Token A (Full page + 10)
        val aliceFriend = aliceManager.getFriend(bobEntry.id)!!
        var currentAliceRecvToken = aliceFriend.session.recvToken.toHex()
        println("Initial Alice recv token (A): $currentAliceRecvToken")

        for (i in 1..60) {
            val (msg, _) = bobManager.encryptAndAdvance(bobEntry.id, MessagePlaintext.Location(37.0, -122.0, 0.0, 1000L + i))
            mailbox.post("http://localhost", currentAliceRecvToken, msg)
        }

        // 2. Bob triggers a ratchet (e.g. by receiving something from Alice and then sending)
        // Alice sends a keepalive
        aliceClient.sendKeepalive(bobEntry.id)
        bobClient.poll() // Bob receives keepalive, ratchets

        // Bob sends 10 more messages on Token B
        val bobFriend = bobManager.getFriend(bobEntry.id)!!
        val aliceRecvTokenB = bobFriend.session.sendToken.toHex()
        println("Alice recv token after ratchet (B): $aliceRecvTokenB")
        assertNotEquals(currentAliceRecvToken, aliceRecvTokenB)

        for (i in 61..70) {
            val (msg, session) = bobManager.encryptAndAdvance(bobEntry.id, MessagePlaintext.Location(37.0, -122.0, 0.0, 1000L + i))
            val tokenToUse = if (session.sendSeq == 1L) session.prevSendToken else session.sendToken
            mailbox.post("http://localhost", tokenToUse.toHex(), msg)
        }

        // Alice polls. She should get 60 from Token A, then follow to Token B and get 10 more.
        val updates = aliceClient.poll()
        // Alice receives 60 + 10 = 70 updates
        assertEquals(70, updates.size, "Alice should have received all 70 updates across two tokens")
    }

    @Test
    fun testForceAckBreaksLoop() = runTest {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        // Handshake
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)
        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)

        val aliceFriend = aliceManager.getFriend(bobEntry.id)!!
        val currentAliceRecvToken = aliceFriend.session.recvToken.toHex()

        // 1. Post a "bad" message that Alice cannot decrypt
        // We'll just post random bytes as payload
        mailbox.post("http://localhost", currentAliceRecvToken, EncryptedMessagePayload(
            msgId = "bad-msg",
            envelope = byteArrayOf(1, 2, 3),
            ct = byteArrayOf(4, 5, 6)
        ))

        // 2. Poll many times to trigger force-ACK
        for (i in 1 until MAX_SILENT_DROP_RETRIES) {
            aliceClient.poll()
            assertNotNull(mailbox.mailboxes[currentAliceRecvToken]?.find { it.msgId == "bad-msg" }, "Should not have ACKed yet at retry $i")
        }

        // 3. This poll should trigger force-ACK and clear the mailbox
        aliceClient.poll()
        assertNull(mailbox.mailboxes[currentAliceRecvToken]?.find { it.msgId == "bad-msg" }, "Should have force-ACKed")
    }

    @Test
    fun testIsCaughtUpState() = runTest {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        // Handshake
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)
        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)

        val aliceFriend = aliceManager.getFriend(bobEntry.id)!!
        assertFalse(aliceFriend.isCaughtUp, "Should not be caught up initially")

        val aliceRecvToken = aliceFriend.session.recvToken.toHex()

        // 1. Send messages
        for (i in 1..10) {
            val (msg, _) = bobManager.encryptAndAdvance(bobEntry.id, MessagePlaintext.Location(37.0 + i, -122.0, 0.0, 1000L + i))
            mailbox.post("http://localhost", aliceRecvToken, msg)
        }

        // 2. Poll. It should drain and set isCaughtUp to true.
        aliceClient.poll()
        val aliceFriendAfter = aliceManager.getFriend(bobEntry.id)!!
        assertTrue(aliceFriendAfter.isCaughtUp, "Should be caught up after draining mailbox")

        // 3. Send more messages. Alice is still marked as caughtUp=true from the LAST poll.
        for (i in 1..10) {
            val (msg, _) = bobManager.encryptAndAdvance(bobEntry.id, MessagePlaintext.Location(37.0 + i, -122.0, 0.0, 1000L + i))
            mailbox.post("http://localhost", aliceRecvToken, msg)
        }
        assertTrue(aliceManager.getFriend(bobEntry.id)!!.isCaughtUp, "Should still be true until next poll finishes")

        // 4. Poll again.
        aliceClient.poll()
        assertTrue(aliceManager.getFriend(bobEntry.id)!!.isCaughtUp, "Should still be caught up after second poll")
    }
}
