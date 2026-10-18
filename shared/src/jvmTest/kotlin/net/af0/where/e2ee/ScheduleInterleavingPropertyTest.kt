package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Property test over random schedules of two paired [E2eeManager]s on SQLite, with the transport
 * rules LocationClient and the server follow: the outbox drains in order and stops at the first
 * failed post; no new message is created while it's stuck (WAL safety); the server keeps frames
 * per mailbox token in arrival order and serves them a page at a time; and a receiver polls only
 * its current recvToken (§5.4.2).
 *
 * Within those rules a schedule interleaves sends, failed posts (retried later), frames the
 * server takes and loses, frames stored ahead of the one posted before them (same epoch), crashes
 * after a post but before its outbox entry is cleared (so it's
 * posted again), lost acks (so a frame is served again), batches handed over in any order, replays
 * of frames already decrypted, and restarts that reload a manager from its database. Throughout,
 * it checks that:
 *
 *  - no message decrypts twice, and nothing decrypts that its sender didn't send;
 *  - a replayed frame delivers nothing and leaves the session as it was;
 *  - skippedMessageKeys never exceeds [MAX_SKIPPED_KEYS].
 *
 * Then the network is repaired (outboxes drain, every mailbox is read until empty). Every message
 * that reached the server must have decrypted exactly once, and a fresh message each way must
 * get through. The server never loses an epoch's transition message (seq 1): §5.4.5 accepts that
 * loss as unrecoverable.
 *
 * A failure names its seed; [replay] reruns one schedule with its full trace.
 */
class ScheduleInterleavingPropertyTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun randomSchedulesKeepDeliveryInvariants() {
        val failures = (1..SCHEDULES).mapNotNull { seed -> runSchedule(seed.toLong(), verbose = false) }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $SCHEDULES schedules failed:\n" + failures.sortedBy { it.length }.take(3).joinToString("\n\n"))
        }
    }

    /** Reruns one schedule with its full trace: `--tests '*ScheduleInterleaving*replay' -Dschedule.seed=<n>`. */
    @Test
    fun replay() {
        val seed = System.getProperty("schedule.seed")?.toLong() ?: return
        runSchedule(seed, verbose = true)?.let { fail(it) }
    }

    private class Party(val name: String, val driver: SqlDriver) {
        var manager = testE2eeManager(driver)

        /** Message numbers this party has decrypted. */
        val decrypted = mutableSetOf<Int>()

        /** Message numbers whose frame reached the server at least once, addressed to this party. */
        val reachedServer = mutableSetOf<Int>()
    }

    /** A frame on the server in mailbox [token], addressed to [to]. */
    private class Frame(val to: Party, val token: String, val number: Int, val payload: EncryptedMessagePayload)

    private enum class Post { OK, FAILED, LOST, CRASH_AFTER_POST }

    /** Runs one schedule; returns a description of the first broken invariant, or null. */
    private fun runSchedule(
        seed: Long,
        verbose: Boolean,
    ): String? {
        val random = Random(seed)
        val trace = mutableListOf<String>()
        var failure: String? = null
        runTest {
            val alice = Party("alice", createTestSqlDriver())
            val bob = Party("bob", createTestSqlDriver())
            val qr = alice.manager.createInvite("Alice")
            val (init, bobEntry) = bob.manager.processScannedQr(qr, "Bob")
            alice.manager.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val friendId = bobEntry.id
            bob.manager.removeFromOutbox(friendId, init.msgId) // the KeyExchangeInit, delivered above

            fun peer(p: Party) = if (p === alice) bob else alice

            val server = mutableListOf<Frame>() // arrival order
            val everPosted = mutableListOf<Frame>()
            val numberOf = mutableMapOf<String, Int>() // msgId -> message number
            val seqOf = mutableMapOf<String, Long>() // msgId -> sendSeq
            var nextNumber = 0

            fun expect(
                ok: Boolean,
                what: () -> String,
            ) {
                if (!ok && failure == null) {
                    failure = "seed $seed: ${what()}\ntrace:\n" + (if (verbose) trace else trace.takeLast(40)).joinToString("\n")
                }
            }

            suspend fun session(p: Party) = p.manager.getFriend(friendId)!!.session

            /**
             * The server takes a PUT; like the real one it is idempotent per msgId while stored.
             * Sometimes it lands ahead of the frame posted before it (postedAt ties): only within
             * an epoch, since a mailbox holds the transition message (seq 1) only after the rest.
             */
            fun post(frame: Frame) {
                if (server.any { it.token == frame.token && it.payload.msgId == frame.payload.msgId }) {
                    // Already stored.
                } else if ((seqOf[frame.payload.msgId] ?: 0) > 1 && random.nextInt(5) == 0) {
                    val previous = server.indexOfLast { it.token == frame.token }
                    val swappable = previous >= 0 && (seqOf[server[previous].payload.msgId] ?: 0) > 1
                    if (swappable) server.add(previous, frame) else server.add(frame)
                } else {
                    server.add(frame)
                }
                everPosted.add(frame)
                frame.to.reachedServer.add(frame.number)
            }

            suspend fun deliver(
                to: Party,
                frames: List<Frame>,
                ackLost: Boolean,
            ) {
                val before = session(to)
                val result =
                    to.manager.processBatch(friendId, before.recvToken.toHex(), frames.map { it.payload })
                        ?: return expect(false) { "${to.name} lost its friend" }
                val numbers = result.decryptedLocations.map { it.lat.toInt() }
                trace += "  ${to.name} got ${frames.map { it.number }} -> decrypted $numbers"
                for (n in numbers) {
                    expect(everPosted.any { it.to === to && it.number == n }) { "${to.name} decrypted unsent #$n" }
                    expect(to.decrypted.add(n)) { "${to.name} decrypted #$n twice" }
                }
                val after = session(to)
                expect(after.skippedMessageKeys.size <= MAX_SKIPPED_KEYS) { "skipped keys ${after.skippedMessageKeys.size}" }
                if (frames.all { it.number in to.decrypted && it.number !in numbers }) {
                    // Every frame was one already decrypted.
                    expect(numbers.isEmpty() && after == before) { "${to.name}: replay of ${frames.map { it.number }} changed the session" }
                }
                if (!ackLost) server.removeAll { f -> f.token == before.recvToken.toHex() && f.payload.msgId in result.processedIds }
            }

            /** LocationClient.deliverInOrder: post in order, stop at the first failure. False if it stopped. */
            suspend fun drainOutbox(
                from: Party,
                outcome: () -> Post,
            ): Boolean {
                for (entry in from.manager.getOutbox(friendId)) {
                    val payload = entry.payload as? EncryptedMessagePayload ?: continue
                    val n = numberOf.getValue(payload.msgId)
                    val frame = Frame(peer(from), entry.token, n, payload)
                    var o = outcome()
                    if (o == Post.LOST && seqOf[payload.msgId] == 1L) o = Post.FAILED
                    trace += "  ${from.name} post #$n: $o"
                    when (o) {
                        Post.FAILED -> return false
                        Post.LOST -> from.manager.removeFromOutbox(friendId, entry.msgId)
                        Post.CRASH_AFTER_POST -> {
                            post(frame)
                            from.manager = testE2eeManager(from.driver)
                            return false
                        }
                        Post.OK -> {
                            post(frame)
                            from.manager.removeFromOutbox(friendId, entry.msgId)
                        }
                    }
                }
                return true
            }

            fun chaoticPost(): Post =
                when (random.nextInt(12)) {
                    0, 1 -> Post.FAILED
                    2 -> Post.LOST
                    3 -> Post.CRASH_AFTER_POST
                    else -> Post.OK
                }

            suspend fun send(p: Party): Boolean {
                // WAL safety: a stuck outbox blocks a new message until it drains.
                if (!drainOutbox(p, ::chaoticPost)) return false
                val n = nextNumber++
                val (payload, sent) =
                    p.manager.encryptAndAdvance(friendId, MessagePlaintext.Location(n.toDouble(), 0.0, 1.0, 1_000L + n))
                numberOf[payload.msgId] = n
                seqOf[payload.msgId] = sent.sendSeq
                trace += "${p.name} send #$n (seq ${sent.sendSeq})"
                return true
            }

            /** One poll: the first [page] frames of the mailbox p reads, maybe handed over shuffled. */
            suspend fun poll(
                p: Party,
                page: Int,
                shuffle: Boolean,
                ackLost: Boolean,
            ) {
                val token = session(p).recvToken.toHex()
                val waiting = server.filter { it.to === p && it.token == token }.take(page)
                if (waiting.isEmpty()) return
                deliver(p, if (shuffle) waiting.shuffled(random) else waiting, ackLost)
            }

            repeat(random.nextInt(MIN_STEPS, MAX_STEPS)) {
                if (failure != null) return@repeat
                val p = if (random.nextBoolean()) alice else bob
                when (random.nextInt(100)) {
                    in 0..39 -> {
                        if (send(p)) drainOutbox(p, ::chaoticPost)
                    }
                    in 40..69 -> {
                        val ackLost = random.nextInt(8) == 0
                        trace += "${p.name} poll${if (ackLost) " (ack lost)" else ""}"
                        poll(p, page = random.nextInt(1, 8), shuffle = true, ackLost = ackLost)
                    }
                    in 70..79 -> {
                        // The server (or anyone who saw it) replays a frame already decrypted.
                        val old = everPosted.filter { it.to === p && it.number in p.decrypted }.randomOrNull(random) ?: return@repeat
                        trace += "${p.name} replayed #${old.number}"
                        deliver(p, listOf(old), ackLost = false)
                    }
                    in 80..89 -> {
                        trace += "${p.name} retry outbox"
                        drainOutbox(p, ::chaoticPost)
                    }
                    else -> {
                        trace += "${p.name} restart"
                        p.manager = testE2eeManager(p.driver)
                    }
                }
            }
            if (failure != null) return@runTest

            // Repair the network: drain both outboxes, read every mailbox until nothing changes.
            trace += "-- quiesce"
            repeat(QUIESCE_ROUNDS) {
                for (p in listOf(alice, bob)) drainOutbox(p) { Post.OK }
                for (p in listOf(alice, bob)) poll(p, page = 50, shuffle = false, ackLost = false)
            }
            for (p in listOf(alice, bob)) {
                val missing = p.reachedServer - p.decrypted
                expect(missing.isEmpty()) { "${p.name} never decrypted ${missing.sorted()} (decrypted ${p.decrypted.sorted()})" }
            }

            // And the session still works both ways.
            for (p in listOf(alice, bob)) {
                val n = nextNumber
                send(p)
                drainOutbox(p) { Post.OK }
                poll(peer(p), page = 50, shuffle = false, ackLost = false)
                expect(n in peer(p).decrypted) { "${peer(p).name} could not decrypt a fresh message #$n after the schedule" }
            }
        }
        if (verbose) println(trace.joinToString("\n"))
        return failure
    }

    private companion object {
        const val SCHEDULES = 300
        const val MIN_STEPS = 40
        const val MAX_STEPS = 160
        const val QUIESCE_ROUNDS = 4
    }
}
