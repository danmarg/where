package net.af0.where

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.af0.where.e2ee.RawKeyValueStorage
import net.af0.where.e2ee.UserStore
import net.af0.where.model.UserLocation
import net.af0.where.model.newestPerUser
import kotlin.test.Test
import kotlin.test.assertEquals

class NewestPerUserAndRepositoryConcurrencyTest {
    private class MemoryStorage : RawKeyValueStorage {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }
    }

    private fun loc(
        user: String,
        ts: Long,
        lat: Double = 0.0,
    ) = UserLocation(user, lat, 0.0, ts)

    @Test
    fun newestPerUser_keepsHighestTimestampAndLaterWinsTies() {
        val out =
            newestPerUser(
                listOf(loc("a", 10, 1.0), loc("b", 5), loc("a", 7, 2.0), loc("b", 5, 3.0), loc("a", 10, 4.0)),
            )
        assertEquals(listOf(loc("b", 5, 3.0), loc("a", 10, 4.0)), out)
    }

    @Test
    fun newestPerUser_passesThroughSmallInputs() {
        assertEquals(emptyList(), newestPerUser(emptyList()))
        assertEquals(listOf(loc("a", 1)), newestPerUser(listOf(loc("a", 1))))
    }

    @Test
    fun awaitingFirstUpdate_concurrentMarkAndReceiveLosesNothing() =
        runBlocking {
            repeat(50) {
                val repo = LocationRepository(UserStore(MemoryStorage()))
                val ids = (0 until 64).map { "f$it" }
                repo.triggerRapidPoll()
                ids.map { id -> async(Dispatchers.Default) { repo.markAwaitingFirstUpdate(id) } }.awaitAll()
                ids.map { id -> async(Dispatchers.Default) { repo.onFriendLocationReceived(id) } }.awaitAll()
                assertEquals(0L, repo.lastRapidPollTrigger.value, "rapid poll must reset once every friend reported")
            }
        }

    @Test
    fun awaitingFirstUpdate_concurrentMarksAllCounted() =
        runBlocking {
            repeat(50) {
                val repo = LocationRepository(UserStore(MemoryStorage()))
                val ids = (0 until 64).map { "f$it" }
                repo.triggerRapidPoll()
                ids.map { id -> async(Dispatchers.Default) { repo.markAwaitingFirstUpdate(id) } }.awaitAll()
                // All but one report: the poll must still be rapid, i.e. no mark was lost.
                ids.drop(1).map { id -> async(Dispatchers.Default) { repo.onFriendLocationReceived(id) } }.awaitAll()
                assert(repo.lastRapidPollTrigger.value != 0L) { "a concurrent mark was lost" }
            }
        }

    @Test
    fun awaitingFirstUpdate_markAfterLastReceiveIsNotWiped() {
        val repo = LocationRepository(UserStore(MemoryStorage()))
        repo.markAwaitingFirstUpdate("a")
        repo.triggerRapidPoll()
        repo.onFriendLocationReceived("a") // last awaited id: clears the trigger
        assertEquals(0L, repo.lastRapidPollTrigger.value)

        // A new friend is marked and the poll re-triggered; an unrelated receive must not wipe either.
        repo.markAwaitingFirstUpdate("b")
        repo.triggerRapidPoll()
        repo.onFriendLocationReceived("a")
        assert(repo.lastRapidPollTrigger.value != 0L) { "unrelated receive wiped the trigger" }
        repo.markAwaitingFirstUpdate("c")
        repo.onFriendLocationReceived("b") // c is still awaited
        assert(repo.lastRapidPollTrigger.value != 0L) { "mark of c was wiped" }
        repo.onFriendLocationReceived("c")
        assertEquals(0L, repo.lastRapidPollTrigger.value)
    }
}
