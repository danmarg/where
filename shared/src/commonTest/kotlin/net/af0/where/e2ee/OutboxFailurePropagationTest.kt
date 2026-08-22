package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression tests for https://github.com/danmarg/where/issues/343: processOutbox() used to
 * swallow every exception from a failed post(), so LocationClient.sendLocation() could never
 * observe (and therefore never report) a real delivery failure - it always looked like success
 * to callers such as LocationService.sendLocationIfNeeded, whose retry/backoff logic and
 * connection-status reporting depend on the exception actually propagating.
 */
class OutboxFailurePropagationTest {
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

    private suspend fun pairedClients(): Triple<LocationClient, LocationClient, ChaosMailboxClient> {
        val realMailbox = MemoryMailboxClient()
        val chaosMailbox = ChaosMailboxClient(realMailbox)

        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, chaosMailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, chaosMailbox)

        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)

        return Triple(aliceClient, bobClient, chaosMailbox)
    }

    @Test
    fun `sendLocation propagates a real post failure instead of swallowing it`() =
        runTest {
            val (aliceClient, _, chaosMailbox) = pairedClients()

            chaosMailbox.failNextPost = true

            // Before the fix, this call would return normally (no exception) even though the
            // underlying POST failed - processOutbox()'s catch-and-break swallowed it silently.
            assertFailsWith<NetworkException> {
                aliceClient.sendLocation(37.0, -122.0, emptySet())
            }
        }

    @Test
    fun `a subsequent send after the failure still delivers the original stuck message first`() =
        runTest {
            val (aliceClient, bobClient, chaosMailbox) = pairedClients()

            chaosMailbox.failNextPost = true
            assertFailsWith<NetworkException> {
                // Location A never actually leaves the device - it's stuck in the outbox.
                aliceClient.sendLocation(37.0, -122.0, emptySet())
            }

            // Network "recovers". WAL safety means this call must first retry (and this time
            // succeed at) flushing the stuck location A, THEN generate and send a genuinely new
            // location B - not silently drop A, and not skip sending B either.
            aliceClient.sendLocation(38.0, -123.0, emptySet())

            val locations = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(2, locations.size, "both the recovered stuck message and the new one must be delivered")
            assertEquals(37.0, locations[0].lat, "the originally stuck message must be delivered first, in order")
            assertEquals(38.0, locations[1].lat)
        }

    @Test
    fun `processOutboxes still absorbs per-friend failures for its best-effort callers`() =
        runTest {
            val (aliceClient, _, chaosMailbox) = pairedClients()

            chaosMailbox.failNextPost = true
            assertFailsWith<NetworkException> {
                aliceClient.sendLocation(37.0, -122.0, emptySet())
            }

            // poll() calls processOutboxes() internally and must not blow up just because a
            // friend's outbox is (still) stuck - only the single-friend send path should surface
            // the failure to its caller.
            var threw = false
            try {
                aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
            } catch (e: Exception) {
                threw = true
            }
            assertTrue(!threw, "poll()/processOutboxes() must remain best-effort, not propagate per-friend failures")
        }
}
