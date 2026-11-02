package net.af0.where

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import net.af0.where.e2ee.DhPublicKey
import net.af0.where.e2ee.EncryptedMessagePayload
import net.af0.where.e2ee.KeyExchange
import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.MessagePlaintext
import net.af0.where.e2ee.ReceivedMessage
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.toHex
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises DynamoMailboxState directly against a real (containerized) DynamoDB Local, covering
 * the same behaviors PostgresMailboxStateTest verified for that backend, plus DynamoDB-specific
 * concerns (TTL filtering, the bounded depth-guard scan, client-side postedAt ordering).
 *
 * Uses a plain GenericContainer rather than a dedicated Testcontainers DynamoDB module - the
 * Postgres attempt hit a real Docker-29-vs-Testcontainers-1.x incompatibility that required
 * bumping to 2.x and a renamed artifact; a bare container + endpointOverride sidesteps any
 * similar module-specific gotcha entirely.
 */
class DynamoMailboxStateTest {
    companion object {
        private val container =
            GenericContainer(DockerImageName.parse("amazon/dynamodb-local:latest"))
                .withExposedPorts(8000)
                .waitingFor(Wait.forListeningPort())
                .apply { start() }

        // Mirrors Server.kt's private MAX_QUEUE_DEPTH constant (not visible outside that file).
        private const val TEST_MAX_QUEUE_DEPTH = 10000

        private fun endpoint() = "http://${container.host}:${container.getMappedPort(8000)}"
    }

    private val stores = mutableListOf<DynamoMailboxState>()
    private var tableCounter = 0

    // Fresh table pair per store (rather than a fresh token, as PostgresMailboxStateTest used) -
    // DynamoDB table creation is cheap against Local and this keeps each test's queue-depth-guard
    // and TTL-seeding assertions fully isolated from any other test's items.
    private fun store(
        compactPayloads: Boolean = false,
        wrap: (DynamoDbClient) -> DynamoDbClient = { it },
    ): DynamoMailboxState {
        val client = wrap(createDynamoDbClient("test", "test", "us-east-1", endpoint()))
        val suffix = tableCounter++
        return DynamoMailboxState(
            client,
            messagesTable = "test_messages_$suffix",
            receivedIdsTable = "test_received_$suffix",
            compactPayloads = compactPayloads,
        ).also { stores.add(it) }
    }

