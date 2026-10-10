package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HygieneTest {
    init {
        initializeE2eeTests()
    }

    @AfterTest
    fun restoreClock() {
        TimeSource.setProvider(DefaultTimeProvider)
    }

    private class OffsetClock(private val offsetSeconds: Long) : TimeProvider {
        override fun currentTimeMillis() = DefaultTimeProvider.currentTimeMillis() + offsetSeconds * 1000

        override fun currentTimeSeconds() = DefaultTimeProvider.currentTimeSeconds() + offsetSeconds

        override fun formatLocalTime(seconds: Long) = DefaultTimeProvider.formatLocalTime(seconds)
    }

    @Test
    fun redactSecretsStripsInboxPathsAndRoutingTokens() {
        val token = "0123456789abcdef0123456789abcdef"
        val redacted =
            redactSecrets(
                "poll failed on $token: Request timeout [url=https://where.af0.net/inbox/$token/msg-1?x=1]",
            )
        assertFalse(redacted.contains(token), redacted)
        assertTrue(redacted.contains("012345…"), "short prefix kept for correlation: $redacted")
        assertTrue(redacted.contains("/inbox/<redacted>"), redacted)
    }

    @Test
    fun redactSecretsLeavesFriendIdsAndPlainTextAlone() {
        val friendId = "a".repeat(64) + ":" + "b".repeat(64)
        assertEquals("pollFriend($friendId) ok", redactSecrets("pollFriend($friendId) ok"))
    }

    @Test
    fun diagnosticEventsAreRedactedBeforeStorage() {
        val manager = testE2eeManager(createTestSqlDriver())
        val token = "fedcba9876543210fedcba9876543210"
        manager.addDiagnosticEvent("force-ACK after drops on $token")
        assertFalse(manager.diagnosticLogSnapshot().any { it.contains(token) }, manager.diagnosticLogSnapshot().toString())
    }

    @Test
    fun scannerSideFriendNameIsSanitized() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice").copy(suggestedName = "Mal‮lory" + "x".repeat(100))
            val (_, entry) = bob.processScannedQr(qr, "Bob")
            assertFalse(entry.name.contains('‮'), "bidi override must be stripped: ${entry.name}")
            assertTrue(entry.name.length <= 32, "name must be length-limited: ${entry.name.length}")
        }

    @Test
    fun emojiNamesSurviveSanitizationButBidiOverridesDoNot() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice").copy(suggestedName = "\uD83C\uDF55")
            val (_, entry) = bob.processScannedQr(qr, "Bob")
            assertEquals("\uD83C\uDF55", entry.name)

            val onlyControls = alice.createInvite("Alice").copy(suggestedName = "\u202E\u200B")
            val (_, fallback) = testE2eeManager(createTestSqlDriver()).processScannedQr(onlyControls, "Bob")
            assertEquals("Friend", fallback.name)
        }

    @Test
    fun updateLastLocationRejectsFarFutureAndStaleTimestamps() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val (_, entry) = bob.processScannedQr(alice.createInvite("Alice"), "Bob")
            val now = currentTimeSeconds()
            bob.updateLastLocation(entry.id, 1.0, 1.0, now)
            bob.updateLastLocation(entry.id, 9.0, 9.0, now + 24 * 3600) // far future: ignored
            bob.updateLastLocation(entry.id, 8.0, 8.0, now - 60) // older: ignored
            val stored = bob.listFriends().single()
            assertEquals(1.0, stored.lastLng)
            assertEquals(now, stored.lastTs)
        }

    @Test
    fun farFuturePeerTimestampDoesNotFreezeLocation() =
        runTest {
            val mailbox = MemoryMailboxClient()
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val bobManager = testE2eeManager(createTestSqlDriver())
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
            val alice = LocationClient("http://localhost", aliceManager, mailbox).apply { enableAutomatedKeepalives = false }
            val bob = LocationClient("http://localhost", bobManager, mailbox).apply { enableAutomatedKeepalives = false }
            alice.postKeyExchangeInit(bobEntry.id, qr, initPayload)
            aliceManager.processKeyExchangeInit(alice.pollPendingInvites().single().payload, "Bob", qr.ekPub)

            // Alice's clock is a day fast for one send.
            TimeSource.setProvider(OffsetClock(24 * 3600))
            alice.sendLocation(1.0, 1.0)
            TimeSource.setProvider(DefaultTimeProvider)
            val seen = bob.poll()
            assertTrue(
                seen.all { it.timestamp <= currentTimeSeconds() + E2eeManager.MAX_PEER_CLOCK_SKEW_SECONDS },
                "callers must only ever see clamped timestamps: $seen",
            )
            val stored = bobManager.listFriends().single()
            assertEquals(1.0, stored.lastLng)
            assertTrue(
                stored.lastTs!! <= currentTimeSeconds() + E2eeManager.MAX_PEER_CLOCK_SKEW_SECONDS,
                "stored ts must be clamped",
            )

            // Her clock is fixed: the next, honest update must still be applied.
            alice.sendLocation(2.0, 2.0)
            bob.poll()
            assertEquals(2.0, bobManager.listFriends().single().lastLng)
        }
}
