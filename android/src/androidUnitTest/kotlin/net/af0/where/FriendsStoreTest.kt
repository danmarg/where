package net.af0.where

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FriendsStoreTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val ownId = "owner-id"

    private fun store() = FriendsStore(context, ownId)

    @Test
    fun addFriendAppearsInSet() {
        val s = store()
        s.add("friend-1")
        assertTrue(s.friendIds.value.contains("friend-1"))
    }

    @Test
    fun addTrimsWhitespace() {
        val s = store()
        s.add("  friend-2  ")
        assertTrue(s.friendIds.value.contains("friend-2"))
        assertFalse(s.friendIds.value.contains("  friend-2  "))
    }

    @Test
    fun addIgnoresBlankId() {
        val s = store()
        s.add("   ")
        assertTrue(s.friendIds.value.isEmpty())
    }

    @Test
    fun addIgnoresOwnId() {
        val s = store()
        s.add(ownId)
        assertFalse(s.friendIds.value.contains(ownId))
    }

    @Test
    fun removeFriendDisappearsFromSet() {
        val s = store()
        s.add("friend-3")
        s.remove("friend-3")
        assertFalse(s.friendIds.value.contains("friend-3"))
    }

    @Test
    fun addMultipleFriendsAllPresent() {
        val s = store()
        s.add("a")
        s.add("b")
        s.add("c")
        assertEquals(setOf("a", "b", "c"), s.friendIds.value)
    }

    @Test
    fun toggleSharingFlipsState() {
        val s = store()
        assertTrue(s.isSharingLocation.value)
        s.toggleSharing()
        assertFalse(s.isSharingLocation.value)
        s.toggleSharing()
        assertTrue(s.isSharingLocation.value)
    }

    @Test
    fun sharingStatePersistedAcrossInstances() {
        val s1 = store()
        assertTrue(s1.isSharingLocation.value)
        s1.toggleSharing()
        assertFalse(s1.isSharingLocation.value)

        // A new instance should load the persisted value
        val s2 = store()
        assertFalse(s2.isSharingLocation.value)
    }
}
