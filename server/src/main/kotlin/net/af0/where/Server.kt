package net.af0.where

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.delete
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ReturnValue
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.Select
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

// ---------------------------------------------------------------------------
// Server module
// ---------------------------------------------------------------------------

private val json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

private const val RATE_LIMIT_WINDOW_MS = 60 * 1000L
private const val MAX_BODY_BYTES = 4 * 1024
private const val POLL_BASELINE_LATENCY_MS = 50L
private const val MAILBOX_TTL_MS = 7 * 24 * 60 * 60 * 1000L

/** Maximum messages retained per token. Prevents unbounded memory growth from floods. */
private const val MAX_QUEUE_DEPTH = 10000

/** Maximum messages returned in a single poll request. */
private const val MAX_MESSAGES_PER_POLL = 50

/** Maximum POST requests per token within the rate-limit window.
 * Increased 10x to accommodate WAL retry bursts during reconnects (e.g. 20 friends x 50 retries).
 */
internal const val RATE_LIMIT_MAX_POSTS = 1000

/** Maximum GET requests per token within the rate-limit window. */
internal const val RATE_LIMIT_MAX_GETS = 2000

// ---------------------------------------------------------------------------
// In-process rate limiter (used by the in-memory store)
// ---------------------------------------------------------------------------

/**
 * Tracks per-token POST/GET counts and per-IP POST counts entirely in the JVM
 * process. Rate-limit counters are short-lived,
 * so keeping them out of the persistent store avoids a write per request.
 *
 * Thread-safe via ConcurrentHashMap + ConcurrentLinkedQueue; no locking needed
 * because we only need approximate counts (a few extra requests past the limit
 * are harmless, and missing a concurrent removal is safe).
 */
class InProcessRateLimiter {
    private val postTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val getTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val ipTimes = RequestKind.entries.associateWith { ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>() }

    fun checkPost(token: String): Boolean = check(postTimes, token, RATE_LIMIT_MAX_POSTS)

    fun checkGet(token: String): Boolean = check(getTimes, token, RATE_LIMIT_MAX_GETS)

    fun checkIp(
        ip: String,
        kind: RequestKind = RequestKind.WRITE,
    ): Boolean = check(ipTimes.getValue(kind), ip, kind.ipLimit)

    /**
     * Trims timestamps older than [windowMs] and drops keys whose queues are then empty, so
     * maps don't grow by one entry per distinct token/IP forever. A check() racing with the
     * removal may count into an orphaned queue; that loses at most one request's count, which
     * is within this limiter's approximate-by-design contract.
     */
    fun evict(windowMs: Long = RATE_LIMIT_WINDOW_MS) {
        val cutoff = System.currentTimeMillis() - windowMs
        for (map in listOf(postTimes, getTimes) + ipTimes.values) {
            for (key in map.keys) {
                map.computeIfPresent(key) { _, q ->
                    q.removeIf { it < cutoff }
                    q.takeUnless { it.isEmpty() }
                }
            }
        }
    }

    internal fun trackedKeyCount(): Int = postTimes.size + getTimes.size + ipTimes.values.sumOf { it.size }

    private fun check(
        map: ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>,
        key: String,
        limit: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val q = map.getOrPut(key) { ConcurrentLinkedQueue() }
        q.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
        if (q.size >= limit) return false
        q.add(now)
        return true
    }
}

/**
 * Per-IP request budgets per [RATE_LIMIT_WINDOW_MS], in separate buckets so heavy polling
 * can't starve posts. Generous because carrier-grade NAT puts many clients behind one IP;
 * these are estimates, not measured — revisit against real per-IP traffic.
 */
enum class RequestKind(val ipLimit: Int) {
    WRITE(2000),
    READ(4000),
    DELETE(4000),
}

/**
 * Fixed pool of monitors for per-token mutual exclusion. A map of one lock per token grows
 * with every distinct token ever seen and can't be safely pruned (a pruned lock could be
 * held while a new one is handed out); striping bounds memory at the cost of occasional
 * unrelated tokens sharing a stripe.
 */
internal class StripedLocks(stripes: Int = 256) {
    private val locks = Array(stripes) { Any() }

    operator fun get(key: String): Any = locks[Math.floorMod(key.hashCode(), locks.size)]
}

interface MailboxStore : AutoCloseable {
    fun checkIpRateLimit(
        ip: String,
        kind: RequestKind = RequestKind.WRITE,
    ): Boolean

    /**
     * Posts a [payload] to the inbox for [token].
     * Returns true if successful, false if rate-limited or mailbox full.
     */
    fun post(
        token: String,
        payload: JsonElement,
        msgId: String? = null,
    ): Boolean

