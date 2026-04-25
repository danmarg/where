package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MultiFriendSyncTest {
    init {
        initializeE2eeTests()
    }

    class MemoryStorage : E2eeStorage {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }
    }

    @Test
    fun `poll returns updates from successful friends even if one fails`() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val charlieStore = E2eeStore(MemoryStorage())

            // Pair A-B
            val qrAB = aliceStore.createInvite("Alice")
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            val friendB = aliceStore.processKeyExchangeInit(initAB, "Bob", qrAB.discoveryToken().toHex())
            assertNotNull(friendB)

            // Pair A-C
            val qrAC = aliceStore.createInvite("Alice")
            val (initAC, _) = charlieStore.processScannedQr(qrAC, "Alice")
            val friendC = aliceStore.processKeyExchangeInit(initAC, "Charlie", qrAC.discoveryToken().toHex())
            assertNotNull(friendC)

            // Bob sends a location to Alice
            val locationB = MessagePlaintext.Location(lat = 1.0, lng = 2.0, acc = 0.0, ts = 100L)
            val bobSideOfAlice = bobStore.getFriend(aliceStore.listFriends()[0].id)!!
            val encryptedB = Session.encryptMessage(bobSideOfAlice.session, locationB).second

            // Mock MailboxClient
            val fakeMailbox =
                object : MailboxClient {
                    var pollBCount = 0
                    var pollCCount = 0

                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> {
                        return when (token) {
                            friendB.session.recvToken.toHex() -> {
                                pollBCount++
                                listOf(encryptedB)
                            }
                            friendC.session.recvToken.toHex() -> {
                                pollCCount++
                                throw Exception("Friend C mailbox failure")
                            }
                            else -> emptyList()
                        }
                    }

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {}
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)

            // Alice polls
            val updates = client.poll()

            assertEquals(1, updates.size, "Should have 1 update from Bob")
            assertEquals(friendB.id, updates[0].userId)
            assertEquals(1.0, updates[0].lat)

            assertEquals(1, fakeMailbox.pollBCount)
            assertEquals(1, fakeMailbox.pollCCount)
        }

    @Test
    fun `poll updates friend location persistently in store`() =
        runTest {
            val storage = MemoryStorage()
            val aliceStore = E2eeStore(storage)
            val bobStore = E2eeStore(MemoryStorage())

            // Pair A-B
            val qr = aliceStore.createInvite("Alice")
            val (init, _) = bobStore.processScannedQr(qr, "Alice")
            val friendB = aliceStore.processKeyExchangeInit(init, "Bob", qr.discoveryToken().toHex())!!
            val bobId = friendB.id

            // Bob sends location
            val location = MessagePlaintext.Location(lat = 50.0, lng = 50.0, acc = 0.0, ts = 100L)
            val bobSideOfAlice = bobStore.getFriend(aliceStore.listFriends()[0].id)!!
            val encrypted = Session.encryptMessage(bobSideOfAlice.session, location).second

            val fakeMailbox =
                object : MailboxClient {
                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> {
                        return if (token == friendB.session.recvToken.toHex()) listOf(encrypted) else emptyList()
                    }

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {}
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)

            // Alice polls
            client.poll()

            // Simulate restart: Create a NEW store instance using the SAME storage
            val aliceStoreRestarted = E2eeStore(storage)
            val bobAfterRestart = aliceStoreRestarted.getFriend(bobId)!!

            assertEquals(100L, bobAfterRestart.lastTs, "Location timestamp should persist")
            assertEquals(50.0, bobAfterRestart.lastLat, "Latitude should persist")
        }

    @Test
    fun `sendLocation succeeds if at least one friend succeeds`() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val charlieStore = E2eeStore(MemoryStorage())

            val qrAB = aliceStore.createInvite("Alice")
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            val friendB = aliceStore.processKeyExchangeInit(initAB, "Bob", qrAB.discoveryToken().toHex())
            assertNotNull(friendB)

            val qrAC = aliceStore.createInvite("Alice")
            val (initAC, _) = charlieStore.processScannedQr(qrAC, "Alice")
            val friendC = aliceStore.processKeyExchangeInit(initAC, "Charlie", qrAC.discoveryToken().toHex())
            assertNotNull(friendC)

            // Mock MailboxClient
            val fakeMailbox =
                object : MailboxClient {
                    var postSuccessCount = 0
                    var postFailCount = 0

                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> = emptyList()

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {
                        if (token == friendB.session.sendToken.toHex()) {
                            postSuccessCount++
                        } else if (token == friendC.session.sendToken.toHex()) {
                            postFailCount++
                            throw Exception("Mock failure")
                        }
                    }
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)

            // Should not throw because Bob succeeds
            client.sendLocation(1.0, 2.0)

            assertTrue(fakeMailbox.postSuccessCount >= 1, "Should have at least 1 success for Bob")
            assertTrue(fakeMailbox.postFailCount >= 1, "Should have at least 1 failure for Charlie")
        }

    @Test
    fun `poll throws if ALL friends fail`() =
        runTest {
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val qrAB = aliceStore.createInvite("Alice")
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            aliceStore.processKeyExchangeInit(initAB, "Bob", qrAB.discoveryToken().toHex())!!

            val fakeMailbox =
                object : MailboxClient {
                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> {
                        throw Exception("Total failure")
                    }

                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {}
                }

            val client = LocationClient("http://fake", aliceStore, fakeMailbox)

            assertFailsWith<Exception> {
                client.poll()
            }
        }
}
