package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression tests for https://github.com/danmarg/where/issues/346: LocationClient must track a
 * global, cross-cycle failure counter so LocationService can back off its poll-loop wake interval
 * during a genuine server outage - but must NOT count a 429 (per-friend throttle, not a server
 * health signal) or a ConnectException-shaped failure (device has no path to the server at all -
 * already handled faster by the platform's network-available callback).
 */
class CrossCycleBackoffTest {
    init {
        initializeE2eeTests()
    }

    /**
     * Delegates to a real mailbox by default; [nextPostException] overrides the next post() only,
     * [failAllPolls] makes every poll() throw (until cleared).
     */
    private class SelectiveFailureMailboxClient(private val delegate: MailboxClient) : MailboxClient {
        var nextPostException: (() -> Exception)? = null
        var failAllPolls: (() -> Exception)? = null

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            nextPostException?.let { throw it() } ?: delegate.post(baseUrl, token, payload)
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> = failAllPolls?.let { throw it() } ?: delegate.poll(baseUrl, token)
    }

    private suspend fun pairedClient(): Pair<LocationClient, SelectiveFailureMailboxClient> {
        val (client, mailbox, _) = pairedClientWithFriends(friendCount = 1)
        return client to mailbox
    }

    /** Same as [pairedClient], but pairs Alice with [friendCount] separate friends. */
    private suspend fun pairedClientWithFriends(
        friendCount: Int,
    ): Triple<LocationClient, SelectiveFailureMailboxClient, List<LocationClient>> {
        val realMailbox = MemoryMailboxClient()
        val mailbox = SelectiveFailureMailboxClient(realMailbox)
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)

        val friendClients =
            (1..friendCount).map { i ->
                val qr = aliceManager.createInvite("Alice")
                val friendDriver = createTestSqlDriver()
                val friendManager = testE2eeManager(friendDriver)
                val friendClient = LocationClient("http://localhost", friendManager, realMailbox)

                val (initPayload, friendEntry) = friendManager.processScannedQr(qr, "Friend$i")
                friendClient.postKeyExchangeInit(friendEntry.id, qr, initPayload)

                val pending = aliceClient.pollPendingInvites()
                val match = pending.first { it.payload.ekPub.contentEquals(initPayload.ekPub) }
                aliceManager.processKeyExchangeInit(match.payload, "Friend$i", match.inviteEkPub)

                friendClient
            }

        return Triple(aliceClient, mailbox, friendClients)
    }

    @Test
    fun `a server error counts toward backoff`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { ServerException(500, "boom") }
            assertEquals(1, client.crossCycleBackoffMultiplier)
            assertFailsWith<ServerException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(2, client.crossCycleBackoffMultiplier)
        }

    @Test
    fun `a 429 does not count toward backoff - that's the unresponsive-friend throttle's problem`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { ServerException(429, "queue full") }
            assertFailsWith<ServerException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(1, client.crossCycleBackoffMultiplier)
        }

    @Test
    fun `a ConnectException does not count toward backoff - the platform's network callback handles that`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { ConnectException("no route") }
            assertFailsWith<ConnectException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(1, client.crossCycleBackoffMultiplier)
        }

    @Test
    fun `a TimeoutException only counts when the platform reports the device is online`() =
        runTest {
            val (client, mailbox) = pairedClient()
            client.isNetworkAvailable = { false }
            mailbox.nextPostException = { TimeoutException("timed out") }
            assertFailsWith<TimeoutException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(1, client.crossCycleBackoffMultiplier, "offline device must not count toward backoff")

            client.isNetworkAvailable = { true }
            mailbox.nextPostException = { TimeoutException("timed out") }
            assertFailsWith<TimeoutException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(2, client.crossCycleBackoffMultiplier)
        }

    @Test
    fun `a success resets the backoff counter`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { ServerException(500, "boom") }
            assertFailsWith<ServerException> { client.sendLocation(1.0, 2.0, emptySet()) }
            mailbox.nextPostException = { ServerException(500, "boom") }
            assertFailsWith<ServerException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(4, client.crossCycleBackoffMultiplier)

            mailbox.nextPostException = null
            client.sendLocation(1.0, 2.0, emptySet())
            assertEquals(1, client.crossCycleBackoffMultiplier)
        }

    @Test
    fun `poll() aggregates N failing friends into a single backoff increment, not N`() =
        runTest {
            val (client, mailbox, _) = pairedClientWithFriends(friendCount = 3)
            mailbox.failAllPolls = { ServerException(500, "boom") }

            assertEquals(1, client.crossCycleBackoffMultiplier)
            client.poll(isForeground = true, pausedFriendIds = emptySet())
            // Without aggregation, 3 independently-recording friends would jump straight to 8x.
            assertEquals(2, client.crossCycleBackoffMultiplier, "one poll() cycle must count as one failure, not one per friend")
        }

    @Test
    fun `a send-side wall-clock timeout counts toward backoff and surfaces as TimeoutException, not CancellationException`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { WallClockTimeoutCancellationException() }
            client.isNetworkAvailable = { true }

            // Must NOT surface as a CancellationException - a platform caller with a reasonable
            // `catch (e: CancellationException) { throw e }` (to avoid swallowing genuine
            // structured-concurrency shutdown) would otherwise skip its own failure handling.
            assertFailsWith<TimeoutException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(2, client.crossCycleBackoffMultiplier, "a send-side hang must count toward backoff like any other timeout")
        }

    @Test
    fun `sendLocation with recordCrossCycleOutcome=false lets the caller aggregate its own retry loop`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { ServerException(500, "boom") }

            // Three "manual retry loop" attempts, none self-recording.
            repeat(3) {
                assertFailsWith<ServerException> {
                    client.sendLocation(1.0, 2.0, emptySet(), recordCrossCycleOutcome = false)
                }
            }
            assertEquals(1, client.crossCycleBackoffMultiplier, "no attempt should have self-recorded")

            client.recordCrossCycleAttempt(success = false, error = ServerException(500, "boom"))
            assertEquals(2, client.crossCycleBackoffMultiplier, "the caller's single aggregated record must count exactly once")
        }

    @Test
    fun `resetCrossCycleBackoff clears the counter immediately without waiting for a success`() =
        runTest {
            val (client, mailbox) = pairedClient()
            mailbox.nextPostException = { ServerException(500, "boom") }
            assertFailsWith<ServerException> { client.sendLocation(1.0, 2.0, emptySet()) }
            assertEquals(2, client.crossCycleBackoffMultiplier)

            client.resetCrossCycleBackoff()
            assertEquals(1, client.crossCycleBackoffMultiplier)
        }
}
