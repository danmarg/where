package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import net.af0.where.db.WhereDatabase

/**
 * Storage that never leaves this device or install (iOS: a ThisDeviceOnly keychain item).
 */
interface DeviceMarkerStore {
    /**
     * The stored marker, or "" if none has ever been stored. Throws if the store can't be read
     * right now (e.g. the device is locked): an unreadable marker must never be taken as absent.
     */
    @Throws(Exception::class)
    fun readMarker(): String

    @Throws(Exception::class)
    fun writeMarker(marker: String)
}

class DeviceBindingResult(
    /** False when the device marker couldn't be read or written, so nothing was checked. */
    val checked: Boolean,
    /** Friends whose sessions were discarded because the database came from elsewhere. */
    val discardedFriendNames: List<String>,
)

/**
 * The database holds live ratchet state. A copy restored or transferred from another device
 * (or install) would roll back send counters and reuse (key, nonce) pairs (spec §5.5), so its
 * sessions, pending invites and outbox are discarded and the friends must re-pair.
 *
 * Detection: the database and [deviceStore] each hold the same random marker. The device store
 * doesn't travel with the database, so a database whose marker is missing from or differs from
 * the device store came from elsewhere. A database with no marker (new, or created before this
 * check existed) is adopted as this device's.
 *
 * Must run before an [E2eeManager] is constructed on [sqlDriver], since it loads friends.
 */
fun reconcileDeviceBinding(
    sqlDriver: SqlDriver,
    deviceStore: DeviceMarkerStore,
): DeviceBindingResult {
    val deviceMarker =
        try {
            deviceStore.readMarker().ifEmpty { null }
        } catch (e: Exception) {
            return DeviceBindingResult(checked = false, discardedFriendNames = emptyList())
        }
    val database = WhereDatabase(sqlDriver)
    val dbMarker = database.deviceBindingQueries.getMarker().executeAsOneOrNull()
    if (dbMarker != null && dbMarker == deviceMarker) {
        return DeviceBindingResult(checked = true, discardedFriendNames = emptyList())
    }

    // Write the device store first: a database marker without its device copy reads as foreign.
    val marker = deviceMarker ?: newDeviceMarker()
    if (deviceMarker == null) {
        try {
            deviceStore.writeMarker(marker)
        } catch (e: Exception) {
            return DeviceBindingResult(checked = false, discardedFriendNames = emptyList())
        }
    }

    if (dbMarker == null) {
        database.deviceBindingQueries.setMarker(marker)
        return DeviceBindingResult(checked = true, discardedFriendNames = emptyList())
    }

    val discarded =
        database.transactionWithResult {
            val names = database.friendsQueries.getAllFriends().executeAsList().map { it.name }
            database.friendsQueries.deleteAll()
            database.invitesQueries.deleteAll()
            database.outboxQueries.deleteAll()
            database.deviceBindingQueries.setMarker(marker)
            names
        }
    return DeviceBindingResult(checked = true, discardedFriendNames = discarded)
}

@OptIn(ExperimentalStdlibApi::class)
private fun newDeviceMarker(): String = randomBytes(16).toHexString()
