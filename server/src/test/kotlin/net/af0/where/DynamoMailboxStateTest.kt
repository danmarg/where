package net.af0.where

import kotlinx.serialization.json.JsonPrimitive
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.CancellationReason
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private fun store(): DynamoMailboxState {
        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val suffix = tableCounter++
        return DynamoMailboxState(
            client,
            messagesTable = "test_messages_$suffix",
            receivedIdsTable = "test_received_$suffix",
        ).also { stores.add(it) }
    }

    private fun freshToken() = "test-" + java.util.UUID.randomUUID().toString().take(12)

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
    fun `post writes the message and its idempotency record atomically`() {
        // Regression guard: the message write and the receivedIds write happen inside one
        // TransactWriteItems call specifically so a crash/AWS error can never leave one without
        // the other. Verify both rows land together, directly against the tables rather than
        // through drain()/post()'s own dedup check, so this doesn't just re-test itself.
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("atomic"), "msg-atomic"))

        val client = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val message =
            client.getItem(
                GetItemRequest.builder()
                    .tableName("test_messages_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS("msg-atomic")))
                    .consistentRead(true)
                    .build(),
            )
        val receivedId =
            client.getItem(
                GetItemRequest.builder()
                    .tableName("test_received_${tableCounter - 1}")
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS("msg-atomic")))
                    .consistentRead(true)
                    .build(),
            )
        client.close()

        assertTrue(message.hasItem(), "message row must exist after a successful post")
        assertTrue(receivedId.hasItem(), "receivedIds row must exist alongside it, not just after a separate call")
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
    fun `post rethrows a non-duplicate transaction cancellation instead of reporting false success`() {
        // Regression guard: TransactionCanceledException is not synonymous with "duplicate" -
        // contention, throttling, and other transaction failures cancel it too. Wrap the real
        // client so transactWriteItems always throws a cancellation whose receivedIds reason is
        // something other than ConditionalCheckFailed, and confirm post() propagates it as a
        // real failure rather than swallowing it and reporting success with nothing written.
        val realClient = createDynamoDbClient("test", "test", "us-east-1", endpoint())
        val suffix = tableCounter++
        val throttlingClient = ThrottlingCanceledDynamoDbClient(realClient)
        val state =
            DynamoMailboxState(
                throttlingClient,
                messagesTable = "test_messages_$suffix",
                receivedIdsTable = "test_received_$suffix",
            )
        stores.add(state)
        val token = freshToken()

        assertFailsWith<TransactionCanceledException> {
            state.post(token, JsonPrimitive("should not silently succeed"), "msg-1")
        }
    }
}

/** Makes every transactWriteItems() call fail with a cancellation that is NOT a duplicate. */
private class ThrottlingCanceledDynamoDbClient(
    private val delegate: DynamoDbClient,
) : DynamoDbClient by delegate {
    override fun transactWriteItems(request: TransactWriteItemsRequest): TransactWriteItemsResponse {
        throw TransactionCanceledException.builder()
            .message("Transaction cancelled, please refer cancellation reasons for specific reasons")
            .cancellationReasons(
                // Index 0 = messagesTable put (unconditioned): "None". Index 1 = receivedIds put
                // (conditioned): a non-ConditionalCheckFailed reason, simulating throttling/
                // contention rather than a genuine duplicate.
                CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("ThrottlingError").build(),
            )
            .build()
    }
}