    /**
     * Drains up to 50 messages from the inbox for [token].
     * Returns null if rate-limited.
     */
    fun drain(token: String): List<JsonElement>?

    /**
     * Deletes a specific message by [msgId]. Idempotent.
     */
    fun deleteById(
        token: String,
        msgId: String,
    ): Boolean

    /**
     * Deletes multiple messages by [msgIds]. Idempotent.
     */
    fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int

    /** Reclaim stale entries. No-op for implementations where the store handles expiry. */
    fun evict() {}

    override fun close() {}
}

/** Utility to help with testing eviction logic. */
fun MailboxStore.evictForTest(rateLimitWindowMs: Long) {
    if (this is InMemoryMailboxState) {
        evictWithParams(rateLimitWindowMs)
    }
}

// ---------------------------------------------------------------------------
// In-memory implementation (tests / local dev)
// ---------------------------------------------------------------------------

private data class MailboxEntry(val payload: JsonElement, val expiresAt: Long, val msgId: String? = null)

class InMemoryMailboxState(
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
) : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()
    private val receivedIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedIdsOrder = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val dummyQueue = ConcurrentLinkedQueue<MailboxEntry>()

    private val locks = StripedLocks()

    private fun getLock(token: String) = locks[token]

    override fun checkIpRateLimit(
        ip: String,
        kind: RequestKind,
    ) = limiter.checkIp(ip, kind)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean =
        synchronized(getLock(token)) {
            val now = System.currentTimeMillis()

            if (msgId != null) {
                val ids = receivedIds.getOrPut(token) { ConcurrentHashMap.newKeySet() }
                if (ids.contains(msgId)) return true
            }

            if (!limiter.checkPost(token)) return false

            val queue = mailboxes.getOrPut(token) { ConcurrentLinkedQueue() }
            queue.removeIf { it.expiresAt <= now }
            if (queue.size >= MAX_QUEUE_DEPTH) return false
            queue.add(MailboxEntry(payload, now + MAILBOX_TTL_MS, msgId))
            if (msgId != null) {
                receivedIds.getOrPut(token) { ConcurrentHashMap.newKeySet() }.add(msgId)
                receivedIdsOrder.getOrPut(token) { ConcurrentLinkedQueue() }.add(msgId)
            }
            return true
        }

    override fun drain(token: String): List<JsonElement>? {
        if (!limiter.checkGet(token)) return null
        val now = System.currentTimeMillis()
        val queue = mailboxes[token] ?: dummyQueue
        return queue.asSequence()
            .filter { it.expiresAt > now }
            .map { it.payload }
            .take(MAX_MESSAGES_PER_POLL)
            .toList()
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        val queue = mailboxes[token] ?: return false
        return queue.removeIf { it.msgId == msgId }
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        val queue = mailboxes[token] ?: return 0
        val initialSize = queue.size
        queue.removeIf { it.msgId in msgIds }
        return initialSize - queue.size
    }

    override fun evict() = evictWithParams(RATE_LIMIT_WINDOW_MS)

    // Kept for tests that need to drive eviction with a custom window.
    internal fun evictWithParams(rateLimitWindowMs: Long) {
        limiter.evict(rateLimitWindowMs)

        val now = System.currentTimeMillis()
        mailboxes.forEach { (token, _) ->
            mailboxes.computeIfPresent(token) { _, q ->
                q.removeIf { it.expiresAt <= now }
                if (q.isEmpty()) null else q
            }
        }

        receivedIds.forEach { (token, set) ->
            if (set.size > MAX_QUEUE_DEPTH) {
                val order = receivedIdsOrder[token]
                while (set.size > MAX_QUEUE_DEPTH && order != null) {
                    val oldest = order.poll() ?: break
                    set.remove(oldest)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DynamoDB implementation
// ---------------------------------------------------------------------------

/**
 * Builds a [DynamoDbClient] from explicit credentials rather than relying on the SDK's default
 * credential chain (instance metadata, profile files, etc). This app only ever runs on Fly with
 * static keys passed as secrets, so an explicit provider fails fast with a clear config error
 * instead of the chain silently trying (and failing) several irrelevant credential sources first.
 *
 * [endpointOverride] is for pointing at DynamoDB Local in tests; left null in production so the
 * SDK routes to the real regional endpoint.
 */
fun createDynamoDbClient(
    accessKeyId: String,
    secretAccessKey: String,
    region: String,
    endpointOverride: String? = null,
): DynamoDbClient =
    DynamoDbClient.builder()
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        .apply { endpointOverride?.let { endpointOverride(URI(it)) } }
        .build()

/**
 * True if [e] cancelled the message+receivedIds transaction specifically because the receivedIds
 * item (index 1 - see DynamoMailboxState.post()'s transactItems order) already existed, i.e. a
 * genuine duplicate. Any other cancellation reason (throttling, contention, etc.) is a real
 * failure and must not be swallowed as if it were one - see post()'s call site.
 */
internal fun isReceivedIdsConditionalCheckFailure(e: TransactionCanceledException): Boolean =
    e.cancellationReasons().getOrNull(1)?.code() == "ConditionalCheckFailed"

/**
 * DynamoDB-backed mailbox store. Two tables, mirroring the same split PostgresMailboxState used
 * and for the same reason: mailbox_received_ids must outlive message deletion, so idempotency
 * can't be folded into the messages table.
 *
 * Both tables use on-demand (PAY_PER_REQUEST) billing - pure per-operation cost with no idle
 * charge, unlike Neon's compute-hour billing which is what killed the Postgres attempt for this
 * poll-heavy traffic pattern. Both use DynamoDB's native TTL on `expiresAt`, so - like Firestore
 * would have - there's no manual eviction sweep to run at all; drain()/post() already filter
 * expiresAt > now in-query, so the TTL sweep's up-to-48h background deletion lag is
 * correctness-neutral, same reasoning already used for Postgres's evict().
 *
 * Primary key is (token, msgId) rather than (token, postedAt+msgId) specifically so
 * deleteById/deleteByIds - which only ever know msgId, not postedAt - are direct O(1)
 * DeleteItem calls with no secondary index or lookup needed. drain() needs postedAt order
 * though, so messagesTable also carries a [POSTED_AT_INDEX_NAME] GSI (token hash + postedAt
 * range, ALL projection) purely for that read path - see drain()'s doc for why a client-side
 * sort over the whole partition wasn't good enough at this app's actual traffic shape.
 *
 * post()'s dedup-check + depth-check + insert sequence is serialized per token via [locks],
 * the same pattern the deleted PostgresMailboxState used. That's sufficient (rather than a
 * DynamoDB transaction) because Fly runs this server as exactly one JVM (`max_machines_running
 * = 1` in fly.toml) - there is never a second writer to race against.
 *
 * The depth guard itself is backed by [depthCounts], an in-process cache of each token's live
 * message count, lazily seeded from one real (paginated, exact) count query and then maintained
 * by simple increment/decrement on post/delete - see currentDepth()'s doc for why a per-post
 * COUNT scan was too expensive under real sustained traffic (one party posting steadily while
 * the other is offline for hours/days - not just an abuse scenario) and why the cache's only
 * failure mode (drift from TTL-expired-but-undeleted rows) can't manifest inside the 7-day
 * window the app is actually designed to tolerate.
 */
class DynamoMailboxState(
    private val client: DynamoDbClient,
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
    private val messagesTable: String = "where_mailbox_messages",
    private val receivedIdsTable: String = "where_mailbox_received_ids",
) : MailboxStore {
    private val locks = StripedLocks()
    private val depthCounts = ConcurrentHashMap<String, Int>()

    private fun getLock(token: String) = locks[token]

    init {
        ensureTable(messagesTable, withPostedAtIndex = true)
        ensureTable(receivedIdsTable, withPostedAtIndex = false)
    }

    private fun ensureTable(
        tableName: String,
        withPostedAtIndex: Boolean,
    ) {
        val exists =
            try {
                client.describeTable { it.tableName(tableName) }
                true
            } catch (e: ResourceNotFoundException) {
                false
            }
        if (!exists) createTable(tableName, withPostedAtIndex)
        // Checked on every startup, not just after creation: a table created by hand, or by a
        // run that died before enabling TTL, would otherwise retain messages forever.
        ensureTtl(tableName)
    }

    private fun ensureTtl(tableName: String) {
        // Best-effort: if the deploy credentials lack DescribeTimeToLive/UpdateTimeToLive, warn
        // loudly rather than crash-looping the server on startup.
        try {
            val status = client.describeTimeToLive { it.tableName(tableName) }.timeToLiveDescription()?.timeToLiveStatus()
            if (status == TimeToLiveStatus.ENABLED || status == TimeToLiveStatus.ENABLING) return
            client.updateTimeToLive(
                UpdateTimeToLiveRequest.builder()
                    .tableName(tableName)
                    .timeToLiveSpecification(
                        TimeToLiveSpecification.builder().attributeName("expiresAt").enabled(true).build(),
                    )
                    .build(),
            )
        } catch (e: DynamoDbException) {
            LoggerFactory.getLogger(
                "Server",
            ).error("Could not verify/enable TTL on $tableName (${e::class.simpleName}); messages may never expire")
        }
    }

    private fun createTable(
        tableName: String,
        withPostedAtIndex: Boolean,
    ) {
        try {
            val attributeDefinitions =
                mutableListOf(
                    AttributeDefinition.builder().attributeName("token").attributeType(ScalarAttributeType.S).build(),
                    AttributeDefinition.builder().attributeName("msgId").attributeType(ScalarAttributeType.S).build(),
                )
            val createTableRequest =
                CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(
                        KeySchemaElement.builder().attributeName("token").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("msgId").keyType(KeyType.RANGE).build(),
                    )
            if (withPostedAtIndex) {
                attributeDefinitions.add(
                    AttributeDefinition.builder().attributeName("postedAt").attributeType(ScalarAttributeType.N).build(),
                )
                createTableRequest.globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName(POSTED_AT_INDEX_NAME)
                        .keySchema(
                            KeySchemaElement.builder().attributeName("token").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("postedAt").keyType(KeyType.RANGE).build(),
                        )
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build(),
                )
            }
            client.createTable(createTableRequest.attributeDefinitions(attributeDefinitions).build())
        } catch (e: ResourceInUseException) {
            // Another process/run already created it between our describeTable and createTable -
            // fall through to the waiter below, which is safe to call either way.
        }
        client.waiter().waitUntilTableExists { it.tableName(tableName) }
    }

    private companion object {
        // GSI on messagesTable only: token (hash) + postedAt (range), ALL projection - lets
        // drain() query in delivery order directly instead of scanning+sorting client-side.
        const val POSTED_AT_INDEX_NAME = "postedAt-index"
        const val MAX_BATCH_RETRIES = 8
        const val MAX_DEPTH_CACHE_ENTRIES = 50_000
    }

    override fun checkIpRateLimit(
        ip: String,
        kind: RequestKind,
    ) = limiter.checkIp(ip, kind)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        if (!limiter.checkPost(token)) return false
        synchronized(getLock(token)) {
            val now = System.currentTimeMillis()
            val expiresAt = (now + MAILBOX_TTL_MS) / 1000

            if (msgId != null) {
                val existing =
                    client.getItem(
                        GetItemRequest.builder()
                            .tableName(receivedIdsTable)
                            .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId)))
                            .consistentRead(true)
                            .build(),
                    )
                if (existing.hasItem()) {
                    // Already seen: idempotent no-op, matches DynamoMailboxState's receivedIds dedup check.
                    return true
                }
            }

            // Depth check happens before either write below, so a post that's rejected for being
            // over the limit leaves no trace in receivedIds - a retry of the same msgId (which the
            // HTTP layer's 429 response implicitly invites) still has a real message to enqueue,
            // rather than being silently swallowed by a dedup entry from the failed attempt.
            val depth = currentDepth(token, now)
            if (depth >= MAX_QUEUE_DEPTH) return false

            val messageItem =
                mapOf(
                    "token" to AttributeValue.fromS(token),
                    // A null msgId means "no idempotency requested" (never happens over the real
                    // HTTP API, which requires msgId in the path) - give it a unique key so it
                    // behaves like InMemoryMailboxState's queue (a fresh entry per post), not a
                    // dedup target.
                    "msgId" to AttributeValue.fromS(msgId ?: UUID.randomUUID().toString()),
                    "payload" to AttributeValue.fromS(payload.toString()),
                    "postedAt" to AttributeValue.fromN(now.toString()),
                    "expiresAt" to AttributeValue.fromN(expiresAt.toString()),
                )

            if (msgId != null) {
                // The message write and its receivedIds record must land together or not at all -
                // two independent PutItems left a window where a crash/AWS error between them could
                // leave a message durably stored with no idempotency record, so a client retry of
                // the same msgId would silently re-insert (a harmless overwrite, since messagesTable
                // is keyed on (token, msgId)) but still double-count it in depthCounts. TransactWriteItems
                // closes that window instead of narrowing it. The conditionExpression here is mostly
                // redundant with the GetItem check above (both run under the same per-token lock, the
                // only writer in this process) - it's the defense against exactly the crash case this
                // fixes: a prior attempt whose transaction actually committed just before this process
                // died, so a fresh attempt (this call, possibly in a restarted process) must still
                // recognize it as a duplicate rather than trusting an in-memory decision alone.
                try {
                    client.transactWriteItems(
                        TransactWriteItemsRequest.builder()
                            .transactItems(
                                TransactWriteItem.builder()
                                    .put(Put.builder().tableName(messagesTable).item(messageItem).build())
                                    .build(),
                                TransactWriteItem.builder()
                                    .put(
                                        Put.builder()
                                            .tableName(receivedIdsTable)
                                            .item(
                                                mapOf(
                                                    "token" to AttributeValue.fromS(token),
                                                    "msgId" to AttributeValue.fromS(msgId),
                                                    "expiresAt" to AttributeValue.fromN(expiresAt.toString()),
                                                ),
                                            )
                                            .conditionExpression("attribute_not_exists(msgId)")
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                } catch (e: TransactionCanceledException) {
                    // TransactionCanceledException isn't synonymous with "duplicate" - contention,
                    // throttling, and other transaction failures cancel it too, and those must
                    // propagate as a real failure rather than be reported to the HTTP layer as a
                    // false 204 (which would be silent message loss).
                    if (isReceivedIdsConditionalCheckFailure(e)) {
                        // Already seen: idempotent no-op, matches the check above.
                        return true
                    }
                    throw e
                }
            } else {
                client.putItem(PutItemRequest.builder().tableName(messagesTable).item(messageItem).build())
            }
            depthCounts[token] = depth + 1
            return true
        }
    }

    /**
     * Returns [token]'s current live (unexpired) message count, from [depthCounts] if cached or
     * by seeding it with one real paginated count query otherwise. Must be called while holding
     * [getLock] for [token], same as the rest of post()'s critical section.
     *
     * This replaces a per-post COUNT scan of the partition. That scan was cheap for the abuse
     * case it was originally written for (reject fast once already over the limit) but expensive
     * for a real, non-abusive one: one party posting steadily (e.g. every 30s while traveling)
     * while the other is offline for hours - every single post during that stretch would have
     * re-scanned the entire, growing backlog just to confirm it's still under the limit. The
     * cache turns that into one scan per token per process lifetime instead of one per post.
     *
     * Invariant: this cache is exact for the supported 7-day mailbox lifetime - within that
     * window every delete goes through deleteById/deleteByIds, which decrement it, so it can
     * never diverge from the true live count. It is *not* guaranteed exact past that window: a
     * message DynamoDB's native TTL sweep deletes (rather than the app) doesn't decrement the
     * cache, since TTL fires with no application hook. That can only happen to a message that's
     * already 7 days old, which is already past the "up to 7 days without ratcheting" bound the
     * app is designed around - a mailbox that old is expected to need a restart/re-pair anyway,
     * not to keep relying on an exact count.
     */
    private fun currentDepth(
        token: String,
        now: Long,
    ): Int = depthCounts.getOrPut(token) { queryLiveDepth(token, now) }

    private fun queryLiveDepth(
        token: String,
        now: Long,
    ): Int {
        var count = 0
        var lastKey: Map<String, AttributeValue>? = null
        do {
            val response =
                client.query(
                    QueryRequest.builder()
                        .tableName(messagesTable)
                        // "token" is a DynamoDB reserved keyword, so it can't appear bare in an
                        // expression - #tok aliases it via ExpressionAttributeNames.
                        .keyConditionExpression("#tok = :token")
                        .filterExpression("expiresAt > :now")
                        .expressionAttributeNames(mapOf("#tok" to "token"))
                        .expressionAttributeValues(
                            mapOf(
                                ":token" to AttributeValue.fromS(token),
                                ":now" to AttributeValue.fromN((now / 1000).toString()),
                            ),
                        )
                        .select(Select.COUNT)
                        .exclusiveStartKey(lastKey)
                        .build(),
                )
            count += response.count()
            lastKey = response.lastEvaluatedKey().takeIf { it.isNotEmpty() }
        } while (lastKey != null)
        return count
    }

    // Must be called while holding getLock(token) - see currentDepth()'s doc.
    private fun decrementDepth(
        token: String,
        by: Int,
    ) {
        depthCounts.computeIfPresent(token) { _, count ->
            (count - by).coerceAtLeast(0).takeIf { it > 0 }
        }
    }

    /**
     * Queries the [POSTED_AT_INDEX_NAME] GSI rather than the base table, so results come back
     * already in postedAt order via ScanIndexForward - no need to read the whole partition and
     * sort client-side. Limit is applied before FilterExpression on DynamoDB's side, so a page
     * can return fewer than [MAX_MESSAGES_PER_POLL] live items if some in it are expired; the
     * loop keeps paging until it either has enough or the index is exhausted.
     */
    override fun drain(token: String): List<JsonElement>? {
        if (!limiter.checkGet(token)) return null
        val now = System.currentTimeMillis() / 1000
        val items = mutableListOf<Map<String, AttributeValue>>()
        var lastKey: Map<String, AttributeValue>? = null
        do {
            val response =
                client.query(
                    QueryRequest.builder()
                        .tableName(messagesTable)
                        .indexName(POSTED_AT_INDEX_NAME)
                        .keyConditionExpression("#tok = :token")
                        .filterExpression("expiresAt > :now")
                        .expressionAttributeNames(mapOf("#tok" to "token"))
                        .expressionAttributeValues(
                            mapOf(
                                ":token" to AttributeValue.fromS(token),
                                ":now" to AttributeValue.fromN(now.toString()),
                            ),
                        )
                        .scanIndexForward(true)
                        .limit(MAX_MESSAGES_PER_POLL)
                        .exclusiveStartKey(lastKey)
                        .build(),
                )
            items.addAll(response.items())
            lastKey = response.lastEvaluatedKey().takeIf { it.isNotEmpty() }
        } while (lastKey != null && items.size < MAX_MESSAGES_PER_POLL)

        return items
            .take(MAX_MESSAGES_PER_POLL)
            .map { json.parseToJsonElement(it.getValue("payload").s()) }
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        // ReturnValues.ALL_OLD tells us whether an item actually existed to delete - a retried
        // delete-ack for an id already removed by a prior call must not decrement depthCounts
        // again. Unlike the TTL-drift case, an undercount here is unsafe in the wrong direction:
        // it lets post() accept messages past MAX_QUEUE_DEPTH instead of just rejecting slightly
        // early.
        val response =
            client.deleteItem(
                DeleteItemRequest.builder()
                    .tableName(messagesTable)
                    .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId)))
                    .returnValues(ReturnValue.ALL_OLD)
                    .build(),
            )
        if (response.hasAttributes()) {
            synchronized(getLock(token)) { decrementDepth(token, 1) }
        }
        return true
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        // BatchWriteItem's response doesn't say which keys actually existed (only which requests
        // are unprocessed and need retrying), so - unlike deleteById's ReturnValues.ALL_OLD -
        // there's no way to get an accurate decrement count from the delete calls themselves.
        // Check existence first via BatchGetItem instead; see deleteById's doc for why an
        // inflated decrement here is unsafe, not just imprecise.
        // BatchGetItem/BatchWriteItem reject a request containing duplicate keys outright
        // (ValidationException), which would fail the whole ack.
        val ids = msgIds.distinct()
        var existingCount = 0
        ids.chunked(100).forEach { chunk ->
            var keysToCheck =
                chunk.map { msgId ->
                    mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId))
                }
            var attempt = 0
            while (keysToCheck.isNotEmpty()) {
                backoffBeforeRetry(attempt++)
                val response =
                    client.batchGetItem(
                        BatchGetItemRequest.builder()
                            .requestItems(
                                mapOf(
                                    messagesTable to
                                        KeysAndAttributes.builder()
                                            .keys(keysToCheck)
                                            .projectionExpression("msgId")
                                            .consistentRead(true)
                                            .build(),
                                ),
                            )
                            .build(),
                    )
                existingCount += response.responses()[messagesTable]?.size ?: 0
                keysToCheck = response.unprocessedKeys()[messagesTable]?.keys() ?: emptyList()
            }
        }

        // BatchWriteItem caps at 25 requests per call and doesn't guarantee all of them land -
        // unprocessed ones come back in the response and get retried until none remain.
        ids.chunked(25).forEach { chunk ->
            var requests =
                chunk.map { msgId ->
                    WriteRequest.builder()
                        .deleteRequest(
                            DeleteRequest.builder()
                                .key(mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId)))
                                .build(),
                        )
                        .build()
                }
            var attempt = 0
            while (requests.isNotEmpty()) {
                backoffBeforeRetry(attempt++)
                val response =
                    client.batchWriteItem(
                        BatchWriteItemRequest.builder()
                            .requestItems(mapOf(messagesTable to requests))
                            .build(),
                    )
                requests = response.unprocessedItems()[messagesTable] ?: emptyList()
            }
        }
        synchronized(getLock(token)) { decrementDepth(token, existingCount) }
        return ids.size
    }

    /**
     * Unprocessed batch items mean DynamoDB is throttling; retrying immediately in a tight loop
     * only adds load. Exponential backoff, then give up (surfacing a 500) rather than spin.
     */
    private fun backoffBeforeRetry(attempt: Int) {
        if (attempt == 0) return
        check(attempt <= MAX_BATCH_RETRIES) { "DynamoDB batch still unprocessed after $MAX_BATCH_RETRIES retries" }
        Thread.sleep(minOf(1_000L, 25L shl attempt))
    }

    override fun evict() {
        // Both tables use native DynamoDB TTL, which sweeps expired items in the background at
        // no extra cost (see the class doc), so only in-process state needs trimming here.
        limiter.evict()
        // depthCounts is a cache of an exact, re-seedable count (see currentDepth), so dropping
        // entries is always safe - it only costs one count query on that token's next post. Bound
        // it so tokens that stop posting (their messages expired by TTL) don't accumulate forever.
        if (depthCounts.size > MAX_DEPTH_CACHE_ENTRIES) {
            for (token in depthCounts.keys) synchronized(getLock(token)) { depthCounts.remove(token) }
        }
    }

    override fun close() {
        client.close()
    }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

