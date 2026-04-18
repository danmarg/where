package net.af0.where

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import net.af0.where.e2ee.*
import kotlin.test.*

class MultiFriendIntegrationTest {
    init {
        LibsodiumInitializer.initializeWithCallback {
            // Libsodium initialized
        }
    }

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
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

    class KtorTestMailboxClient(private val client: HttpClient) : MailboxClient {
        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            val resp =
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            if (resp.status != HttpStatusCode.NoContent && resp.status != HttpStatusCode.OK) {
                throw ServerException(resp.status.value, "Post failed")
            }
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            val resp = client.get("/inbox/$token")
            if (resp.status != HttpStatusCode.OK) {
                throw ServerException(resp.status.value, "Poll failed")
            }
            return resp.body()
        }
    }

    @Test
    fun `central Alice receives updates from Bob and Charlie in the same poll`() =
        testApplication {
            application { module(ServerState()) }

            val testClient =
                createClient {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
            val mailboxClient = KtorTestMailboxClient(testClient)

            // 1. Setup Alice (iPhone)
            val aliceStore = E2eeStore(MemoryStorage())
            val aliceClient = LocationClient("", aliceStore, mailboxClient)

            // 2. Setup Bob (Android 1)
            val bobStore = E2eeStore(MemoryStorage())
            val bobClient = LocationClient("", bobStore, mailboxClient)

            // 3. Setup Charlie (Android 2)
            val charlieStore = E2eeStore(MemoryStorage())
            val charlieClient = LocationClient("", charlieStore, mailboxClient)

            // --- PAIR ALICE AND BOB ---
            val qrAB = aliceStore.createInvite("Alice")
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            aliceStore.processKeyExchangeInit(initAB, "Bob")

            // --- PAIR ALICE AND CHARLIE ---
            val qrAC = aliceStore.createInvite("Alice")
            val (initAC, _) = charlieStore.processScannedQr(qrAC, "Alice")
            aliceStore.processKeyExchangeInit(initAC, "Charlie")

            val friends = aliceStore.listFriends()
            assertEquals(2, friends.size)
            val bobId = friends.find { it.name == "Bob" }!!.id
            val charlieId = friends.find { it.name == "Charlie" }!!.id

            // Bob sends location
            bobClient.sendLocation(1.0, 1.0)

            // Charlie sends location
            charlieClient.sendLocation(2.0, 2.0)

            // Alice polls
            val updates = aliceClient.poll()

            // Assert we got both!
            assertEquals(2, updates.size, "Alice should receive updates from both friends")

            val bobUpdate = updates.find { it.userId == bobId }
            val charlieUpdate = updates.find { it.userId == charlieId }

            assertNotNull(bobUpdate)
            assertEquals(1.0, bobUpdate.lat)

            assertNotNull(charlieUpdate)
            assertEquals(2.0, charlieUpdate.lat)

            // Check persistent lastTs
            val bobAfter = aliceStore.getFriend(bobId)!!
            val charlieAfter = aliceStore.getFriend(charlieId)!!

            assertNotNull(bobAfter.lastTs, "Bob's lastTs should be updated in store")
            assertNotNull(charlieAfter.lastTs, "Charlie's lastTs should be updated in store")
        }

    @Test
    fun `poll updates friend location persistently in store`() =
        testApplication {
            application { module(ServerState()) }

            val testClient =
                createClient {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
            val mailboxClient = KtorTestMailboxClient(testClient)

            val storage = MemoryStorage()
            val aliceStore = E2eeStore(storage)
            val aliceClient = LocationClient("", aliceStore, mailboxClient)

            val bobStore = E2eeStore(MemoryStorage())
            val bobClient = LocationClient("", bobStore, mailboxClient)

            // Pair A-B
            val qr = aliceStore.createInvite("Alice")
            val (init, _) = bobStore.processScannedQr(qr, "Alice")
            aliceStore.processKeyExchangeInit(init, "Bob")
            val bobId = aliceStore.listFriends()[0].id

            // Bob sends location
            bobClient.sendLocation(50.0, 50.0)

            // Alice polls
            aliceClient.poll()

            // --- SIMULATE RESTART ---
            // Create a NEW store instance using the SAME storage
            val aliceStoreRestarted = E2eeStore(storage)
            val bobAfterRestart = aliceStoreRestarted.getFriend(bobId)!!

            // THIS IS THE BUG: Currently, this will be NULL because poll()
            // didn't update the FriendEntry in the store!
            assertNotNull(bobAfterRestart.lastTs, "Bob's location should persist after poll and restart")
            assertEquals(50.0, bobAfterRestart.lastLat)
        }

    @Test
    fun `central Alice receiving from Bob does not interfere with Charlie receiving from Alice`() =
        testApplication {
            application { module(ServerState()) }

            val testClient =
                createClient {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
            val mailboxClient = KtorTestMailboxClient(testClient)

            val aliceStore = E2eeStore(MemoryStorage())
            val aliceClient = LocationClient("", aliceStore, mailboxClient)

            val bobStore = E2eeStore(MemoryStorage())
            val bobClient = LocationClient("", bobStore, mailboxClient)

            val charlieStore = E2eeStore(MemoryStorage())
            val charlieClient = LocationClient("", charlieStore, mailboxClient)

            // Pair A-B and A-C
            val qrAB = aliceStore.createInvite("Alice")
            val (initAB, _) = bobStore.processScannedQr(qrAB, "Alice")
            aliceStore.processKeyExchangeInit(initAB, "Bob")

            val qrAC = aliceStore.createInvite("Alice")
            val (initAC, _) = charlieStore.processScannedQr(qrAC, "Alice")
            aliceStore.processKeyExchangeInit(initAC, "Charlie")

            val bobId = aliceStore.listFriends().find { it.name == "Bob" }!!.id
            val charlieId = aliceStore.listFriends().find { it.name == "Charlie" }!!.id

            // 1. Bob sends location to Alice
            bobClient.sendLocation(1.1, 1.1)

            // 2. Alice sends location to both (Bob and Charlie)
            aliceClient.sendLocation(0.0, 0.0)

            // 3. Charlie polls Alice
            val charlieUpdates = charlieClient.poll()
            assertEquals(1, charlieUpdates.size, "Charlie should see Alice's update")
            assertEquals(0.0, charlieUpdates[0].lat)

            // 4. Alice polls Bob
            val aliceUpdates = aliceClient.poll()
            val bobUpdate = aliceUpdates.find { it.userId == bobId }
            assertNotNull(bobUpdate, "Alice should see Bob's update")
            assertEquals(1.1, bobUpdate.lat)

            // 5. Bob polls Alice
            val bobUpdates = bobClient.poll()
            assertEquals(1, bobUpdates.size, "Bob should see Alice's update")
            assertEquals(0.0, bobUpdates[0].lat)
        }
}
