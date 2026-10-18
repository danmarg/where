package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stored row that no longer decodes is skipped on load; the rest of the store still loads. */
class UnreadableRowTest {
    init {
        initializeE2eeTests()
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun anUnreadableFriendOrInviteIsSkippedNotFatal() =
        runTest {
            val driver = createTestSqlDriver()
            val bob = testE2eeManager(driver)
            val alice1 = testE2eeManager(createTestSqlDriver())
            val alice2 = testE2eeManager(createTestSqlDriver())
            val (_, broken) = bob.processScannedQr(alice1.createInvite("A1"), "Bob")
            val (_, intact) = bob.processScannedQr(alice2.createInvite("A2"), "Bob")
            bob.createInvite("Bob")

            // A root key one byte short in one friend's session, and a short private key in the invite.
            val json = E2eeStore.json.encodeToString(SessionState.serializer(), broken.session)
            val rootKey = E2eeStore.json.encodeToString(RootKey.serializer(), broken.session.rootKey)
            val shortRootKey = "\"" + Base64.encode(ByteArray(31)) + "\""
            driver.execute(null, "UPDATE Friends SET sessionBlob = ? WHERE id = ?", 2) {
                bindBytes(0, json.replace(rootKey, shortRootKey).encodeToByteArray())
                bindString(1, broken.id)
            }
            driver.execute(null, "UPDATE PendingInvites SET privKeyBlob = ?", 1) { bindBytes(0, ByteArray(3)) }

            val reloaded = testE2eeManager(driver)
            assertEquals(listOf(intact.id), reloaded.listFriends().map { it.id })
            assertTrue(reloaded.listPendingInvites().isEmpty())
            val log = reloaded.diagnosticLogSnapshot()
            assertTrue(log.any { "skipped unreadable friend ${broken.id.take(8)}" in it }, "$log")
            assertTrue(log.any { "skipped unreadable invite" in it }, "$log")

            // The friend row is kept for a later release; the invite row, which can never pair, is gone.
            val again = testE2eeManager(driver)
            assertEquals(listOf(intact.id), again.listFriends().map { it.id })
            assertEquals(1, again.diagnosticLogSnapshot().count { "skipped unreadable invite" in it })
        }
}
