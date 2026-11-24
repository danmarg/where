package net.af0.where

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncGateTest {
    private var held = false
    private var acquires = 0
    private val gate =
        SyncGate(acquire = {
            held = true
            acquires++
        }, release = { held = false })

    @Test
    fun `lock is held until the last of overlapping syncs ends`() {
        gate.begin()
        gate.begin()
        assertEquals(2, gate.running)
        gate.end()
        assertTrue(held, "one still running")
        gate.end()
        assertFalse(held)
        assertEquals(0, gate.running)
    }

    @Test
    fun `a sync starting after the last one ended takes the lock again`() {
        gate.begin()
        gate.end()
        gate.begin()
        assertTrue(held)
        assertEquals(1, gate.running)
    }

    @Test
    fun `every begin refreshes the lock`() {
        gate.begin()
        gate.begin()
        assertEquals(2, acquires)
    }

    @Test
    fun `a throwing acquire leaves nothing counted`() {
        val g = SyncGate(acquire = { error("no lock") }, release = {})
        runCatching { g.begin() }
        assertEquals(0, g.running)
    }

    @Test
    fun `an unmatched end does not go negative`() {
        gate.end()
        assertEquals(0, gate.running)
        gate.begin()
        assertTrue(held)
    }

    /**
     * The lock is never released while a sync is counted, whatever the interleaving. The fake's
     * callbacks run inside the gate's critical section, so [active] (syncs between begin()
     * returning and end() being called, always a subset of the counted ones) is checked there,
     * where it can't race with another thread's begin/end.
     */
    @Test
    fun `concurrent begin and end never release the lock under a running sync`() {
        val violations = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val lockHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        val g =
            SyncGate(
                acquire = { lockHeld.set(true) },
                release = {
                    if (active.get() != 0) violations.incrementAndGet()
                    lockHeld.set(false)
                },
            )
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                start.await()
                repeat(20_000) {
                    g.begin()
                    active.incrementAndGet()
                    Thread.yield()
                    active.decrementAndGet()
                    g.end()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, violations.get())
        assertEquals(0, g.running)
        assertFalse(lockHeld.get())
    }
}
