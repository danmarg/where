package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocationClientTest {
    init {
        initializeE2eeTests()
    }

    private lateinit var storage: MemoryStorage
    private lateinit var store: E2eeStore

    @BeforeTest
    fun setup() {
        storage = MemoryStorage()
        store = E2eeStore(storage)
    }

    @Test
    fun `pollPendingInvite picks most recent scan`() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val qr = aliceStore.createInvite("Alice")

            // Simulate two scans (Bob1 and Bob2) in the same mailbox.
            // Bob1 scan
            val (init1, _) = store.processScannedQr(qr, "Bob1")
            // Bob2 scan (retry or different person)
            val (init2, _) = store.processScannedQr(qr, "Bob2")

            // Mock MailboxClient that returns both messages in FIFO order.
            val fakeMailbox =
                object : MailboxClient {
                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> {
                        // Return them in the order they were "posted"
                        return listOf(
                            KeyExchangeInitPayload(
                                v = init1.v,
                                token = init1.token,
                                ekPub = init1.ekPub,
                                keyConfirmation = init1.keyConfirmation,
                                suggestedName = init1.suggestedName,
                            ),
                            KeyExchangeInitPayload(
                                v = init2.v,
                                token = init2.token,
                                ekPub = init2.ekPub,
                                keyConfirmation = init2.keyConfirmation,
                                suggestedName = init2.suggestedName,
                            ),
                        )
                    }

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {}
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)
            val result = client.pollPendingInvite()

            assertNotNull(result)
            assertEquals("Bob2", result.payload.suggestedName, "Should pick the last message (most recent scan)")
            assertTrue(result.multipleScansDetected, "Should detect multiple scans")
        }
}
