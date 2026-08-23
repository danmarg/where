package net.af0.where.e2ee

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression/behavior tests for throttling sends to a friend whose client hasn't sent us
 * anything (not even an automated keepalive) in a while - see sendLocation()'s activeFriends
 * filter. Uses a virtual clock (TestScope.currentTime) so "5 minutes of silence" doesn't require
 * a real 5-minute test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnresponsiveFriendThrottleTest {
    private class MemoryMailboxClient : MailboxClient {
        val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            mailboxes.getOrPut(token) { mutableListOf() }.add(payload)
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> = mailboxes[token] ?: emptyList()

        override suspend fun ackId(
            baseUrl: String,
            token: String,
            msgId: String,
        ) {
            mailboxes[token]?.removeAll { it.msgId == msgId }
        }

        override suspend fun ackIds(
            baseUrl: String,
            token: String,
            msgIds: List<String>,
        ) {
            mailboxes[token]?.removeAll { it.msgId in msgIds }
        }
    }

    init {
        initializeE2eeTests()
    }

    private fun TestScope.setVirtualTime(baseTimeMs: Long) {
        TimeSource.setProvider(
            object : TimeProvider {
                override fun currentTimeMillis() = baseTimeMs + testScheduler.currentTime

                override fun currentTimeSeconds() = (baseTimeMs + testScheduler.currentTime) / 1000

                override fun formatLocalTime(seconds: Long): String = platformFormatLocalTime(seconds)
            },
        )
    }

    private suspend fun pairedClients(mailbox: MailboxClient): Pair<LocationClient, LocationClient> {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)

        return aliceClient to bobClient
    }

    private suspend fun pairNewFriend(
        aliceManager: E2eeManager,
        aliceClient: LocationClient,
        mailbox: MailboxClient,
        name: String,
    ): LocationClient {
        val friendDriver = createTestSqlDriver()
        val friendManager = testE2eeManager(friendDriver)
        val friendClient = LocationClient("http://localhost", friendManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val (initPayload, friendEntry) = friendManager.processScannedQr(qr, name)
        friendClient.postKeyExchangeInit(friendEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending.last().payload, name, pending.last().inviteEkPub)

        return friendClient
    }

    @Test
    fun `a failed send to an unresponsive friend retries at the next normal cycle, not the full throttle interval`() =
        runTest {
            val realMailbox = MemoryMailboxClient()
            val mailbox = ChaosMailboxClient(realMailbox)
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(400_000L) // t=400s: unresponsive, throttled send is due.
            mailbox.failNextPost = true
            // Alice only has one friend here, so this send fails for ALL active friends -
            // sendLocation() rethrows in that case (see fe74ce0). Generation + the failed attempt
            // still happened, leaving the message stuck in the outbox, which is what we're testing.
            assertFailsWith<NetworkException> {
                aliceClient.sendLocation(1.0, 1.0, emptySet())
            }
            assertTrue(
                bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).isEmpty(),
                "the failed attempt must not have been delivered",
            )

            // Network "recovers". Only a few seconds later - nowhere near a full 5-minute throttle
            // window - matching the normal (untouched) WAL-outbox retry cadence for any other friend.
            advanceTimeBy(5_000L)
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, delivered.size, "the originally stuck message must retry promptly, not wait out the throttle window")
            assertEquals(1.0, delivered[0].lat, "must deliver the original stuck message, not a new one")
        }

    @Test
    fun `throttling one unresponsive friend does not affect sends to a responsive friend`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)

            val aliceDriver = createTestSqlDriver()
            val aliceManager = testE2eeManager(aliceDriver)
            val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
            val bobClient = pairNewFriend(aliceManager, aliceClient, mailbox, "Bob")
            val charlieClient = pairNewFriend(aliceManager, aliceClient, mailbox, "Charlie")

            // Both friends heard from at t=0.
            bobClient.sendLocation(0.0, 0.0, emptySet())
            charlieClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(400_000L) // Bob goes unresponsive (throttled); Charlie keeps talking.
            charlieClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            aliceClient.sendLocation(1.0, 1.0, emptySet()) // fans out to both in one call.
            assertTrue(
                bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).isEmpty(),
                "Bob is unresponsive and not yet due - must be throttled",
            )
            val toCharlie = charlieClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, toCharlie.size, "Charlie is responsive - one unresponsive friend must not affect sends to another")
        }

    @Test
    fun `sends continue normally while the friend has been heard from recently`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            // Establishes Alice's lastRecvTs for Bob at t=0.
            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(250_000L) // 250s: under the 300s threshold.
            aliceClient.sendLocation(1.0, 1.0, emptySet())

            val received = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, received.size, "still within the responsiveness window, must send normally")
        }

    @Test
    fun `send to an unresponsive friend is throttled, then allowed once the interval elapses`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(250_000L) // t=250s: still responsive, this send also sets lastSentTs=250.
            aliceClient.sendLocation(1.0, 1.0, emptySet())
            assertEquals(1, bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).size)

            advanceTimeBy(60_000L) // t=310s: now unresponsive (310s of silence), but only 60s since our last send.
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            assertTrue(
                bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).isEmpty(),
                "unresponsive and not yet due for a throttled retry - must not send",
            )

            advanceTimeBy(250_000L) // t=560s: 310s since our last send - now due.
            aliceClient.sendLocation(3.0, 3.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, delivered.size, "throttle interval elapsed - must send the throttled update")
            assertEquals(3.0, delivered[0].lat)
        }

    @Test
    fun `responsiveness recovers immediately once the friend sends anything, no extra cooldown`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(400_000L) // t=400s: unresponsive.
            aliceClient.sendLocation(1.0, 1.0, emptySet())
            bobClient.poll(isForeground = true, pausedFriendIds = emptySet()) // drain the throttled send

            // Bob sends a keepalive (or anything) - Alice's lastRecvTs updates immediately on poll.
            bobClient.sendLocation(9.0, 9.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(1_000L) // barely any time later - well under the throttle interval.
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, delivered.size, "must resume full cadence right away once the friend is heard from again")
        }
}
