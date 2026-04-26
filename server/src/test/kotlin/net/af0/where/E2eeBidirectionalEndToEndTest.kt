package net.af0.where

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import io.ktor.server.testing.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import net.af0.where.e2ee.*
import kotlin.test.*
import kotlinx.serialization.json.Json

class E2eeBidirectionalEndToEndTest {
    init {
        LibsodiumInitializer.initializeWithCallback {
            // Libsodium initialized
        }
    }

    @Test
    fun `Alice and Bob exchange locations bi-directionally via server`() =
        testApplication {
            val state = ServerState(debug = true)
            application { module(state) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aliceStorage = MemoryStorage()
            val bobStorage = MemoryStorage()

            val aliceStore = E2eeStore(aliceStorage)
            val bobStore = E2eeStore(bobStorage)

            val aliceClient = LocationClient(baseUrl = "", store = aliceStore, mailboxClient = testClient.toMailboxClient())
            val bobClient = LocationClient(baseUrl = "", store = bobStore, mailboxClient = testClient.toMailboxClient())

            // --- PAIRING PHASE ---

            // 1. Alice creates invite
            val qr = aliceStore.createInvite("Alice")
            val discoveryToken = qr.discoveryToken().toHex()
            println("Alice created invite: discoveryToken=$discoveryToken")

            // 2. Bob joins Alice's invite
            val (initMsg, _) = bobStore.processScannedQr(qr, "Bob")
            println("Bob processed QR and generated KeyExchangeInit")

            // 3. Bob posts KeyExchangeInit to Alice's discovery token
            testClient.toMailboxClient().post("", discoveryToken, initMsg)
            println("Bob posted KeyExchangeInit to $discoveryToken")

            // 4. Alice polls discovery token and finalizes handshake
            val results = aliceClient.pollPendingInvites()
            assertFalse(results.isEmpty(), "Alice should find Bob's KeyExchangeInit on the discovery token")
            val result = results.first()

            val aliceEntry = aliceStore.processKeyExchangeInit(
                payload = result.payload,
                bobName = "Bob",
                discoveryTokenHex = result.discoveryTokenHex
            )
            assertNotNull(aliceEntry, "Alice should process KeyExchangeInit successfully")
            val aliceFriendId = aliceEntry.id
            println("✓ Alice processed KeyExchangeInit, friendId=${aliceFriendId.take(8)}")
            println()

            // Verify both sides have matching, symmetric tokens
            val aliceSession = aliceStore.getFriend(aliceFriendId)!!.session
            val bobSession = bobStore.getFriend(aliceFriendId)!!.session

            // Verify initial session symmetry.
            // In the new Sealed Envelope protocol, Alice performs an initial DH ratchet
            // rotation (Epoch 1) immediately after handshake. Bob remains in Epoch 0.
            assertContentEquals(aliceSession.prevSendToken, bobSession.recvToken, "Alice send (prev) = Bob recv")
            assertContentEquals(aliceSession.recvToken, bobSession.sendToken, "Alice recv = Bob send")

            // --- SYNC PHASE ---

            // A -> B: Alice sends her first location
            println("--- A -> B (Message 1) ---")
            val latA1 = 52.5200
            val lngA1 = 13.4050
            aliceClient.sendLocation(latA1, lngA1)

            // Bob polls and receives A1
            val updatesB1 = bobClient.poll()
            assertEquals(1, updatesB1.size, "Bob should receive 1 location update")
            assertEquals(latA1, updatesB1[0].lat, "Bob should see correct lat from Alice")
            println("✓ Bob received Alice's first location")

            // B -> A: Bob responds with his location
            println("--- B -> A (Message 2) ---")
            val latB1 = 48.8566
            val lngB1 = 2.3522
            bobClient.sendLocation(latB1, lngB1)

            // Alice polls and receives B1
            val updatesA1 = aliceClient.poll()
            assertEquals(1, updatesA1.size, "Alice should receive 1 location update")
            assertEquals(latB1, updatesA1[0].lat, "Alice should see correct lat from Bob")
            println("✓ Alice received Bob's first location")

            // A -> B: Alice sends a second location
            println("--- A -> B (Message 3) ---")
            val latA2 = 52.5300
            val lngA2 = 13.4150
            aliceClient.sendLocation(latA2, lngA2)

            // Bob polls and receives A2
            val updatesB2 = bobClient.poll()
            assertEquals(1, updatesB2.size, "Bob should receive 1 location update (A2)")
            assertEquals(latA2, updatesB2[0].lat, "Bob should see correct second lat from Alice")
            println("✓ Bob received Alice's second location")

            println("✓ Bi-directional E2EE location sync verified")
        }

    @Test
    fun `Hub-and-Spoke Alice receives updates from Bob and Charlie simultaneously`() =
        testApplication {
            val state = ServerState(debug = false)
            application { module(state) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aStore = E2eeStore(MemoryStorage())
            val bStore = E2eeStore(MemoryStorage())
            val cStore = E2eeStore(MemoryStorage())

            val aClient = LocationClient("", aStore, testClient.toMailboxClient())
            val bClient = LocationClient("", bStore, testClient.toMailboxClient())
            val cClient = LocationClient("", cStore, testClient.toMailboxClient())

            // Pairing: A (QR creator) ↔ B (scanner)
            val qrAB = aStore.createInvite("Hub-A")
            val (initAB, _) = bStore.processScannedQr(qrAB, "B")
            testClient.toMailboxClient().post("", qrAB.discoveryToken().toHex(), initAB)
            val resultsAB = aClient.pollPendingInvites()
            val resultAB = resultsAB.first { it.discoveryTokenHex == qrAB.discoveryToken().toHex() }
            val aEntryForB = aStore.processKeyExchangeInit(
                payload = resultAB.payload,
                bobName = "B",
                discoveryTokenHex = resultAB.discoveryTokenHex
            )!!
            val friendIdAB = aEntryForB.id

            // Pairing: A (QR creator) ↔ C (scanner)
            val qrAC = aStore.createInvite("Hub-A")
            val (initAC, _) = cStore.processScannedQr(qrAC, "C")
            testClient.toMailboxClient().post("", qrAC.discoveryToken().toHex(), initAC)
            val resultsAC = aClient.pollPendingInvites()
            val resultAC = resultsAC.first { it.discoveryTokenHex == qrAC.discoveryToken().toHex() }
            val aEntryForC = aStore.processKeyExchangeInit(
                payload = resultAC.payload,
                bobName = "C",
                discoveryTokenHex = resultAC.discoveryTokenHex
            )!!
            val friendIdAC = aEntryForC.id

            // B sends location
            bClient.sendLocation(10.0, 10.0)
            // C sends location
            cClient.sendLocation(20.0, 20.0)

            // A polls and should see both in one batch
            val updates = aClient.poll()
            assertEquals(2, updates.size, "Alice should receive updates from both friends")

            val lats = updates.map { it.lat }.toSet()
            assertTrue(lats.contains(10.0))
            assertTrue(lats.contains(20.0))
            println("✓ Hub Alice received simultaneous updates from Bob and Charlie")
        }

    @Test
    fun `Bob can retrieve Alice's first message even if Alice ratchets immediately after handshake`() =
        testApplication {
            application { module(ServerState()) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aStore = E2eeStore(MemoryStorage())
            val bStore = E2eeStore(MemoryStorage())

            val aClient = LocationClient("", aStore, testClient.toMailboxClient())
            val bClient = LocationClient("", bStore, testClient.toMailboxClient())

            val qr = aStore.createInvite("A")
            val (init, _) = bStore.processScannedQr(qr, "B")
            testClient.toMailboxClient().post("", qr.discoveryToken().toHex(), init)

            val results = aClient.pollPendingInvites()
            val res = results.first()
            val aEntry = aStore.processKeyExchangeInit(
                payload = res.payload,
                bobName = "B",
                discoveryTokenHex = res.discoveryTokenHex
            )!!
            val friendId = aEntry.id

            // Alice sends location. This message is Epoch 1.
            // It must be posted to prevSendToken (T_AB_0) because Bob is still polling T_AB_0.
            aClient.sendLocation(52.0, 13.0)

            // Bob polls. He should see the message on T_AB_0, decrypt it, and advance to Epoch 1.
            val updates = bClient.poll()
            assertEquals(1, updates.size)
            assertEquals(52.0, updates[0].lat)

            val bFriend = bStore.getFriend(friendId)!!
            assertContentEquals(aStore.getFriend(friendId)!!.session.sendToken, bFriend.session.recvToken)
            println("✓ Bob successfully retrieved Alice's first message after immediate ratchet")
        }

    @Test
    fun `Rapid polling recovers from initial send failure`() =
        testApplication {
            application { module(ServerState()) }
            val testClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(net.af0.where.GlobalTestJson)
                }
            }

            val aStore = E2eeStore(MemoryStorage())
            val bStore = E2eeStore(MemoryStorage())

            val aClient = LocationClient("", aStore, testClient.toMailboxClient())
            val bClient = LocationClient("", bStore, testClient.toMailboxClient())

            val qr = aStore.createInvite("A")
            val (init, _) = bStore.processScannedQr(qr, "B")
            testClient.toMailboxClient().post("", qr.discoveryToken().toHex(), init)

            val results = aClient.pollPendingInvites()
            val res = results.first()
            val aEntry = aStore.processKeyExchangeInit(
                payload = res.payload,
                bobName = "B",
                discoveryTokenHex = res.discoveryTokenHex
            )!!
            val friendId = aEntry.id

            aClient.sendLocation(0.0, 0.0)
            var quiet = 0
            while (quiet < 3) {
                val aLen = aClient.poll().size
                val bLen = bClient.poll().size
                if (aLen == 0 && bLen == 0) quiet++ else quiet = 0
                delay(10)
            }

            println("✓ Quiet state reached after handshake")
        }
}
