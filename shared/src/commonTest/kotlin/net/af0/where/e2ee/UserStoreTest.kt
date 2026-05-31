package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserStoreTest {
    private class MemoryStorage : RawKeyValueStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test
    fun effectivelyPausedIdsIncludesElapsedTimers() {
        val store = UserStore(MemoryStorage())
        store.togglePauseFriend("alice")  // explicitly paused
        store.setFriendExpiry("bob", epochSeconds = 1000L)   // expired in the past
        store.setFriendExpiry("carol", epochSeconds = 5000L) // future

        val now = 2000L
        val effective = store.effectivelyPausedIds(now)
        assertTrue("alice" in effective, "explicit pause should be included")
        assertTrue("bob" in effective, "elapsed timer should be included")
        assertFalse("carol" in effective, "future timer should not be included")
    }

    @Test
    fun effectivelyPausedIdsEmptyByDefault() {
        val store = UserStore(MemoryStorage())
        assertEquals(emptySet(), store.effectivelyPausedIds(1000L))
    }

    @Test
    fun setFriendExpiryNullClears() {
        val store = UserStore(MemoryStorage())
        store.setFriendExpiry("bob", 5000L)
        assertEquals(5000L, store.friendExpiresAt.value["bob"])
        store.setFriendExpiry("bob", null)
        assertFalse("bob" in store.friendExpiresAt.value)
    }

    @Test
    fun friendExpiresAtPersistsAcrossInstances() {
        val storage = MemoryStorage()
        UserStore(storage).setFriendExpiry("bob", 5000L)
        val reloaded = UserStore(storage)
        assertEquals(5000L, reloaded.friendExpiresAt.value["bob"])
    }
}
