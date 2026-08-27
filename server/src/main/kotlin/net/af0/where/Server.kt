package net.af0.where

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.delete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
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
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest
import java.net.URI
import java.security.MessageDigest
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
private const val POLL_BASELINE_LATENCY_MS = 50L
private const val MAILBOX_TTL_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * Extra headroom added to the TTL when refreshing mailbox keys in Redis. Keys are only
 * re-EXPIREd once their remaining TTL drops below MAILBOX_TTL_MS, so a chatty mailbox doesn't
 * issue an EXPIRE on every single write. This means data can live up to this long past the
 * 7-day floor (acceptable: it's encrypted at rest) but never expires earlier than 7 days.
 */
private const val TTL_REFRESH_PADDING_SEC = 2 * 24 * 60 * 60L

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
// In-process rate limiter (shared by both store implementations)
// ---------------------------------------------------------------------------

/**
 * Tracks per-token POST/GET counts and per-IP POST counts entirely in the JVM
 * process. This avoids storing short-lived rate-limit keys in Redis, which
 * caused ~50 % of all observed Redis commands (INCR + EXPIRE per request,
 * plus constant TTL-expiry EVICTs for the 60-second windows).
 *
 * Thread-safe via ConcurrentHashMap + ConcurrentLinkedQueue; no locking needed
 * because we only need approximate counts (a few extra requests past the limit
 * are harmless, and missing a concurrent removal is safe).
 */
class InProcessRateLimiter {
    private val postTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val getTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val ipTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun checkPost(token: String): Boolean = check(postTimes, token, RATE_LIMIT_MAX_POSTS)

    fun checkGet(token: String): Boolean = check(getTimes, token, RATE_LIMIT_MAX_GETS)

    fun checkIp(ip: String): Boolean = check(ipTimes, ip, IP_RATE_LIMIT_MAX)

    fun evict(windowMs: Long = RATE_LIMIT_WINDOW_MS) {
        val cutoff = System.currentTimeMillis() - windowMs
        for (q in postTimes.values) q.removeIf { it < cutoff }
        for (q in getTimes.values) q.removeIf { it < cutoff }
        for (q in ipTimes.values) q.removeIf { it < cutoff }
    }

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

private const val IP_RATE_LIMIT_MAX = 2000

interface MailboxStore : AutoCloseable {
    fun checkIpRateLimit(ip: String): Boolean

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
// In-memory implementation (tests / no Redis)
// ---------------------------------------------------------------------------

private data class MailboxEntry(val payload: JsonElement, val expiresAt: Long, val msgId: String? = null)

class InMemoryMailboxState(
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
) : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()
    private val receivedIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedIdsOrder = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val dummyQueue = ConcurrentLinkedQueue<MailboxEntry>()

    private val locks = ConcurrentHashMap<String, Any>()

    private fun getLock(token: String) = locks.getOrPut(token) { Any() }

    override fun checkIpRateLimit(ip: String) = limiter.checkIp(ip)

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
// Redis implementation
// ---------------------------------------------------------------------------

class RedisMailboxState(
    val jedis: JedisPooled,
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
) : MailboxStore {
    // Rate limiting is handled in-process by InProcessRateLimiter; these scripts
    // are pure mailbox operations with no INCR/EXPIRE rate-limit keys. This
    // eliminates the two biggest Redis cost drivers: the constant short-TTL key
    // churn (one INCR + one EXPIRE per request) and the resulting EVICT spam from
    // 60-second windows expiring dozens of times per minute.
    private val postScript =
        """
        local inboxKey = KEYS[1]
        local receivedIdsKey = KEYS[2]
        local dataKey = KEYS[3]

        local maxQueueDepth = tonumber(ARGV[1])
        local payload = ARGV[2]
        local ttlSec = tonumber(ARGV[3])
        local msgId = ARGV[4]
        local score = tonumber(ARGV[5])
        local paddedTtlSec = tonumber(ARGV[6])

        -- Idempotency check: drop retransmits we have already stored.
        if msgId ~= "" then
            if redis.call('SISMEMBER', receivedIdsKey, msgId) == 1 then
                return 1
            end
        end

        -- Queue depth guard.
        if redis.call('ZCARD', inboxKey) >= maxQueueDepth then
            return 0
        end

        -- Store payload.
        redis.call('HSET', dataKey, msgId, payload)
        redis.call('ZADD', inboxKey, score, msgId)
        if msgId ~= "" then
            redis.call('SADD', receivedIdsKey, msgId)
        end

        -- Only re-EXPIRE once the TTL has decayed below the floor, padding back up above it.
        -- Avoids an EXPIRE (x2-3) on every write while guaranteeing keys never expire before
        -- ttlSec of remaining life. inboxKey/dataKey/receivedIdsKey are always refreshed
        -- together, so a single TTL check on dataKey is enough to gate all three. SADD above
        -- must run first so receivedIdsKey already exists by the time EXPIRE targets it.
        local ttl = redis.call('TTL', dataKey)
        if ttl < ttlSec then
            redis.call('EXPIRE', inboxKey, paddedTtlSec)
            redis.call('EXPIRE', dataKey, paddedTtlSec)
            if msgId ~= "" then
                redis.call('EXPIRE', receivedIdsKey, paddedTtlSec)
            end
        end

        return 1
        """.trimIndent()

    private val drainScript =
        """
        local inboxKey = KEYS[1]
        local dataKey = KEYS[2]

        local ids = redis.call('ZRANGE', inboxKey, 0, tonumber(ARGV[1]) - 1)
        if #ids == 0 then return {} end

        local payloads = redis.call('HMGET', dataKey, unpack(ids))
        for i, payload in ipairs(payloads) do
            if not payload then
                redis.call('ZREM', inboxKey, ids[i])
            end
        end
        return payloads
        """.trimIndent()

    override fun checkIpRateLimit(ip: String) = limiter.checkIp(ip)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        if (!limiter.checkPost(token)) return false
        val result =
            jedis.eval(
                postScript,
                listOf("inbox:$token", "receivedIds:$token", "inbox-data:$token"),
                listOf(
                    MAX_QUEUE_DEPTH.toString(),
                    payload.toString(),
                    (MAILBOX_TTL_MS / 1000).toString(),
                    msgId ?: "",
                    System.currentTimeMillis().toString(),
                    (MAILBOX_TTL_MS / 1000 + TTL_REFRESH_PADDING_SEC).toString(),
                ),
            )
        return result == 1L
    }

    override fun drain(token: String): List<JsonElement>? {
        if (!limiter.checkGet(token)) return null
        @Suppress("UNCHECKED_CAST")
        val result =
            jedis.eval(
                drainScript,
                listOf("inbox:$token", "inbox-data:$token"),
                listOf(MAX_MESSAGES_PER_POLL.toString()),
            ) as? List<*> ?: return emptyList()
        return result.filterNotNull().map { item ->
            val str = if (item is ByteArray) item.decodeToString() else item.toString()
            json.parseToJsonElement(str)
        }
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        jedis.zrem("inbox:$token", msgId)
        jedis.hdel("inbox-data:$token", msgId)
        return true
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        if (msgIds.isEmpty()) return 0
        jedis.zrem("inbox:$token", *msgIds.toTypedArray())
        jedis.hdel("inbox-data:$token", *msgIds.toTypedArray())
        return msgIds.size
    }

    override fun evict() = limiter.evict()

    override fun close() {
        jedis.close()
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
    private val locks = ConcurrentHashMap<String, Any>()
    private val depthCounts = ConcurrentHashMap<String, Int>()

    private fun getLock(token: String) = locks.getOrPut(token) { Any() }

    init {
        ensureTable(messagesTable, withPostedAtIndex = true)
        ensureTable(receivedIdsTable, withPostedAtIndex = false)
    }

    private fun ensureTable(
        tableName: String,
        withPostedAtIndex: Boolean,
    ) {
        try {
            client.describeTable { it.tableName(tableName) }
            return
        } catch (e: ResourceNotFoundException) {
            // Falls through to create below.
        }
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
        client.updateTimeToLive(
            UpdateTimeToLiveRequest.builder()
                .tableName(tableName)
                .timeToLiveSpecification(
                    TimeToLiveSpecification.builder().attributeName("expiresAt").enabled(true).build(),
                )
                .build(),
        )
    }

    private companion object {
        // GSI on messagesTable only: token (hash) + postedAt (range), ALL projection - lets
        // drain() query in delivery order directly instead of scanning+sorting client-side.
        const val POSTED_AT_INDEX_NAME = "postedAt-index"
    }

    override fun checkIpRateLimit(ip: String) = limiter.checkIp(ip)

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
                    // Already seen: idempotent no-op, matches RedisMailboxState's SISMEMBER check.
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
        var existingCount = 0
        msgIds.chunked(100).forEach { chunk ->
            var keysToCheck =
                chunk.map { msgId ->
                    mapOf("token" to AttributeValue.fromS(token), "msgId" to AttributeValue.fromS(msgId))
                }
            while (keysToCheck.isNotEmpty()) {
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
        msgIds.chunked(25).forEach { chunk ->
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
            while (requests.isNotEmpty()) {
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
        return msgIds.size
    }

    override fun evict() {
        // No-op beyond the in-process rate limiter: both tables use native DynamoDB TTL, which
        // sweeps expired items in the background at no extra cost. See the class doc.
        limiter.evict()
    }

    override fun close() {
        client.close()
    }
}

// ---------------------------------------------------------------------------
// Dual-write wrapper - Redis -> Postgres migration aid, removed once cutover completes
// ---------------------------------------------------------------------------

private val migrationLog = LoggerFactory.getLogger("MailboxMigration")

private fun tokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)

/**
 * Mirrors every mutation from [primary] to [secondary] best-effort (never fails the caller's
 * request if the mirror write fails), and diffs every drain() against a shadow read of
 * [secondary]. Used during the Redis -> Postgres migration: deploy with Redis as primary and
 * Postgres as secondary first, then flip once confident. "Zero WARN logs from this class" is the
 * proof of parity.
 */
class DualWriteMailboxState(
    private val primary: MailboxStore,
    private val secondary: MailboxStore,
    // Capped so a slow secondary (e.g. Postgres under load, bounded by its own small Hikari pool)
    // can't cause unbounded coroutine fan-out under sustained request volume - each mirrored
    // operation still runs off the request path, just with a ceiling on how many run at once.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4)),
    // evict() is called every RATE_LIMIT_WINDOW_MS (60s) by the app's housekeeping loop. Running
    // secondary.evict() on that cadence pings a serverless Postgres (Neon) far more often than its
    // autosuspend idle window, keeping its compute billed as always-on instead of scale-to-zero.
    // drain()/post() already filter on expires_at directly, so delaying the physical sweep is
    // correctness-neutral - only the row cleanup itself needs a much coarser cadence.
    private val secondaryEvictIntervalMs: Long = 30 * 60 * 1000L,
    private val onMismatch: ((tokenHash: String, primaryCount: Int, secondaryCount: Int, onlyInPrimary: Int, onlyInSecondary: Int) -> Unit)? = null,
) : MailboxStore {
    private val lastSecondaryEvictAt = java.util.concurrent.atomic.AtomicLong(0)

    override fun checkIpRateLimit(ip: String) = primary.checkIpRateLimit(ip)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        val result = primary.post(token, payload, msgId)
        if (result) {
            scope.launch {
                runCatching { secondary.post(token, payload, msgId) }
                    .onFailure { migrationLog.warn("secondary post failed token={}", tokenHash(token), it) }
            }
        }
        return result
    }

    override fun drain(token: String): List<JsonElement>? {
        val result = primary.drain(token) ?: return null
        scope.launch {
            runCatching {
                val secondaryResult = secondary.drain(token) ?: emptyList()
                comparePayloads(token, result, secondaryResult)
            }.onFailure { migrationLog.warn("secondary drain failed token={}", tokenHash(token), it) }
        }
        return result
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        val result = primary.deleteById(token, msgId)
        scope.launch {
            runCatching { secondary.deleteById(token, msgId) }
                .onFailure { migrationLog.warn("secondary deleteById failed token={}", tokenHash(token), it) }
        }
        return result
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        val result = primary.deleteByIds(token, msgIds)
        if (msgIds.isNotEmpty()) {
            scope.launch {
                runCatching { secondary.deleteByIds(token, msgIds) }
                    .onFailure { migrationLog.warn("secondary deleteByIds failed token={}", tokenHash(token), it) }
            }
        }
        return result
    }

    override fun evict() {
        primary.evict()
        val now = System.currentTimeMillis()
        val last = lastSecondaryEvictAt.get()
        if (now - last >= secondaryEvictIntervalMs && lastSecondaryEvictAt.compareAndSet(last, now)) {
            runCatching { secondary.evict() }
                .onFailure { migrationLog.warn("secondary evict failed", it) }
        }
    }

    override fun close() {
        primary.close()
        secondary.close()
    }

    private fun comparePayloads(
        token: String,
        primaryPayloads: List<JsonElement>,
        secondaryPayloads: List<JsonElement>,
    ) {
        val primarySorted = primaryPayloads.map { it.toString() }.sorted()
        val secondarySorted = secondaryPayloads.map { it.toString() }.sorted()
        if (primarySorted != secondarySorted) {
            val onlyInPrimary = primarySorted.toSet() - secondarySorted.toSet()
            val onlyInSecondary = secondarySorted.toSet() - primarySorted.toSet()
            val hash = tokenHash(token)
            migrationLog.warn(
                "drain mismatch token={} primaryCount={} secondaryCount={} onlyInPrimary={} onlyInSecondary={}",
                hash,
                primarySorted.size,
                secondarySorted.size,
                onlyInPrimary.size,
                onlyInSecondary.size,
            )
            runCatching {
                onMismatch?.invoke(hash, primarySorted.size, secondarySorted.size, onlyInPrimary.size, onlyInSecondary.size)
            }.onFailure { migrationLog.warn("onMismatch callback failed", it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Shadow-mismatch audit log (temporary — remove after DynamoDB cutover)
// ---------------------------------------------------------------------------

private val auditLog = LoggerFactory.getLogger("MismatchAuditLog")

class MismatchAuditLog(
    private val client: DynamoDbClient,
    private val tableName: String = "where_shadow_mismatches",
) {
    init {
        try {
            ensureTable()
        } catch (e: Exception) {
            auditLog.warn("Failed to set up mismatch audit table; mismatch recording will be best-effort", e)
        }
    }

    private fun ensureTable() {
        try {
            client.describeTable { it.tableName(tableName) }
            return
        } catch (e: ResourceNotFoundException) {
            // Falls through to create below.
        }
        try {
            client.createTable(
                CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                    )
                    .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build(),
                    )
                    .build(),
            )
        } catch (e: ResourceInUseException) {
            // Another process/run already created it between our describeTable and createTable -
            // fall through to the waiter below, which is safe to call either way.
        }
        client.waiter().waitUntilTableExists { it.tableName(tableName) }
        client.updateTimeToLive(
            UpdateTimeToLiveRequest.builder()
                .tableName(tableName)
                .timeToLiveSpecification(
                    TimeToLiveSpecification.builder().attributeName("expiresAt").enabled(true).build(),
                )
                .build(),
        )
    }

    fun record(tokenHash: String, primaryCount: Int, secondaryCount: Int, onlyInPrimary: Int, onlyInSecondary: Int) {
        val now = java.time.Instant.now()
        val sortableTimestamp = "%019d.%09d".format(now.epochSecond, now.nano)
        val sk = "$sortableTimestamp#${UUID.randomUUID().toString().take(8)}"
        val expiresAt = now.epochSecond + 30L * 24 * 60 * 60
        runCatching {
            client.putItem(
                PutItemRequest.builder()
                    .tableName(tableName)
                    .item(
                        mapOf(
                            "pk" to AttributeValue.fromS("mismatches"),
                            "sk" to AttributeValue.fromS(sk),
                            "tokenHash" to AttributeValue.fromS(tokenHash),
                            "primaryCount" to AttributeValue.fromN(primaryCount.toString()),
                            "secondaryCount" to AttributeValue.fromN(secondaryCount.toString()),
                            "onlyInPrimary" to AttributeValue.fromN(onlyInPrimary.toString()),
                            "onlyInSecondary" to AttributeValue.fromN(onlyInSecondary.toString()),
                            "expiresAt" to AttributeValue.fromN(expiresAt.toString()),
                        ),
                    )
                    .build(),
            )
        }.onFailure { auditLog.warn("Failed to record mismatch", it) }
    }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

data class ServerState(
    val mailbox: MailboxStore = InMemoryMailboxState(),
    val trustProxy: Boolean = System.getenv("TRUST_PROXY")?.toBoolean() ?: false,
    val debug: Boolean = false,
    val mismatchAuditLog: MismatchAuditLog? = null,
)

/**
 * "redis" / "dynamodb": that store alone.
 * "dual-write-redis-primary" / "dual-write-dynamodb-primary": both stores, mirroring every
 * mutation from the primary to the secondary, used during the Redis -> DynamoDB migration.
 * Requires both REDIS_URL and the AWS_* credentials.
 */
private fun buildMailboxStore(
    redisUrl: String?,
    dynamoClient: DynamoDbClient?,
    storeMode: String,
    onMismatch: ((String, Int, Int, Int, Int) -> Unit)? = null,
): MailboxStore {
    val redisStore = redisUrl?.let { RedisMailboxState(JedisPooled(it)) }
    val dynamoStore = dynamoClient?.let { DynamoMailboxState(it) }

    return when (storeMode) {
        "redis" ->
            redisStore?.also { println("Using Redis at ${URI(redisUrl).host}") }
                ?: InMemoryMailboxState().also { println("Using in-memory store") }
        "dynamodb" -> {
            requireNotNull(dynamoStore) { "AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_REGION are required for MAILBOX_STORE_MODE=dynamodb" }
            println("Using DynamoDB store")
            dynamoStore
        }
        "dual-write-redis-primary" -> {
            requireNotNull(redisStore) { "REDIS_URL is required for dual-write-redis-primary" }
            requireNotNull(dynamoStore) { "AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_REGION are required for dual-write-redis-primary" }
            println("Using dual-write store (Redis primary, DynamoDB shadow)")
            DualWriteMailboxState(primary = redisStore, secondary = dynamoStore, onMismatch = onMismatch)
        }
        "dual-write-dynamodb-primary" -> {
            requireNotNull(redisStore) { "REDIS_URL is required for dual-write-dynamodb-primary" }
            requireNotNull(dynamoStore) { "AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_REGION are required for dual-write-dynamodb-primary" }
            println("Using dual-write store (DynamoDB primary, Redis shadow)")
            DualWriteMailboxState(primary = dynamoStore, secondary = redisStore, onMismatch = onMismatch)
        }
        else -> error("Unknown MAILBOX_STORE_MODE: $storeMode")
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val storeMode = System.getenv("MAILBOX_STORE_MODE") ?: "redis"
    val dynamoClient =
        System.getenv("AWS_ACCESS_KEY_ID")?.let { accessKeyId ->
            createDynamoDbClient(
                accessKeyId = accessKeyId,
                secretAccessKey =
                    System.getenv("AWS_SECRET_ACCESS_KEY")
                        ?: error("AWS_SECRET_ACCESS_KEY is required when AWS_ACCESS_KEY_ID is set"),
                region = System.getenv("AWS_REGION") ?: error("AWS_REGION is required when AWS_ACCESS_KEY_ID is set"),
            )
        }
    val mismatchAuditLog = if (storeMode.startsWith("dual-write")) dynamoClient?.let { MismatchAuditLog(it) } else null
    val mailbox = buildMailboxStore(System.getenv("REDIS_URL"), dynamoClient, storeMode, mismatchAuditLog?.let { log ->
        { tokenHash, pc, sc, op, os -> log.record(tokenHash, pc, sc, op, os) }
    })
    val state = ServerState(mailbox = mailbox, mismatchAuditLog = mismatchAuditLog)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(state)
    }.start(wait = true)
}

fun Application.module(state: ServerState = ServerState()) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    monitor.subscribe(ApplicationStopped) { state.mailbox.close() }

    launch(Dispatchers.Default) {
        while (isActive) {
            delay(RATE_LIMIT_WINDOW_MS)
            state.mailbox.evict()
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
            if (!state.mailbox.checkIpRateLimit(ip)) return@put call.respond(HttpStatusCode.TooManyRequests)

            // Manual size check to prevent OOM from large bodies
            val contentLength = call.request.contentLength()
            if (contentLength != null && contentLength > 4 * 1024) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }

            val body = call.receiveText()
            if (body.length > 4 * 1024) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }

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
            val ids = call.request.queryParameters["ids"]?.split(",")?.filter { it.isNotEmpty() }
            if (ids == null || ids.size > MAX_MESSAGES_PER_POLL) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
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
            state.mailbox.deleteById(token, msgId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.clientIp(trustProxy: Boolean): String =
    if (trustProxy) {
        request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.local.remoteHost
    } else {
        request.local.remoteHost
    }
