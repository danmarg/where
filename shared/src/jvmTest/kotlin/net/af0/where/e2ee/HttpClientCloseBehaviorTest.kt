package net.af0.where.e2ee

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the actual close()-during-in-flight-request behavior of the OkHttp engine (the one
 * used in production on JVM/Android, see HttpClientFactory.kt) against a real server, rather than
 * assuming it from documentation. This is the behavior KtorMailboxClient's auto-reset
 * (resetConnection() closing a superseded client after CLOSE_GRACE_PERIOD_MS) depends on for
 * safety against a concurrently-healthy friend's request sharing the same client instance.
 *
 * CONFIRMED (this test): closing the wrapping Ktor HttpClient does NOT abort a request that's
 * already dispatched on it - the underlying OkHttp Call keeps running on its own thread and
 * completes normally. close() only stops accepting *new* work (matches ExecutorService.shutdown()
 * semantics, not shutdownNow()). So a friend's failure burst triggering resetConnection() cannot
 * abort another friend's concurrently-in-flight, otherwise-healthy call on the pre-reset client -
 * that call simply finishes on its own. CLOSE_GRACE_PERIOD_MS remains as a defensive margin (the
 * Darwin/iOS engine isn't covered by this JVM-only test, and a delay before releasing the old
 * client's resources is cheap insurance either way), but per this test it isn't load-bearing for
 * the specific "in-flight request gets aborted" risk it was originally written to guard against -
 * see git history for the prior (incorrect) assumption.
 */
class HttpClientCloseBehaviorTest {
    private lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>
    private var port: Int = 0

    @BeforeTest
    fun startServer() {
        server =
            embeddedServer(Netty, port = 0) {
                routing {
                    get("/slow") {
                        delay(5_000)
                        call.respondText("too late")
                    }
                }
            }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(0, 0)
    }

    @Test
    fun `closing the client while a request is in flight does not abort that request - it completes normally`() =
        runBlocking {
            val client =
                HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 10_000
                        socketTimeoutMillis = 30_000
                    }
                }

            val requestJob =
                async {
                    runCatching { client.get("http://localhost:$port/slow") }
                }

            // Give the request time to actually be in flight (connected, awaiting the server's
            // deliberately-delayed response) before superseding the client - this is the exact
            // race resetConnection() creates for a concurrently-healthy friend's call.
            delay(500)
            val closeStart = System.currentTimeMillis()
            client.close()

            val result = requestJob.await()
            val elapsedAfterClose = System.currentTimeMillis() - closeStart

            // The already-dispatched OkHttp call is unaffected by closing the wrapping Ktor
            // client - it runs to completion on its own, taking roughly the server's remaining
            // delay (not aborted early, and not hanging indefinitely either).
            assertTrue(
                result.isSuccess,
                "An in-flight request must complete normally when its client is closed mid-flight, " +
                    "not be aborted - got: ${result.exceptionOrNull()}",
            )
            assertEquals(200, result.getOrNull()?.status?.value)
            assertTrue(
                elapsedAfterClose in 3_500..10_000,
                "Expected the request to finish around the server's remaining ~4.5s delay, not be " +
                    "cut short or hang well past it (observed ${elapsedAfterClose}ms)",
            )
            assertTrue(
                requestJob.isCompleted && !requestJob.isCancelled,
                "The calling coroutine itself must complete normally, not be cancelled - closing " +
                    "one friend's superseded client must not collaterally cancel another friend's call site",
            )
        }

    @Test
    fun `a fresh client created after close is unaffected and can complete a request normally`() =
        runBlocking {
            val oldClient = HttpClient(OkHttp)
            oldClient.close()

            // Mirrors resetConnection(): a brand-new client instance, independent of the one just
            // closed, must work normally - the close() must not affect shared/global engine state.
            // Hits an unrouted path (not /slow) so a prompt 404 proves connectivity without
            // waiting out the server's deliberate delay.
            val newClient = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 5_000 } }
            try {
                val response = newClient.get("http://localhost:$port/unrouted")
                assertEquals(404, response.status.value, "A fresh client after a prior close must still complete real requests normally")
            } finally {
                newClient.close()
            }
        }
}
