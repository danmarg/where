package net.af0.where.e2ee

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * interface for the mailbox service transport (§9).
 */
interface MailboxClient {
    suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    )

    suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload>

    /**
     * Confirm receipt of a specific message by [msgId].
     */
    suspend fun ackId(
        baseUrl: String,
        token: String,
        msgId: String,
    ) {}

    /**
     * Confirm receipt of specific messages by [msgIds].
     */
    suspend fun ackIds(
        baseUrl: String,
        token: String,
        msgIds: List<String>,
    ) {}
}

object KtorMailboxClient : MailboxClient {
    /**
     * Coarse suspension-recovery backstop for mailbox requests. Must stay comfortably above
     * the underlying Ktor HttpClient's requestTimeoutMillis/socketTimeoutMillis (30s, see
     * HttpClientFactory.kt) so that a genuine slow network trips the client's own, more specific
     * timeout exception first. If this value is too close to the Ktor timeout, ordinary jitter
     * races both timers to the same deadline and this generic wall-clock timeout wins instead,
     * masking the real cause.
     */
    private const val MAILBOX_WALL_CLOCK_TIMEOUT_MS = 60_000L

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // KtorMailboxClient was already a process-lifetime singleton object holding one HttpClient
    // for the app's entire life (its transport identity is inherently global state, mutable or
    // not). The fields below are a narrow, deliberate exception to the "no mutable global state"
    // rule, in the same spirit as LocationRepository: self-healing a wedged connection requires
    // swapping *which* HttpClient instance is live at runtime, and that swap has to happen
    // somewhere. All of it is private and serialized behind resetLock except onConnectionReset,
    // which exists solely for diagnostic visibility.

    /**
     * Consecutive mailbox-call failures (across post/poll/ack, any friend) before we assume the
     * underlying HTTP client's connection pool is wedged - e.g. a dead keep-alive connection that
     * keeps getting reused and keeps failing the same way - and force a fresh HttpClient. This is
     * exactly what force-stopping and reopening the app was observed to fix (a stuck outbox that
     * every poll cycle's diagnostics reported as healthy, since polling/receiving is an
     * independent path from sending); this makes that recovery automatic instead of requiring a
     * manual kill. A handful of *consecutive* failures (any success resets the counter) is well
     * past what ordinary transient network blips produce.
     */
    private const val CONSECUTIVE_FAILURE_RESET_THRESHOLD = 5

    /**
     * Grace period before actually closing a superseded HttpClient. A reset is decided from one
     * friend's failure history, but the client is shared by every friend's concurrent calls -
     * closing it immediately could abort another friend's unrelated, currently-healthy in-flight
     * request on the same instance. Most real calls are fast, so this bounds (without fully
     * eliminating) that collateral cancellation while still reclaiming the old client promptly.
     */
    private const val CLOSE_GRACE_PERIOD_MS = 5_000L

    @kotlin.concurrent.Volatile
    private var client = createHttpClient(json)

    private val resetLock = Mutex()
    private var consecutiveFailures = 0
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Notified (best-effort) whenever [CONSECUTIVE_FAILURE_RESET_THRESHOLD] triggers an auto-reset. */
    var onConnectionReset: (() -> Unit)? = null

    private suspend fun <T> withFailureTracking(block: suspend () -> T): T {
        try {
            val result = block()
            if (consecutiveFailures != 0) {
                resetLock.withLock { consecutiveFailures = 0 }
            }
            return result
        } catch (e: WallClockTimeoutCancellationException) {
            // A wedged connection is exactly a hang that only surfaces as a wall-clock timeout -
            // the scenario this whole mechanism exists for - so despite being implemented as a
            // CancellationException (see withWallClockTimeout), it must count as an ordinary
            // failure here, same as LocationClient's own handling of this exception type.
            recordFailureAndMaybeReset()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerException) {
            // A real HTTP response - even an error one - proves the connection itself is not
            // wedged; only a genuine transport-level hang/failure should count toward the reset
            // threshold. Counting this would let one friend's permanently invalid token (e.g.
            // after re-pairing, or 7-day mailbox expiry per the retention policy) keep forcing
            // disruptive resets that do nothing to fix it and can abort other friends' healthy
            // concurrent calls (see CLOSE_GRACE_PERIOD_MS).
            if (consecutiveFailures != 0) {
                resetLock.withLock { consecutiveFailures = 0 }
            }
            throw e
        } catch (e: Exception) {
            recordFailureAndMaybeReset()
            throw e
        }
    }