private val auditLog = LoggerFactory.getLogger("Server")

/**
 * Redacts a raw request path for logging so logs never contain routing tokens or msgIds.
 * `/inbox/...` keeps only its shape; any other path except the allowlisted `/health` is
 * collapsed entirely. The first segment is URL-decoded because routing matches decoded
 * segments (`/%69nbox/...` is served by the inbox routes).
 */
internal fun redactPath(path: String): String {
    if (path == "/health") return path
    val segments = path.trimStart('/').split('/')
    val first = runCatching { segments.first().decodeURLPart() }.getOrNull()
    if (first != "inbox") return "/<other>"
    return buildString {
        append("/inbox")
        if (segments.size > 1) append("/:token")
        if (segments.size > 2) append("/:msgId")
        if (segments.size > 3) append("/...")
    }
}

data class ServerState(
    val mailbox: MailboxStore = InMemoryMailboxState(),
    val trustProxy: Boolean = System.getenv("TRUST_PROXY")?.toBoolean() ?: false,
    val debug: Boolean = false,
    val healthcheckPingUrl: String? = null,
)

/**
 * How often the process pings HEALTHCHECK_PING_URL (a Healthchecks.io-style dead-man's-switch
 * URL) while it's alive and its event loop is responsive. Deliberately much shorter than
 * whatever period/grace the check itself is configured with in Healthchecks.io (e.g. a 5min
 * period / 2min grace) so a couple of missed pings from transient blips don't false-positive,
 * while a real sustained outage (crash loop, hung process) is still caught within a few minutes.
 */
