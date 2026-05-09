package net.af0.where.e2ee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class ConcurrencyRobustnessTest {
    init {
        initializeE2eeTests()
    }

    /**
     * Verifies that concurrent calls to cleanupExpiredInvites and invite creation
     * do not deadlock or cause data corruption.
     */
    @Test
    fun testConcurrentCleanupAndInviteCreation() = runTest {
        val storage = MemoryStorage()
        val store = E2eeManager(storage)
        
        // 1. Create a bunch of initial invites (up to the limit of 10)
        repeat(5) { i ->
            store.createInvite("User $i")
        }
        
        // 2. Run cleanup and creation/deletion in parallel
        withContext(Dispatchers.Default) {
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            
            // Cleanup job
            jobs.add(launch {
                repeat(20) {
                    store.cleanupExpiredInvites(0) // Cleanup everything immediately
                    kotlinx.coroutines.yield()
                }
            })
            
            // Creation job
            jobs.add(launch {
                repeat(20) { i ->
                    try {
                        store.createInvite("New User $i")
                    } catch (_: IllegalStateException) {} // Ignore if we hit the limit
                    kotlinx.coroutines.yield()
                }
            })
            
            // Deletion job (using listPendingInvites)
            jobs.add(launch {
                repeat(20) {
                    val invites = store.listPendingInvites()
                    if (invites.isNotEmpty()) {
                        store.clearInvite(invites.first().qrPayload.ekPub)
                    }
                    kotlinx.coroutines.yield()
                }
            })
            
            jobs.joinAll()
        }
        
        // If we reach here without deadlock, the locks are working correctly.
        val finalInvites = store.listPendingInvites()
        assertTrue(finalInvites.size <= 10) 
    }

    /**
     * Verifies that E2eePersistence correctly enforces the lock hierarchy
     * and doesn't allow reentrant deadlocks even under heavy load.
     */
    @Test
    fun testHighContentionLocking() = runTest {
        val storage = MemoryStorage()
        val store = E2eeManager(storage)
        
        withContext(Dispatchers.Default) {
            val jobs = List(10) { 
                launch {
                    repeat(50) {
                        try {
                            store.createInvite("Busy Alice")
                        } catch (_: IllegalStateException) {}
                        store.listPendingInvites()
                    }
                }
            }
            jobs.joinAll()
        }
        
        // Metadata is still intact
        store.listPendingInvites()
    }
}
