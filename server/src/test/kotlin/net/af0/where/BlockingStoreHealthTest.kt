package net.af0.where

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockingStoreHealthTest {
    private class BlockingStore(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : MailboxStore by InMemoryMailboxState() {
        @Volatile var drainThread: String? = null

        override fun drain(token: String): List<JsonElement>? {
            drainThread = Thread.currentThread().name
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            return emptyList()
        }
    }

    @Test
    fun `a blocked store call does not block health`() =
        testApplication {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val store = BlockingStore(entered, release)
            val pool = Executors.newCachedThreadPool { Thread(it, "store-io").apply { isDaemon = true } }
            val dispatcher = pool.asCoroutineDispatcher()
            application { module(ServerState(mailbox = store, debug = true, storeDispatcher = dispatcher)) }
            coroutineScope {
                val inbox = async { client.get("/inbox/tok") }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                    assertEquals(HttpStatusCode.OK, client.get("/health").status)
                } finally {
                    release.countDown()
                }
                assertEquals(HttpStatusCode.OK, inbox.await().status)
                // Regression guard: the store call must run on the store dispatcher, not the request thread.
                assertTrue(store.drainThread?.startsWith("store-io") == true, "drain ran on ${store.drainThread}")
            }
            pool.shutdown()
        }

    private fun clientWith(
        selfStatus: HttpStatusCode,
        pingStatus: HttpStatusCode,
        hits: MutableList<String>,
    ) = HttpClient(
        MockEngine { req ->
            hits += req.url.encodedPath
            respond("", if (req.url.encodedPath == "/health") selfStatus else pingStatus)
        },
    )

    @Test
    fun `ping is sent only after self health succeeds`() =
        runBlocking {
            val hits = mutableListOf<String>()
            assertTrue(pingHealthcheck(clientWith(HttpStatusCode.OK, HttpStatusCode.OK, hits), "http://x/health", "http://x/ping"))
            assertEquals(listOf("/health", "/ping"), hits)
        }

    @Test
    fun `failing self health skips the ping`() =
        runBlocking {
            val hits = mutableListOf<String>()
            val c = clientWith(HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK, hits)
            assertFalse(pingHealthcheck(c, "http://x/health", "http://x/ping"))
            assertEquals(listOf("/health"), hits)
        }

    @Test
    fun `non-2xx ping response counts as failure`() =
        runBlocking {
            val hits = mutableListOf<String>()
            val c = clientWith(HttpStatusCode.OK, HttpStatusCode.InternalServerError, hits)
            assertFalse(pingHealthcheck(c, "http://x/health", "http://x/ping"))
        }
}
