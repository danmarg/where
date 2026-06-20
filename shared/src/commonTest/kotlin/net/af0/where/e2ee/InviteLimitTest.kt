package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InviteLimitTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testInviteLimitDropsOldest() = runTest {
        val driver = createTestSqlDriver()
        val manager = testE2eeManager(driver)

        // Create 10 invites
        val firstQr = manager.createInvite("Invite-0")
        repeat(9) {
            manager.createInvite("Invite-${it + 1}")
        }

        // The 11th should NOT throw IllegalStateException anymore
        val eleventhQr = manager.createInvite("Invite-11")
        assertNotNull(eleventhQr)

        // Verify that we still only have 10 invites
        // We can't directly check the size of pendingInvites as it's private in E2eeManager,
        // but we can check via processKeyExchangeInit (or adding a test-only getter if needed).
        // Actually, we can check via the persistence snapshot if we really want to, 
        // but let's see if there's a better way.
        
        // Let's try to use the first QR - it should fail to find a pending invite
        val result = manager.processKeyExchangeInit(
            payload = KeyExchangeInitPayload(
                v = 1,
                ekPub = ByteArray(32),
                keyConfirmation = ByteArray(32),
                suggestedName = "Bob"
            ),
            aliceSuggestedName = "Alice",
            aliceEkPub = firstQr.ekPub
        )
        assertNull(result, "The first invite should have been dropped")

        // The 11th one should still be there
        // (We can't easily test this without a real KeyExchangeInitPayload that matches eleventhQr)
    }
}
