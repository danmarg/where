package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.test.runTest
import net.af0.where.db.WhereDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A database restored or transferred from elsewhere must not keep using its ratchet state
 * (spec §5.5); a database that belongs to this device must never be wiped.
 */
class DeviceBindingTest {
    private companion object {
        const val OUTBOX_FRIEND = "outbox-friend"
    }

    init {
        initializeE2eeTests()
    }

    private class FakeDeviceStore(var marker: String = "") : DeviceMarkerStore {
        var readFails = false
        var writeFails = false
        var writes = 0

        override fun readMarker(): String {
            if (readFails) throw IllegalStateException("errSecInteractionNotAllowed")
            return marker
        }

        override fun writeMarker(marker: String) {
            if (writeFails) throw IllegalStateException("keychain write failed")
            writes++
            this.marker = marker
        }
    }

    /** A database with one friend ("Alice"), one pending invite and one queued message. */
    private suspend fun populatedDriver(): SqlDriver {
        val alice = testE2eeManager(createTestSqlDriver())
        val driver = createTestSqlDriver()
        val bob = testE2eeManager(driver)
        bob.processScannedQr(alice.createInvite("Alice"), "Alice")
        bob.createInvite("Bob")
        WhereDatabase(driver).outboxQueries.insertOutbox("msg", OUTBOX_FRIEND, "token", byteArrayOf(1), 0)
        return driver
    }

    private fun outbox(driver: SqlDriver) = WhereDatabase(driver).outboxQueries.getOutboxForFriend(OUTBOX_FRIEND).executeAsList()

    private fun dbMarker(driver: SqlDriver) = WhereDatabase(driver).deviceBindingQueries.getMarker().executeAsOneOrNull()

    private suspend fun assertIntact(driver: SqlDriver) {
        val manager = testE2eeManager(driver)
        assertEquals(1, manager.listFriends().size)
        assertEquals(1, manager.listPendingInvites().size)
        assertEquals(1, outbox(driver).size)
    }

    @Test
    fun firstRunBindsTheDatabaseToTheDevice() =
        runTest {
            val driver = populatedDriver()
            val device = FakeDeviceStore()

            val result = reconcileDeviceBinding(driver, device)

            assertTrue(result.checked)
            assertTrue(result.discardedFriendNames.isEmpty())
            assertTrue(device.marker.isNotEmpty())
            assertEquals(device.marker, dbMarker(driver))
            assertIntact(driver)
        }

    @Test
    fun matchingMarkersLeaveTheDatabaseAlone() =
        runTest {
            val driver = populatedDriver()
            val device = FakeDeviceStore()
            reconcileDeviceBinding(driver, device)

            val result = reconcileDeviceBinding(driver, device)

            assertTrue(result.checked)
            assertTrue(result.discardedFriendNames.isEmpty())
            assertEquals(1, device.writes)
            assertIntact(driver)
        }

    @Test
    fun databaseFromAnotherDeviceIsDiscarded() =
        runTest {
            val driver = populatedDriver()
            reconcileDeviceBinding(driver, FakeDeviceStore())
            // Same database, now on a device that has its own (different) marker.
            val otherDevice = FakeDeviceStore("another-device")

            val result = reconcileDeviceBinding(driver, otherDevice)

            assertTrue(result.checked)
            assertEquals(listOf("Alice"), result.discardedFriendNames)
            assertEquals("another-device", dbMarker(driver))
            val manager = testE2eeManager(driver)
            assertTrue(manager.listFriends().isEmpty())
            assertTrue(manager.listPendingInvites().isEmpty())
            assertTrue(outbox(driver).isEmpty())

            // And it's now bound here: the next launch keeps whatever is paired from now on.
            assertTrue(reconcileDeviceBinding(driver, otherDevice).discardedFriendNames.isEmpty())
        }

    @Test
    fun databaseArrivingOnADeviceWithNoMarkerIsDiscarded() =
        runTest {
            val driver = populatedDriver()
            reconcileDeviceBinding(driver, FakeDeviceStore())
            val newDevice = FakeDeviceStore()

            val result = reconcileDeviceBinding(driver, newDevice)

            assertEquals(listOf("Alice"), result.discardedFriendNames)
            assertTrue(newDevice.marker.isNotEmpty())
            assertEquals(newDevice.marker, dbMarker(driver))
            assertTrue(testE2eeManager(driver).listFriends().isEmpty())
        }

    @Test
    fun unreadableDeviceMarkerChangesNothing() =
        runTest {
            val driver = populatedDriver()
            val device = FakeDeviceStore()
            reconcileDeviceBinding(driver, device)
            val bound = dbMarker(driver)
            device.readFails = true

            val result = reconcileDeviceBinding(driver, device)

            assertFalse(result.checked)
            assertTrue(result.discardedFriendNames.isEmpty())
            assertEquals(bound, dbMarker(driver))
            assertIntact(driver)
        }

    @Test
    fun failedDeviceWriteLeavesTheDatabaseUnbound() =
        runTest {
            val driver = populatedDriver()
            val device = FakeDeviceStore().apply { writeFails = true }

            val result = reconcileDeviceBinding(driver, device)

            assertFalse(result.checked)
            assertEquals(null, dbMarker(driver), "a database marker without its device copy would read as foreign")
            assertIntact(driver)

            // Once the device store works again the database is adopted, not wiped.
            device.writeFails = false
            assertTrue(reconcileDeviceBinding(driver, device).discardedFriendNames.isEmpty())
            assertIntact(driver)
        }

    @Test
    fun reinstallAdoptsTheExistingDeviceMarker() =
        runTest {
            // The keychain outlives an app deletion; the fresh database has no marker yet.
            val device = FakeDeviceStore("kept-from-previous-install")
            val driver = populatedDriver()

            val result = reconcileDeviceBinding(driver, device)

            assertTrue(result.discardedFriendNames.isEmpty())
            assertEquals("kept-from-previous-install", dbMarker(driver))
            assertEquals(0, device.writes)
            assertIntact(driver)
        }

    @Test
    fun migrationFromVersion4AddsTheBindingTable() {
        val driver = createTestSqlDriver()
        driver.execute(null, "DROP TABLE DeviceBinding", 0)

        WhereDatabase.Schema.migrate(driver, 4, WhereDatabase.Schema.version)

        val queries = WhereDatabase(driver).deviceBindingQueries
        queries.setMarker("m")
        assertNotNull(queries.getMarker().executeAsOneOrNull())
        assertEquals(5, WhereDatabase.Schema.version)
    }
}
