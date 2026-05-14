package net.af0.where

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import net.af0.where.e2ee.*
import net.af0.where.model.UserLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiFriendIntegrationTest {

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    class TestStorage : RawKeyValueStorage {
        val map = mutableMapOf<String, String>()

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
        ): List<MailboxMessage> {
            val resp = client.get("/inbox/$token")
            if (resp.status != HttpStatusCode.OK) {
                throw ServerException(resp.status.value, "Poll failed")
            }
            return resp.body()
        }

        override suspend fun ack(baseUrl: String, token: String, ids: List<String>) {
            client.delete("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(ids)
            }
        }
    }

    @Test
    fun `central Alice receives updates from Bob and Charlie in the same poll`() =
        testApplication {
            LibsodiumInitializer.ensureInitialized()
            val state = ServerState(debug = true)
            application {
                module(state)
            }

            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
            val mailboxClient = KtorTestMailboxClient(client)

            // Setup 3 managers
            val aStore = E2eeManager(TestStorage())
            val bStore = E2eeManager(TestStorage())
            val cStore = E2eeManager(TestStorage())

            val aClient = LocationClient("http://localhost", aStore, mailboxClient)
            val bClient = LocationClient("http://localhost", bStore, mailboxClient)
            val cClient = LocationClient("http://localhost", cStore, mailboxClient)

            // Pair A-B
            val qrB = aStore.createInvite("Alice")
            val (initB, _) = bStore.processScannedQr(qrB, "Bob")
            aClient.postKeyExchangeInit(qrB, initB)

            // Pair A-C
            val qrC = aStore.createInvite("Alice")
            val (initC, _) = cStore.processScannedQr(qrC, "Charlie")
            aClient.postKeyExchangeInit(qrC, initC)

            // Alice polls pending invites to complete handshakes
            val pending = aClient.pollPendingInvites()
            for (p in pending) {
                val name = if (p.payload.suggestedName.isNotEmpty()) p.payload.suggestedName else "Peer"
                aStore.processKeyExchangeInit(p.payload, name, p.aliceEkPub)
            }

            assertEquals(2, aStore.listFriends().size, "Alice should have 2 friends after handshakes")

            // Bob sends location
            bClient.sendLocation(1.1, 1.1)

            // Charlie sends location
            cClient.sendLocation(2.2, 2.2)

            // Alice polls ONCE, should get both
            val updates = aClient.poll()

            assertEquals(2, updates.size, "Alice should have received 2 location updates in one poll cycle")
            val latSet = updates.map { it.lat }.toSet()
            assertEquals(setOf(1.1, 2.2), latSet)
        }
}
