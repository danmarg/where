package net.af0.where

import io.ktor.server.testing.*
import io.ktor.serialization.kotlinx.json.json
import net.af0.where.e2ee.*
import kotlin.test.*

import com.ionspin.kotlin.crypto.LibsodiumInitializer

class MultiFriendIntegrationTest {
    init {
        LibsodiumInitializer.initializeWithCallback {
            // Libsodium initialized
        }
    }

    @Test
    fun `central Alice receives updates from Bob and Charlie in the same poll`() =
        testApplication {
            val server = ServerState()
            application { module(server) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val charlieStore = E2eeStore(MemoryStorage())

            val aliceClient = LocationClient("", aliceStore, testClient.toMailboxClient())
            val bobClient = LocationClient("", bobStore, testClient.toMailboxClient())
            val charlieClient = LocationClient("", charlieStore, testClient.toMailboxClient())

            // --- PAIR ALICE AND BOB ---
            val qrAB = aliceStore.createInvite("Alice")
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            testClient.toMailboxClient().post("", qrAB.discoveryToken().toHex(), initAB)

            val resultsAB = aliceClient.pollPendingInvites()
            val resAB = resultsAB.first { it.discoveryTokenHex == qrAB.discoveryToken().toHex() }
            aliceStore.processKeyExchangeInit(
                payload = resAB.payload,
                bobName = "Bob",
                discoveryTokenHex = resAB.discoveryTokenHex
            )

            // --- PAIR ALICE AND CHARLIE ---
            val qrAC = aliceStore.createInvite("Alice")
            val (initAC, _) = charlieStore.processScannedQr(qrAC, "Alice")
            testClient.toMailboxClient().post("", qrAC.discoveryToken().toHex(), initAC)

            val resultsAC = aliceClient.pollPendingInvites()
            val resAC = resultsAC.first { it.discoveryTokenHex == qrAC.discoveryToken().toHex() }
            aliceStore.processKeyExchangeInit(
                payload = resAC.payload,
                bobName = "Charlie",
                discoveryTokenHex = resAC.discoveryTokenHex
            )

            val friends = aliceStore.listFriends()
            assertEquals(2, friends.size)

            // --- SEND FROM BOTH SPOKES ---
            bobClient.sendLocation(1.1, 1.1)
            charlieClient.sendLocation(2.2, 2.2)

            // --- ALICE POLLS ONCE ---
            val updates = aliceClient.poll()
            assertEquals(2, updates.size, "Alice should see both updates in one poll batch")

            val lats = updates.map { it.lat }.toSet()
            assertTrue(lats.contains(1.1))
            assertTrue(lats.contains(2.2))
        }

    @Test
    fun `Bob can send multiple messages to Alice before Alice polls`() =
        testApplication {
            application { module(ServerState()) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())

            val aliceClient = LocationClient("", aliceStore, testClient.toMailboxClient())
            val bobClient = LocationClient("", bobStore, testClient.toMailboxClient())

            val qr = aliceStore.createInvite("Alice")
            val (init, _) = bobStore.processScannedQr(qr, "Alice")
            testClient.toMailboxClient().post("", qr.discoveryToken().toHex(), init)

            val results = aliceClient.pollPendingInvites()
            val res = results.first()
            aliceStore.processKeyExchangeInit(
                payload = res.payload,
                bobName = "Bob",
                discoveryTokenHex = res.discoveryTokenHex
            )

            // Bob sends 3 updates.
            bobClient.sendLocation(1.0, 1.0)
            bobClient.sendLocation(2.0, 2.0)
            bobClient.sendLocation(3.0, 3.0)

            val updates = aliceClient.poll()
            assertEquals(3, updates.size, "Alice should receive all 3 messages in order")
            assertEquals(1.0, updates[0].lat)
            assertEquals(2.0, updates[1].lat)
            assertEquals(3.0, updates[2].lat)
        }

    @Test
    fun `Simultaneous bi-directional handshake completes correctly`() =
        testApplication {
            application { module(ServerState()) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aliceStore = E2eeStore(MemoryStorage())

            val aliceClient = LocationClient("", aliceStore, testClient.toMailboxClient())

            // Alice invites Bob
            val qrAB = aliceStore.createInvite("Alice")
            val bobStore = E2eeStore(MemoryStorage())
            val bobClient = LocationClient("", bobStore, testClient.toMailboxClient())
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            testClient.toMailboxClient().post("", qrAB.discoveryToken().toHex(), initAB)

            // Alice invites Charlie
            val qrAC = aliceStore.createInvite("Alice")
            val charlieStore = E2eeStore(MemoryStorage())
            val charlieClient = LocationClient("", charlieStore, testClient.toMailboxClient())
            val (initAC, _) = charlieStore.processScannedQr(qrAC, "Alice")
            testClient.toMailboxClient().post("", qrAC.discoveryToken().toHex(), initAC)

            val handshakes = aliceClient.pollPendingInvites()
            assertEquals(2, handshakes.size)

            // Alice confirms Bob
            val bobHandshake = handshakes.first { it.payload.token == initAB.token }
            aliceStore.processKeyExchangeInit(
                payload = bobHandshake.payload,
                bobName = "Bob",
                discoveryTokenHex = bobHandshake.discoveryTokenHex
            )

            // Alice confirms Charlie
            val charlieHandshake = handshakes.first { it.payload.token == initAC.token }
            aliceStore.processKeyExchangeInit(
                payload = charlieHandshake.payload,
                bobName = "Charlie",
                discoveryTokenHex = charlieHandshake.discoveryTokenHex
            )

            assertEquals(2, aliceStore.listFriends().size)
        }
}
