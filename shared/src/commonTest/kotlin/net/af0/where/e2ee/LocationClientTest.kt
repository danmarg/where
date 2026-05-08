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
            val results = client.pollPendingInvites()

            assertNotNull(results)
            assertEquals(1, results.size)
            val result = results[0]
            assertEquals("Bob2", result.payload.suggestedName, "Should pick the last message (most recent scan)")
            assertTrue(result.multipleScansDetected, "Should detect multiple scans")
        }

    @Test
    fun `poll should NOT process pending invites automatically`() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())

            // Alice creates an invite
            val qr = aliceStore.createInvite("Alice")

            // Bob scans it and posts his Init
            val (init, _) = bobStore.processScannedQr(qr, "Alice")

            // Mock MailboxClient that returns the Init when Alice polls the discovery token
            val discoveryHex = qr.discoveryToken().toHex()
            val fakeMailbox =
                object : MailboxClient {
                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> {
                        return if (token == discoveryHex) {
                            listOf(
                                KeyExchangeInitPayload(
                                    v = init.v,
                                    token = init.token,
                                    ekPub = init.ekPub,
                                    keyConfirmation = init.keyConfirmation,
                                    suggestedName = init.suggestedName,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    }

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {}
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)

            // Verify there are no friends yet
            assertEquals(0, aliceStore.listFriends().size)
            assertEquals(1, aliceStore.listPendingInvites().size)

            // Alice polls for location updates (she has no friends, so no updates expected)
            client.poll()

            // BUG: In the faulty implementation, poll() would have processed the invite.
            assertEquals(0, aliceStore.listFriends().size, "poll() should not have processed the invite and added a friend")
            assertEquals(1, aliceStore.listPendingInvites().size, "Invite should still be pending")
        }

    @Test
    fun `pollPendingInvites should still find pending invites`() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())

            val qr = aliceStore.createInvite("Alice")
            val (init, _) = bobStore.processScannedQr(qr, "Alice")

            val discoveryHex = qr.discoveryToken().toHex()
            val fakeMailbox =
                object : MailboxClient {
                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> {
                        return if (token == discoveryHex) {
                            listOf(
                                KeyExchangeInitPayload(
                                    v = init.v,
                                    token = init.token,
                                    ekPub = init.ekPub,
                                    keyConfirmation = init.keyConfirmation,
                                    suggestedName = init.suggestedName,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    }

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {}
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)

            val results = client.pollPendingInvites()
            assertEquals(1, results.size)
            assertEquals("Alice", results[0].payload.suggestedName)

            // Invite should still be in the store (pollPendingInvites is read-only)
            assertEquals(1, aliceStore.listPendingInvites().size)
        }
}