private const val HEALTHCHECK_PING_INTERVAL_MS = 60_000L

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    // DynamoDB when AWS credentials are configured; otherwise an in-memory store (local dev).
    val mailbox =
        System.getenv("AWS_ACCESS_KEY_ID")?.let { accessKeyId ->
            println("Using DynamoDB store")
            DynamoMailboxState(
                createDynamoDbClient(
                    accessKeyId = accessKeyId,
                    secretAccessKey =
                        System.getenv("AWS_SECRET_ACCESS_KEY")
                            ?: error("AWS_SECRET_ACCESS_KEY is required when AWS_ACCESS_KEY_ID is set"),
                    region = System.getenv("AWS_REGION") ?: error("AWS_REGION is required when AWS_ACCESS_KEY_ID is set"),
                ),
            )
        } ?: run {
            // On Fly, a missing secret must not silently fall back to a store that loses every
            // message on each auto-stop.
            check(System.getenv("FLY_APP_NAME") == null) { "AWS_ACCESS_KEY_ID is required in production (FLY_APP_NAME is set)" }
            InMemoryMailboxState().also { println("Using in-memory store") }
        }
    val healthcheckPingUrl = System.getenv("HEALTHCHECK_PING_URL")
    if (healthcheckPingUrl == null) {
        auditLog.warn("HEALTHCHECK_PING_URL not set; no external uptime monitoring configured")
    }
    val state = ServerState(mailbox = mailbox, healthcheckPingUrl = healthcheckPingUrl)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(state)
    }.start(wait = true)
}