    private suspend fun recordFailureAndMaybeReset() {
        // The increment-and-maybe-reset decision must be one atomic critical section: if it were
        // check-then-act across two separate lock acquisitions, two callers racing past the
        // threshold in close succession could each decide to reset, discarding a freshly created
        // client and closing the old one twice for a single failure burst.
        val old: HttpClient? =
            resetLock.withLock {
                consecutiveFailures += 1
                if (consecutiveFailures >= CONSECUTIVE_FAILURE_RESET_THRESHOLD) {
                    consecutiveFailures = 0
                    val previous = client
                    client = createHttpClient(json)
                    previous
                } else {
                    null
                }
            }
        if (old != null) {
            backgroundScope.launch {
                delay(CLOSE_GRACE_PERIOD_MS)
                runCatching { old.close() }
            }
            runCatching { onConnectionReset?.invoke() }
        }
    }

    /**
     * POST a message to a mailbox address.
     * @param baseUrl Server base URL (e.g. "http://10.0.2.2:8080")
     * @param token Hex-encoded mailbox address (routing token or discovery token).
     * @param payload The encrypted or handshake payload to send.
     */
    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        try {
            withFailureTracking {
                withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                    val url = "$baseUrl/inbox/$token/${payload.msgId}"
                    val response =
                        client.put(url) {
                            contentType(ContentType.Application.Json)
                            setBody(payload)
                        }
                    if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.OK) {
                        throw ServerException(response.status.value, "Failed to post to mailbox")
                    }
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    /**
     * GET all pending messages for a mailbox address.
     * @param baseUrl Server base URL.
     * @param token Hex-encoded mailbox address.
     * @return List of payloads, or empty if none.
     */
    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> {
        try {
            return withFailureTracking {
                withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                    val response = client.get("$baseUrl/inbox/$token")
                    if (response.status != HttpStatusCode.OK) {
                        throw ServerException(response.status.value, "Failed to poll mailbox")
                    }
                    response.body()
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    override suspend fun ackId(
        baseUrl: String,
        token: String,
        msgId: String,
    ) {
        try {
            withFailureTracking {
                withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                    val response = client.delete("$baseUrl/inbox/$token/$msgId")
                    if (!response.status.isSuccess()) {
                        throw ServerException(response.status.value, "ACK failed for msgId $msgId")
                    }
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    override suspend fun ackIds(
        baseUrl: String,
        token: String,
        msgIds: List<String>,
    ) {
        if (msgIds.isEmpty()) return
        try {
            withFailureTracking {
                withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                    val response =
                        client.delete("$baseUrl/inbox/$token") {
                            parameter("ids", msgIds.joinToString(","))
                        }
                    if (!response.status.isSuccess()) {
                        throw ServerException(response.status.value, "Batch ACK failed for token $token")
                    }
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    private fun mapException(e: Exception): Exception {
        return when (e) {
            is CancellationException -> throw e
            is ConnectTimeoutException, is HttpRequestTimeoutException, is SocketTimeoutException ->
                TimeoutException("Network timeout", e)
            is IOException -> ConnectException(e.message ?: "Connection failed", e)
            is WhereException -> e
            else -> NetworkException(e.message ?: "Unknown network error", e)
        }
    }
}

internal expect fun createHttpClient(json: Json): HttpClient