    /**
     * Wraps [real] so [override] can replace individual calls (by method name, request-object
     * overloads only); returning null passes the call through. A dynamic proxy rather than Kotlin
     * `by` delegation, which doesn't forward the SDK interface's Java default methods.
     */
    private fun intercepting(
        real: DynamoDbClient,
        override: (String, Array<Any?>) -> Any?,
    ): DynamoDbClient =
        java.lang.reflect.Proxy.newProxyInstance(
            DynamoDbClient::class.java.classLoader,
            arrayOf(DynamoDbClient::class.java),
        ) { _, method, args ->
            val a = args ?: emptyArray()
            override(method.name, a) ?: try {
                method.invoke(real, *a)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as DynamoDbClient

    private fun freshToken() = "test-" + java.util.UUID.randomUUID().toString().take(12)

    private fun isPutTo(
        name: String,
        args: Array<Any?>,
        tablePrefix: String,
    ) = name == "putItem" && (args.getOrNull(0) as? PutItemRequest)?.tableName()?.startsWith(tablePrefix) == true

    private fun storedPayload(
        token: String,
        msgId: String,
    ): String? {
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        return client.use {
            it.getItem(
                GetItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId)))
                    .consistentRead(true)
                    .build(),
            ).item()["payload"]?.s()
        }
    }

    private fun hasReceivedId(
        token: String,
        msgId: String,
    ): Boolean {
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        return client.use {
            it.getItem(
                GetItemRequest.builder()
                    .tableName("test_received_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId)))
                    .consistentRead(true)
                    .build(),
            ).hasItem()
        }
    }

    @AfterTest
    fun closeStores() {
        stores.forEach { it.close() }
        stores.clear()
    }

    @Test
    fun `post then drain round trip`() {
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("hello"), "msg-1"))
        assertEquals(listOf(JsonPrimitive("hello")), state.drain(token))
    }

    @Test
    fun `drain is non-destructive`() {
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("hello"), "msg-1")
        assertEquals(1, state.drain(token)?.size)
        assertEquals(1, state.drain(token)?.size)
    }

    @Test
    fun `deleteById removes only that message`() {
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("a"), "msg-a")
        state.post(token, JsonPrimitive("b"), "msg-b")
        assertTrue(state.deleteById(token, "msg-a"))
        assertEquals(listOf(JsonPrimitive("b")), state.drain(token))
    }

    @Test
    fun `deleteById on a token with no cached depth entry does not throw`() {
        // Regression guard for decrementDepth() against a token whose depth cache was never
        // seeded (e.g. a message written by something other than this process's post() calls) -
        // computeIfPresent must no-op rather than error or fabricate a negative count.
        val state = store()
        val token = freshToken()
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        client.putItem(
            PutItemRequest.builder()
                .tableName("test_messages_${tableCounter - 1}")
                .item(
                    mapOf(
                        "token" to AttributeValue.fromS(token),
                        "msgId" to AttributeValue.fromS("external-msg"),
                        "payload" to AttributeValue.fromS("\"seeded\""),
                        "postedAt" to AttributeValue.fromN(System.currentTimeMillis().toString()),
                        "expiresAt" to AttributeValue.fromN(((System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000) / 1000).toString()),
                    ),
                )
                .build(),
        )
        client.close()

        assertTrue(state.deleteById(token, "external-msg"))
        assertTrue(state.drain(token)?.isEmpty() == true)
    }

    @Test
    fun `deleteByIds removes a batch`() {
        val state = store()
        val token = freshToken()
        repeat(5) { i -> state.post(token, JsonPrimitive(i), "msg-$i") }
        val removed = state.deleteByIds(token, listOf("msg-0", "msg-1", "msg-2"))
        assertEquals(3, removed)
        assertEquals(2, state.drain(token)?.size)
    }

    @Test
    fun `tokens are isolated`() {
        val state = store()
        val tokenA = freshToken()
        val tokenB = freshToken()
        state.post(tokenA, JsonPrimitive("a"), "msg-1")
        assertEquals(1, state.drain(tokenA)?.size)
        assertTrue(state.drain(tokenB)?.isEmpty() == true)
    }

    @Test
    fun `drain returns messages in posted order`() {
        val state = store()
        val token = freshToken()
        // Primary key is (token, msgId), not (token, postedAt) - drain() relies on the
        // postedAt-index GSI for ordering rather than the base table's own key schema, so this
        // is the one behavior that doesn't fall out "for free" the way it did for Postgres/Redis,
        // and is worth its own explicit test.
        repeat(10) { i -> state.post(token, JsonPrimitive(i), "msg-$i") }
        assertEquals((0..9).map { JsonPrimitive(it) }, state.drain(token))
    }

    @Test
    fun `post writes the message and its idempotency record`() {
        // Verify both rows land, directly against the tables rather than through drain()/post()'s
        // own dedup check, so this doesn't just re-test itself.
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("both"), "msg-both"))

        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val message =
            client.getItem(
                GetItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS("msg-both")))
                    .consistentRead(true)
                    .build(),
            )
        val receivedId =
            client.getItem(
                GetItemRequest.builder()
                    .tableName("test_received_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS("msg-both")))
                    .consistentRead(true)
                    .build(),
            )
        client.close()

        assertTrue(message.hasItem(), "message row must exist after a successful post")
        assertTrue(receivedId.hasItem(), "receivedIds row must exist alongside it")
    }

    @Test
    fun `duplicate post with same msgId is a no-op`() {
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("first"), "msg-1"))
        assertTrue(state.post(token, JsonPrimitive("retry"), "msg-1"))
        // Only the original payload should be stored - the retry must not overwrite or duplicate.
        assertEquals(listOf(JsonPrimitive("first")), state.drain(token))
    }

    @Test
    fun `retry after delete is still recognized as a duplicate`() {
        // This is the case the receivedIds table exists for: idempotency must outlive delivery,
        // otherwise a retried PUT after the original was drained+deleted would re-insert a
        // message the recipient's ratchet has already advanced past.
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("first"), "msg-1"))
        state.deleteById(token, "msg-1")
        assertTrue(state.drain(token)?.isEmpty() == true)

        assertTrue(state.post(token, JsonPrimitive("retry"), "msg-1"))
        assertTrue(state.drain(token)?.isEmpty() == true, "retried duplicate must not resurrect the deleted message")
    }

    @Test
    fun `queue depth guard rejects posts past the limit`() {
        val state = store()
        val token = freshToken()

        // Seed the queue directly via PutItem (bypassing the in-process rate limiter, which caps
        // far below MAX_QUEUE_DEPTH) so this test isolates the depth guard specifically.
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val now = System.currentTimeMillis()
        val farFutureSec = (now + 7L * 24 * 60 * 60 * 1000) / 1000
        repeat(TEST_MAX_QUEUE_DEPTH) { i ->
            client.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        mapOf(
                            "token" to AttributeValue.fromS(token),
                            "msgId" to AttributeValue.fromS("seed-$i"),
                            "payload" to AttributeValue.fromS("\"seed\""),
                            "postedAt" to AttributeValue.fromN((now + i).toString()),
                            "expiresAt" to AttributeValue.fromN(farFutureSec.toString()),
                        ),
                    )
                    .build(),
            )
        }
        client.close()

        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg"))
    }

    @Test
    fun `drain does not return expired rows`() {
        // Regression guard: an expired-but-not-yet-TTL-swept row must not be returned -
        // otherwise a dual-write shadow-read comparison would see it in DynamoDB but not in
        // Redis (whose keys vanish atomically via native TTL) and log a false-positive parity
        // mismatch. DynamoDB Local doesn't actually run the background TTL sweep, so this also
        // covers the real-world up-to-48h TTL deletion lag in production.
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("soon-expired"), "msg-1")

        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        client.putItem(
            PutItemRequest.builder()
                .tableName("test_messages_${tableCounter - 1}")
                .item(
                    mapOf(
                        "token" to AttributeValue.fromS(token),
                        "msgId" to AttributeValue.fromS("msg-1"),
                        "payload" to AttributeValue.fromS("\"soon-expired\""),
                        "postedAt" to AttributeValue.fromN(System.currentTimeMillis().toString()),
                        "expiresAt" to AttributeValue.fromN(((System.currentTimeMillis() - 1000) / 1000).toString()),
                    ),
                )
                .build(),
        )
        client.close()

        assertTrue(state.drain(token)?.isEmpty() == true, "expired row must not be returned even before the TTL sweep runs")
    }

    @Test
    fun `queue depth guard ignores expired rows`() {
        // Regression guard: expired-but-not-yet-TTL-swept rows must not count toward the depth
        // guard, otherwise a legitimate post could be spuriously rejected for up to 48h (the
        // real DynamoDB TTL deletion lag) after the queue's actual live depth has dropped.
        val state = store()
        val token = freshToken()

        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val past = System.currentTimeMillis() - 1000
        repeat(TEST_MAX_QUEUE_DEPTH) { i ->
            client.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        mapOf(
                            "token" to AttributeValue.fromS(token),
                            "msgId" to AttributeValue.fromS("expired-$i"),
                            "payload" to AttributeValue.fromS("\"expired\""),
                            "postedAt" to AttributeValue.fromN((past - i).toString()),
                            "expiresAt" to AttributeValue.fromN((past / 1000).toString()),
                        ),
                    )
                    .build(),
            )
        }
        client.close()

        assertTrue(state.post(token, JsonPrimitive("fresh"), "fresh-msg"), "expired rows must not count toward depth")
    }

    @Test
    fun `concurrent posts to the same token are not lost`() {
        val state = store()
        val token = freshToken()
        val n = 50
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(n)
        repeat(n) { i ->
            pool.submit {
                try {
                    state.post(token, JsonPrimitive(i), "msg-$i")
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(n, state.drain(token)?.size)
    }

    @Test
    fun `post without msgId does not dedupe across calls`() {
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("one")))
        assertTrue(state.post(token, JsonPrimitive("two")))
        assertEquals(2, state.drain(token)?.size)
    }

    @Test
    fun `concurrent posts at the depth limit boundary do not overshoot`() {
        // Regression guard for the non-atomic-depth-check bug: seed the queue to exactly
        // limit-1, then fire a burst of concurrent posts at the same token. Without the
        // per-token lock serializing the depth-check-then-insert sequence, multiple posts can
        // all observe "one slot free" and all insert, overshooting MAX_QUEUE_DEPTH.
        val state = store()
        val token = freshToken()

        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val now = System.currentTimeMillis()
        val farFutureSec = (now + 7L * 24 * 60 * 60 * 1000) / 1000
        repeat(TEST_MAX_QUEUE_DEPTH - 1) { i ->
            client.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        mapOf(
                            "token" to AttributeValue.fromS(token),
                            "msgId" to AttributeValue.fromS("seed-$i"),
                            "payload" to AttributeValue.fromS("\"seed\""),
                            "postedAt" to AttributeValue.fromN((now + i).toString()),
                            "expiresAt" to AttributeValue.fromN(farFutureSec.toString()),
                        ),
                    )
                    .build(),
            )
        }
        client.close()

        val n = 10
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(n)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(n) { i ->
            pool.submit {
                try {
                    if (state.post(token, JsonPrimitive(i), "race-$i")) accepted.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        // Exactly one of the racing posts should have claimed the single free slot.
        assertEquals(1, accepted.get(), "only one concurrent post should fit in the single free slot")
    }

    @Test
    fun `retry after a rejected post succeeds once space frees up`() {
        // Regression guard for the "failed post consumes the msgId" bug: a post rejected for
        // being over the depth limit must not leave a receivedIds entry behind, or a client
        // retry of that same msgId (which the HTTP 429 response invites) would be silently
        // swallowed as an idempotent duplicate instead of actually enqueuing.
        val state = store()
        val token = freshToken()

        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val now = System.currentTimeMillis()
        val farFutureSec = (now + 7L * 24 * 60 * 60 * 1000) / 1000
        val seedKeys = mutableListOf<Map<String, AttributeValue>>()
        repeat(TEST_MAX_QUEUE_DEPTH) { i ->
            val key =
                mapOf(
                    "token" to AttributeValue.fromS(token),
                    "msgId" to AttributeValue.fromS("seed-$i"),
                )
            seedKeys.add(key)
            client.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        key +
                            mapOf(
                                "payload" to AttributeValue.fromS("\"seed\""),
                                "postedAt" to AttributeValue.fromN((now + i).toString()),
                                "expiresAt" to AttributeValue.fromN(farFutureSec.toString()),
                            ),
                    )
                    .build(),
            )
        }

        assertFalse(state.post(token, JsonPrimitive("overflow"), "retry-msg"), "queue is full, post must be rejected")

        // Free up a slot, then retry the same msgId that was just rejected.
        state.deleteById(token, "seed-0")

        assertTrue(
            state.post(token, JsonPrimitive("overflow"), "retry-msg"),
            "retry of a msgId rejected for depth (not duplication) must actually enqueue once space is free",
        )

        // drain() only returns the oldest MAX_MESSAGES_PER_POLL of a 10000-deep queue, so the
        // retried message (posted last, hence sorted last) wouldn't show up there - check the
        // table directly instead.
        val stored =
            client.getItem(
                GetItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS("retry-msg")))
                    .consistentRead(true)
                    .build(),
            )
        assertTrue(stored.hasItem(), "the retried message must actually be in the mailbox, not silently dropped")
        client.close()
    }

    private fun seedFullQueue(token: String) {
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val now = System.currentTimeMillis()
        val farFutureSec = (now + 7L * 24 * 60 * 60 * 1000) / 1000
        repeat(TEST_MAX_QUEUE_DEPTH) { i ->
            client.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        mapOf(
                            "token" to AttributeValue.fromS(token),
                            "msgId" to AttributeValue.fromS("seed-$i"),
                            "payload" to AttributeValue.fromS("\"seed\""),
                            "postedAt" to AttributeValue.fromN((now + i).toString()),
                            "expiresAt" to AttributeValue.fromN(farFutureSec.toString()),
                        ),
                    )
                    .build(),
            )
        }
        client.close()
    }

    @Test
    fun `repeated deleteById of the same message only frees one depth-guard slot`() {
        // Regression guard: DeleteItem gives no signal about whether an item actually existed
        // unless asked for via ReturnValues - a naive decrement-on-every-call would let a
        // retried delete-ack for the same id "free" more capacity than was ever actually freed,
        // letting post() accept messages past MAX_QUEUE_DEPTH rather than just rejecting early.
        val state = store()
        val token = freshToken()
        seedFullQueue(token)

        // Seeds the in-process depth cache to the true count (10000) as a side effect.
        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg"))

        // Retry the same delete-ack several times, as a flaky client might.
        repeat(5) { state.deleteById(token, "seed-0") }

        // Only one real slot was freed - exactly one post should now fit, not five.
        assertTrue(state.post(token, JsonPrimitive("a"), "after-1"))
        assertFalse(state.post(token, JsonPrimitive("b"), "after-2"))
    }

    @Test
    fun `retried deleteByIds for already-removed ids does not double-free depth-guard slots`() {
        val state = store()
        val token = freshToken()
        seedFullQueue(token)

        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg"))

        assertEquals(2, state.deleteByIds(token, listOf("seed-0", "seed-1")))
        // Retry of the same delete-ack, as a client might do after a lost response.
        assertEquals(2, state.deleteByIds(token, listOf("seed-0", "seed-1")))

        // Exactly two real slots were freed - two posts should fit, not four.
        assertTrue(state.post(token, JsonPrimitive("a"), "after-1"))
        assertTrue(state.post(token, JsonPrimitive("b"), "after-2"))
        assertFalse(state.post(token, JsonPrimitive("c"), "after-3"))
    }

    @Test
    fun `deleteByIds that fails after a partial batch still frees the deleted slots`() {
        // First BatchWriteItem chunk (25 ids) lands; every later call comes back fully
        // unprocessed until the retry budget runs out. The 25 deleted messages are really gone,
        // so post() must see that capacity rather than a stale cached count of 10000.
        var writeCalls = 0
        val state =
            store { real ->
                intercepting(real) { name, args ->
                    if (name != "batchWriteItem" || writeCalls++ == 0) return@intercepting null
                    val request = args[0] as BatchWriteItemRequest
                    BatchWriteItemResponse.builder().unprocessedItems(request.requestItems()).build()
                }
            }
        val token = freshToken()
        seedFullQueue(token)
        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg")) // seeds the cache

        assertFailsWith<IllegalStateException> { state.deleteByIds(token, (0 until 30).map { "seed-$it" }) }

        repeat(25) { assertTrue(state.post(token, JsonPrimitive(it), "after-$it"), "slot ${it + 1} of 25 freed") }
        assertFalse(state.post(token, JsonPrimitive("full"), "after-full"))
    }

    @Test
    fun `a post that re-seeds the depth cache mid-delete does not cause an undercount`() {
        // The delete has committed in DynamoDB but deleteById hasn't returned yet. A post() at
        // that moment on a cold cache must not re-seed from the reduced count and then have the
        // delete's decrement subtract the same message again, which would admit an extra post
        // past MAX_QUEUE_DEPTH. Holding the token lock across the delete makes the post wait.
        val pool = Executors.newSingleThreadExecutor()
        lateinit var state: DynamoMailboxState
        val token = freshToken()
        var racedPost: java.util.concurrent.Future<Boolean>? = null
        state =
            store { real ->
                intercepting(real) { name, args ->
                    if (name != "deleteItem") return@intercepting null
                    real.deleteItem(args[0] as DeleteItemRequest).also {
                        val f = pool.submit<Boolean> { state.post(token, JsonPrimitive("raced"), "raced") }
                        racedPost = f
                        try {
                            f.get(2, TimeUnit.SECONDS) // completes here only if post() isn't blocked
                        } catch (_: TimeoutException) {
                        }
                    }
                }
            }
        seedFullQueue(token) // depth cache stays cold: nothing has posted yet

        state.deleteById(token, "seed-0")
        assertTrue(racedPost!!.get(30, TimeUnit.SECONDS), "the raced post fills the one freed slot")
        pool.shutdown()

        assertFalse(state.post(token, JsonPrimitive("extra"), "extra"), "queue is full again at MAX_QUEUE_DEPTH")
    }

    @Test
    fun `a post whose write commits but then throws does not leave the depth count short`() {
        // e.g. a client-side timeout after DynamoDB applied the message write. Nothing later
        // increments for that row, so a cache that skipped the increment would stay one short for
        // good and admit a post past MAX_QUEUE_DEPTH.
        var failNextWrite = false
        val state =
            store { real ->
                intercepting(real) { name, args ->
                    if (!failNextWrite || !isPutTo(name, args, "test_messages_")) return@intercepting null
                    failNextWrite = false
                    real.putItem(args[0] as PutItemRequest)
                    throw SdkClientException.create("simulated timeout after commit")
                }
            }
        val token = freshToken()
        seedFullQueue(token)
        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg")) // seeds the cache
        state.deleteById(token, "seed-0") // one free slot

        failNextWrite = true
        assertFailsWith<SdkClientException> { state.post(token, JsonPrimitive("x"), "x") }
        // The landed row has no idempotency record yet, so the retry reaches the depth check,
        // where that row already counts.
        assertFalse(state.post(token, JsonPrimitive("x"), "x"), "the landed row filled the last slot")

        assertFalse(state.post(token, JsonPrimitive("y"), "y"), "the committed write filled the last slot")
    }

    @Test
    fun `deleteByIds with duplicate ids deletes once and frees one slot each`() {
        val state = store()
        val token = freshToken()
        seedFullQueue(token)

        // DynamoDB rejects batch requests with duplicate keys; the store must dedupe.
        assertEquals(1, state.deleteByIds(token, listOf("seed-0", "seed-0", "seed-0")))
        assertTrue(state.post(token, JsonPrimitive("a"), "after-1"))
        assertFalse(state.post(token, JsonPrimitive("b"), "after-2"))
    }

    @Test
    fun `TTL is enabled on a pre-existing table that lacks it`() {
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val suffix = "preexisting_" + java.util.UUID.randomUUID().toString().take(8)
        val messages = "test_messages_$suffix"
        val received = "test_received_$suffix"
        for (table in listOf(messages, received)) {
            client.createTable(
                CreateTableRequest.builder()
                    .tableName(table)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("token").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("msgId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("postedAt").attributeType(ScalarAttributeType.N).build(),
                    )
                    .keySchema(
                        KeySchemaElement.builder().attributeName("token").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("msgId").keyType(KeyType.RANGE).build(),
                    )
                    .apply {
                        if (table == messages) {
                            globalSecondaryIndexes(
                                GlobalSecondaryIndex.builder()
                                    .indexName("postedAt-index")
                                    .keySchema(
                                        KeySchemaElement.builder().attributeName("token").keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName("postedAt").keyType(KeyType.RANGE).build(),
                                    )
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            )
                        } else {
                            attributeDefinitions(
                                AttributeDefinition.builder().attributeName("token").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder().attributeName("msgId").attributeType(ScalarAttributeType.S).build(),
                            )
                        }
                    }
                    .build(),
            )
        }
        stores += DynamoMailboxState(client, messagesTable = messages, receivedIdsTable = received)

        for (table in listOf(messages, received)) {
            val status = client.describeTimeToLive { it.tableName(table) }.timeToLiveDescription().timeToLiveStatus()
            assertTrue(status == TimeToLiveStatus.ENABLED || status == TimeToLiveStatus.ENABLING, "$table TTL is $status")
        }
    }

    @Test
    fun `a message write that fails leaves no idempotency record and the retry delivers it`() {
        var failNextWrite = true
        val state =
            store { real ->
                intercepting(real) { name, args ->
                    if (!failNextWrite || !isPutTo(name, args, "test_messages_")) return@intercepting null
                    failNextWrite = false
                    throw SdkClientException.create("simulated failure before commit")
                }
            }
        val token = freshToken()

        assertFailsWith<SdkClientException> { state.post(token, JsonPrimitive("x"), "x") }
        assertFalse(hasReceivedId(token, "x"), "no idempotency record for a message that was never stored")

        assertTrue(state.post(token, JsonPrimitive("x"), "x"))
        assertEquals(listOf(JsonPrimitive("x")), state.drain(token))
    }

    @Test
    fun `a failed idempotency write after the message write is repaired by the retry`() {
        // The message lands but its receivedIds record doesn't. The retry must keep the original
        // row (not overwrite or duplicate it), write the record, and not count the row twice.
        var failNextWrite = false
        val state =
            store { real ->
                intercepting(real) { name, args ->
                    if (!failNextWrite || !isPutTo(name, args, "test_received_")) return@intercepting null
                    failNextWrite = false
                    throw SdkClientException.create("simulated idempotency write failure")
                }
            }
        val token = freshToken()
        seedFullQueue(token)
        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg")) // seeds the cache
        // Two free slots: the orphaned row counts toward the depth check, so with only one the
        // retry would (correctly) get a 429 rather than reach the repair path.
        state.deleteById(token, "seed-0")
        state.deleteById(token, "seed-1")

        failNextWrite = true
        assertFailsWith<SdkClientException> { state.post(token, JsonPrimitive("first"), "x") }
        assertFalse(hasReceivedId(token, "x"))

        assertTrue(state.post(token, JsonPrimitive("retry"), "x"))
        assertTrue(hasReceivedId(token, "x"), "the retry writes the missing idempotency record")
        assertEquals("\"first\"", storedPayload(token, "x"), "the retry must not overwrite the original row")

        assertTrue(state.post(token, JsonPrimitive("y"), "y"))
        assertFalse(state.post(token, JsonPrimitive("z"), "z"), "x was counted once, so y filled the last slot")
    }

    @Test
    fun `a failed idempotency write followed by a restart is repaired and queued once`() {
        // As above, but the process dies before the retry, so depthCounts is lost and re-seeded
        // from the table: the original payload must survive, the record is repaired, the row is queued once.
        var failNextWrite = true
        val first =
            store { real ->
                intercepting(real) { name, args ->
                    if (!failNextWrite || !isPutTo(name, args, "test_received_")) return@intercepting null
                    failNextWrite = false
                    throw SdkClientException.create("simulated idempotency write failure")
                }
            }
        val token = freshToken()
        assertFailsWith<SdkClientException> { first.post(token, JsonPrimitive("first"), "x") }
        assertFalse(hasReceivedId(token, "x"))

        // "Restart": a new instance over the same tables, with an empty depth cache.
        val suffix = tableCounter - 1
        val restarted =
            DynamoMailboxState(
                createDynamoDbClient("test", "test", "us-east-1", endpoint()),
                messagesTable = "test_messages_$suffix",
                receivedIdsTable = "test_received_$suffix",
            ).also { stores.add(it) }

        assertTrue(restarted.post(token, JsonPrimitive("retry"), "x"))
        assertTrue(hasReceivedId(token, "x"), "the retry writes the missing idempotency record")
        assertEquals("\"first\"", storedPayload(token, "x"), "the retry must not overwrite the original row")
        assertEquals(listOf(JsonPrimitive("first")), restarted.drain(token), "the row is queued exactly once")
    }

    @Test
    fun `an SDK retry that finds its own landed message write keeps the depth count exact`() {
        // The SDK retries a PutItem internally after e.g. a timeout; if the first attempt landed,
        // the retry fails the attribute_not_exists condition for a row this very call wrote.
        var failNextWrite = false
        val state =
            store { real ->
                intercepting(real) { name, args ->
                    if (!failNextWrite || !isPutTo(name, args, "test_messages_")) return@intercepting null
                    failNextWrite = false
                    real.putItem(args[0] as PutItemRequest)
                    throw ConditionalCheckFailedException.builder().message("simulated SDK retry").build()
                }
            }
        val token = freshToken()
        seedFullQueue(token)
        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg")) // seeds the cache
        state.deleteById(token, "seed-0") // one free slot

        failNextWrite = true
        assertTrue(state.post(token, JsonPrimitive("x"), "x"))
        assertTrue(hasReceivedId(token, "x"), "the idempotency record is still written")

        assertFalse(state.post(token, JsonPrimitive("y"), "y"), "the landed write filled the last slot")
    }

    @Test
    fun `a msgId whose expired row the TTL sweep has not removed yet is stored again`() {
        val state = store()
        val token = freshToken()
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        client.use {
            it.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        mapOf(
                            "token" to AttributeValue.fromS(token),
                            "msgId" to AttributeValue.fromS("reused"),
                            "payload" to AttributeValue.fromS("\"stale\""),
                            "postedAt" to AttributeValue.fromN("1"),
                            "expiresAt" to AttributeValue.fromN("1"),
                        ),
                    )
                    .build(),
            )
        }

        assertTrue(state.post(token, JsonPrimitive("fresh"), "reused"))
        assertEquals(listOf(JsonPrimitive("fresh")), state.drain(token))
    }

    private fun rawItem(
        token: String,
        msgId: String,
    ): Map<String, AttributeValue> =
        createDynamoDbClient("test", "test", "us-east-1", endpoint()).use {
            it.getItem(
                GetItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId)))
                    .consistentRead(true)
                    .build(),
            ).item()
        }

    // DynamoDB's item size rules: attribute name bytes plus value bytes, numbers about one byte per
    // two significant digits plus one. Checked against real AWS consumed capacity: an item this
    // formula puts at 1024 B costs 1 write unit, and 1030 B costs 2, on the table and its GSI alike.
    private fun itemSize(item: Map<String, AttributeValue>): Int =
        item.entries.sumOf { (name, value) ->
            name.toByteArray().size +
                when {
                    value.s() != null -> value.s().toByteArray().size
                    value.b() != null -> value.b().asByteArray().size
                    else -> (value.n().trimStart('-', '0').replace(".", "").length + 1) / 2 + 1
                }
        }

    // A 32-hex-char routing token, like real ones (16 bytes).
    private fun realisticToken() = java.util.UUID.randomUUID().toString().replace("-", "")

    @Test
    fun `compact location messages drain byte-identically and fit in one write unit`() {
        val state = store(compactPayloads = true)
        val token = realisticToken()
        val messages = RealMessages.locations(20)
        val sizes =
            messages.map { message ->
                assertTrue(state.post(token, RealMessages.wire(message), message.msgId))
                val item = rawItem(token, message.msgId)
                assertTrue("c" in item && "payload" !in item, "stored compact: ${item.keys}")
                itemSize(item)
            }
        assertEquals(messages.map { RealMessages.wire(it).toString() }, state.drain(token)!!.map { it.toString() })
        // ~780 B today; the margin leaves room for a somewhat larger envelope.
        assertTrue(sizes.max() <= 900, "item sizes $sizes")
    }

    @Test
    fun `with the flag off location messages are stored as JSON`() {
        val state = store()
        val token = freshToken()
        val message = RealMessages.locations(1).single()
        assertTrue(state.post(token, RealMessages.wire(message), message.msgId))
        assertTrue("payload" in rawItem(token, message.msgId))
        assertEquals(listOf(RealMessages.wire(message)), state.drain(token))
    }

    @Test
    fun `legacy and compact items drain together in posted order`() {
        val state = store(compactPayloads = true)
        val token = freshToken()
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        client.use {
            it.putItem(
                PutItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .item(
                        mapOf(
                            "token" to AttributeValue.fromS(token),
                            "msgId" to AttributeValue.fromS("legacy"),
                            "payload" to AttributeValue.fromS("\"legacy\""),
                            "postedAt" to AttributeValue.fromN((System.currentTimeMillis() - 1000).toString()),
                            "expiresAt" to AttributeValue.fromN((System.currentTimeMillis() / 1000 + 3600).toString()),
                        ),
                    )
                    .build(),
            )
        }
        val (first, second) = RealMessages.locations(2)
        assertTrue(state.post(token, RealMessages.wire(first), first.msgId))
        Thread.sleep(2)
        assertTrue(state.post(token, JsonPrimitive("not compactable"), "plain"))
        Thread.sleep(2)
        assertTrue(state.post(token, RealMessages.wire(second), second.msgId))

        assertEquals(
            listOf(JsonPrimitive("legacy"), RealMessages.wire(first), JsonPrimitive("not compactable"), RealMessages.wire(second)),
            state.drain(token),
        )
    }

    @Test
    fun `a malformed item is skipped without failing the poll`() {
        val state = store(compactPayloads = true)
        val token = freshToken()
        val (first, second) = RealMessages.locations(2)
        assertTrue(state.post(token, RealMessages.wire(first), first.msgId))
        val now = System.currentTimeMillis()
        val bad =
            mapOf(
                "half-compact" to mapOf("c" to AttributeValue.fromB(SdkBytes.fromByteArray(byteArrayOf(1)))),
                "v as string" to
                    mapOf(
                        "v" to AttributeValue.fromS("1"),
                        "e" to AttributeValue.fromB(SdkBytes.fromByteArray(byteArrayOf(1))),
                        "c" to AttributeValue.fromB(SdkBytes.fromByteArray(byteArrayOf(1))),
                    ),
                "no payload" to emptyMap(),
                "unparsable payload" to mapOf("payload" to AttributeValue.fromS("{not json")),
            )
        createDynamoDbClient("test", "test", "us-east-1", endpoint()).use { client ->
            bad.entries.forEachIndexed { i, (name, attrs) ->
                client.putItem(
                    PutItemRequest.builder()
                        .tableName("test_messages_${tableCounter - 1}")
                        .item(
                            attrs +
                                mapOf(
                                    "token" to AttributeValue.fromS(token),
                                    "msgId" to AttributeValue.fromS("bad-$i-$name"),
                                    "postedAt" to AttributeValue.fromN((now + i + 1).toString()),
                                    "expiresAt" to AttributeValue.fromN((now / 1000 + 3600).toString()),
                                ),
                        )
                        .build(),
                )
            }
        }
        Thread.sleep(10)
        assertTrue(state.post(token, RealMessages.wire(second), second.msgId))
        assertEquals(listOf(RealMessages.wire(first), RealMessages.wire(second)), state.drain(token))
    }

    @Test
    fun `compact items are acked like any other`() {
        val state = store(compactPayloads = true)
        val token = freshToken()
        val messages = RealMessages.locations(3)
        messages.forEach { assertTrue(state.post(token, RealMessages.wire(it), it.msgId)) }

        state.deleteById(token, messages[0].msgId)
        state.deleteByIds(token, listOf(messages[1].msgId))
        assertEquals(listOf(RealMessages.wire(messages[2])), state.drain(token))
    }

    @Test
    fun `a compact message round-trips through the HTTP API and decrypts`() =
        io.ktor.server.testing.testApplication {
            val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initMsg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
            val alice = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, DhPublicKey(qr.ekPub.toByteArray()))
            val (_, message) = Session.encryptMessage(alice, MessagePlaintext.Location(lat = 1.5, lng = 2.5, acc = 3.0, ts = 4L))
            val state = store(compactPayloads = true)
            application { module(ServerState(mailbox = state)) }
            val token = alice.sendToken.toHex()

            val put =
                client.put("/inbox/$token/${message.msgId}") {
                    contentType(ContentType.Application.Json)
                    setBody(RealMessages.wire(message).toString())
                }
            assertEquals(HttpStatusCode.NoContent, put.status)
            val item = rawItem(token, message.msgId)
            assertTrue("c" in item && "payload" !in item, "stored compact: ${item.keys}")

            val polled = Json.parseToJsonElement(client.get("/inbox/$token").bodyAsText()).jsonArray
            val received = assertIs<EncryptedMessagePayload>(wireJson.decodeFromJsonElement<MailboxPayload>(polled.single()))
            val (_, plaintext) = assertIs<ReceivedMessage.Delivered>(Session.decryptMessage(bobSession, received))
            val location = assertIs<MessagePlaintext.Location>(plaintext)
            assertEquals(1.5 to 2.5, location.lat to location.lng)
        }

    private val wireJson =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }
}
