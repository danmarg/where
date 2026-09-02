package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Regression coverage for pollFriend()'s handling of a failed service.poll() call: it must not
 * mark the friend as caught-up on a genuine failure, and it must let a real (non-wall-clock)
 * CancellationException propagate instead of swallowing it and continuing as if nothing happened.
 */
class PollFailureHandlingTest {
    /** Wraps a real MailboxClient but can be told to throw once (or always) from poll(). */
    private class FaultInjectingMailboxClient(private val delegate: MailboxClient) : MailboxClient {
        var pollExceptionOnce: Throwable? = null
        var pollExceptionAlways: Throwable? = null

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) = delegate.post(baseUrl, token, payload)

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            pollExceptionAlways?.let { throw it }
            pollExceptionOnce?.let {
                pollExceptionOnce = null
                throw it
            }
            return delegate.poll(baseUrl, token)
        }

        override suspend fun ackId(
            baseUrl: String,
            token: String,
            msgId: String,
        ) = delegate.ackId(baseUrl, token, msgId)

        override suspend fun ackIds(
            baseUrl: String,
            token: String,
            msgIds: List<String>,
        ) = delegate.ackIds(baseUrl, token, msgIds)
    }

    init {
        initializeE2eeTests()
    }

    private suspend fun setupFriendship(mailbox: MailboxClient): Triple<E2eeManager, LocationClient, FriendEntry> {
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

        val aliceFriend = aliceManager.getFriend(bobEntry.id)!!
        for (i in 1..5) {
            val (msg, _) = bobManager.encryptAndAdvance(bobEntry.id, MessagePlaintext.Location(37.0 + i, -122.0, 0.0, 1000L + i))
            mailbox.post("http://localhost", aliceFriend.session.recvToken.toHex(), msg)
        }

        return Triple(aliceManager, aliceClient, aliceFriend)
    }

    @Test
    fun testCaughtUpNotSetOnPollFailure() =
        runTest {
            val realMailbox = MemoryMailboxClient()
            val faultyMailbox = FaultInjectingMailboxClient(realMailbox)
            val (aliceManager, aliceClient, aliceFriend) = setupFriendship(faultyMailbox)

            assertFalse(aliceManager.getFriend(aliceFriend.id)!!.isCaughtUp, "Should not be caught up initially")

            // First poll fails outright - should NOT be recorded as caught up.
            faultyMailbox.pollExceptionOnce = NetworkException("Simulated network failure on POLL")
            aliceClient.poll()
            assertFalse(
                aliceManager.getFriend(aliceFriend.id)!!.isCaughtUp,
                "A failed poll must not be recorded as caught up",
            )

            // A subsequent successful poll should drain the mailbox and mark caught-up normally.
            val updates = aliceClient.poll()
            assertEquals(5, updates.size)
            assertTrue(
                aliceManager.getFriend(aliceFriend.id)!!.isCaughtUp,
                "Should be caught up once the retry actually succeeds",
            )
        }

    @Test
    fun testGenuineCancellationPropagatesFromPollFriend() =
        runTest {
            val realMailbox = MemoryMailboxClient()
            val faultyMailbox = FaultInjectingMailboxClient(realMailbox)
            val (_, aliceClient, aliceFriend) = setupFriendship(faultyMailbox)

            // A real (non-wall-clock) CancellationException represents genuine structured-
            // concurrency cancellation, e.g. the app shutting down mid-poll - it must propagate
            // out of pollFriend rather than being swallowed and treated as an ordinary failure.
            faultyMailbox.pollExceptionAlways = CancellationException("simulated outer cancellation")

            assertFailsWith<CancellationException> {
                aliceClient.pollFriend(aliceFriend.id)
            }
        }

    @Test
    fun testGenuineCancellationPropagatesFromPoll() =
        runTest {
            // Same scenario as testGenuineCancellationPropagatesFromPollFriend, but through the
            // real production call path (poll()'s per-friend async block) rather than calling
            // pollFriend() directly - poll() has its own catch (e: Exception) around pollFriend()
            // that would silently re-swallow a rethrown CancellationException if it weren't also
            // narrowed to let CancellationException through.
            val realMailbox = MemoryMailboxClient()
            val faultyMailbox = FaultInjectingMailboxClient(realMailbox)
            val (_, aliceClient, _) = setupFriendship(faultyMailbox)

            faultyMailbox.pollExceptionAlways = CancellationException("simulated outer cancellation")

            assertFailsWith<CancellationException> {
                aliceClient.poll()
            }
        }

    @Test
    fun testWallClockTimeoutHandledAsOrdinaryFailureNotPropagated() =
        runTest {
            // WallClockTimeoutCancellationException IS a CancellationException, but it's a
            // self-inflicted, expected "this one request timed out" signal (E2eeMailboxClient
            // wraps every mailbox call in withWallClockTimeout) - not a genuine structured-
            // concurrency shutdown. Unlike the real-cancellation cases above, it must NOT
            // propagate out of pollFriend(): doing so would skip updateLastPollTs()/
            // processOutbox()/the automated keepalive backstop check for every routine timeout,
            // which is the opposite of what's wanted since timeouts are the common case this
            // whole investigation started from.
            val realMailbox = MemoryMailboxClient()
            val faultyMailbox = FaultInjectingMailboxClient(realMailbox)
            val (aliceManager, aliceClient, aliceFriend) = setupFriendship(faultyMailbox)

            assertEquals(0L, aliceManager.getFriend(aliceFriend.id)!!.lastPollTs, "Sanity check: no poll recorded yet")

            faultyMailbox.pollExceptionOnce = WallClockTimeoutCancellationException()

            // Must return normally (not throw) - a timeout is handled like any other failure.
            val updates = aliceClient.pollFriend(aliceFriend.id)
            assertTrue(updates.isEmpty(), "A timed-out poll should yield no updates")

            val friendAfter = aliceManager.getFriend(aliceFriend.id)!!
            assertFalse(friendAfter.isCaughtUp, "A timed-out poll must not be recorded as caught up")
            assertTrue(
                friendAfter.lastPollTs > 0L,
                "Per-friend housekeeping (updateLastPollTs/processOutbox/keepalive backstop) must " +
                    "still run after an ordinary wall-clock timeout, unlike a genuine cancellation",
            )
        }
}