fun Application.module(state: ServerState = ServerState()) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging) {
        // The default format logs the full URI, which carries routing tokens (bearer
        // capabilities: anyone holding one can read/delete that mailbox) and msgIds.
        format { call ->
            "${call.response.status()?.value ?: "-"} ${call.request.httpMethod.value} ${redactPath(call.request.path())}"
        }
    }
    install(StatusPages) {
        // Without a handler, Ktor logs uncaught route exceptions as "Unhandled: METHOD - <raw path>",
        // leaking the token. Log only the redacted path and the exception type (store exception
        // messages may echo key values).
        exception<Throwable> { call, cause ->
            auditLog.error("500 ${call.request.httpMethod.value} ${redactPath(call.request.path())}: ${cause::class.simpleName}")
            call.respond(HttpStatusCode.InternalServerError)
        }
    }
    monitor.subscribe(ApplicationStopped) {
        state.mailbox.close()
    }

    launch(Dispatchers.Default) {
        while (isActive) {
            delay(RATE_LIMIT_WINDOW_MS)
            state.mailbox.evict()
        }
    }

    if (state.healthcheckPingUrl != null) {
        val healthcheckClient =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 10_000
                    connectTimeoutMillis = 10_000
                }
            }
        monitor.subscribe(ApplicationStopped) { healthcheckClient.close() }
        launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { healthcheckClient.get(state.healthcheckPingUrl) }
                    // Log only the exception type: Ktor client exception messages embed the
                    // request URL, and the ping URL is itself a secret.
                    .onFailure { auditLog.warn("Healthchecks.io ping failed: ${it::class.simpleName}") }
                delay(HEALTHCHECK_PING_INTERVAL_MS)
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        put("/inbox/{token}/{msgId}") {
            val token = call.parameters["token"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val msgId = call.parameters["msgId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

            if (token.length > 64 || msgId.length > 64) {
                return@put call.respond(HttpStatusCode.BadRequest)
            }

            val ip = call.clientIp(state.trustProxy)
            if (!state.mailbox.checkIpRateLimit(ip, RequestKind.WRITE)) return@put call.respond(HttpStatusCode.TooManyRequests)

            // Size limits prevent OOM from large bodies. Content-Length is checked up front, but a
            // chunked body has none, so the read itself is bounded too: read at most one byte past
            // the limit and reject if that byte arrives.
            val contentLength = call.request.contentLength()
            if (contentLength != null && contentLength > MAX_BODY_BYTES) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val bytes = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_BODY_BYTES) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val body = bytes.decodeToString()

            val payload = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (payload !is JsonObject || !payload.containsKey("type")) {
                return@put call.respond(HttpStatusCode.BadRequest)
            }
            if (!state.mailbox.post(token, payload, msgId)) return@put call.respond(HttpStatusCode.TooManyRequests)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/inbox/{token}") {
            val token = call.parameters["token"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (token.length > 64) return@get call.respond(HttpStatusCode.BadRequest)
            // Per-token limits alone are bypassed by rotating random tokens, each costing a store query.
            if (!state.mailbox.checkIpRateLimit(call.clientIp(state.trustProxy), RequestKind.READ)) {
                return@get call.respond(HttpStatusCode.TooManyRequests)
            }
            val startTime = System.currentTimeMillis()
            val messages = state.mailbox.drain(token) ?: return@get call.respond(HttpStatusCode.TooManyRequests)
            val responseString = json.encodeToString(messages)
            val elapsed = System.currentTimeMillis() - startTime
            if (!state.debug && elapsed < POLL_BASELINE_LATENCY_MS) delay(POLL_BASELINE_LATENCY_MS - elapsed)
            call.respondText(responseString, ContentType.Application.Json)
        }

        delete("/inbox/{token}") {
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (token.length > 64) return@delete call.respond(HttpStatusCode.BadRequest)
            val ids = call.request.queryParameters["ids"]?.split(",")?.filter { it.isNotEmpty() }?.distinct()
            if (ids == null || ids.size > MAX_MESSAGES_PER_POLL || ids.any { it.length > 64 }) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            if (!state.mailbox.checkIpRateLimit(call.clientIp(state.trustProxy), RequestKind.DELETE)) {
                return@delete call.respond(HttpStatusCode.TooManyRequests)
            }
            state.mailbox.deleteByIds(token, ids)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/inbox/{token}/{msgId}") {
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val msgId = call.parameters["msgId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (token.length > 64 || msgId.length > 64) {
                return@delete call.respond(HttpStatusCode.BadRequest)
            }
            if (!state.mailbox.checkIpRateLimit(call.clientIp(state.trustProxy), RequestKind.DELETE)) {
                return@delete call.respond(HttpStatusCode.TooManyRequests)
            }
            state.mailbox.deleteById(token, msgId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Behind Fly's proxy, [ApplicationRequest.local.remoteHost] is the proxy, so every client would
 * share one rate-limit bucket. Fly's edge sets `Fly-Client-IP` and overwrites any client-supplied
 * value; `X-Forwarded-For` is NOT used because its first entry is client-controlled.
 */
private fun ApplicationCall.clientIp(trustProxy: Boolean): String =
    rateLimitKey(
        (if (trustProxy) request.header("Fly-Client-IP")?.trim()?.takeIf { it.isNotEmpty() } else null)
            ?: request.local.remoteHost,
    )

/**
 * Per-IP bucket key. IPv6 clients typically control a whole /64, so keying by full address
 * would hand out a fresh bucket per request; key by the /64 prefix instead. Only IP literals
 * are parsed (never resolved); anything else is used verbatim.
 */
internal fun rateLimitKey(ip: String): String {
    val literal = ip.removePrefix("[").removeSuffix("]")
    // getByName() would do a DNS lookup for anything that isn't a literal; only hand it hex/colon/dot.
    if (':' !in literal || !literal.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return ip
    val addr = runCatching { InetAddress.getByName(literal) }.getOrNull()
    if (addr !is Inet6Address) return ip
    return addr.address.copyOfRange(0, 8).joinToString(":", postfix = "::/64") { "%02x".format(it) }
}
