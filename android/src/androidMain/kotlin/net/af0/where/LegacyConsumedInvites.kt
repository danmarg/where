package net.af0.where

import android.content.Context
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.toHex
import java.security.MessageDigest

/**
 * Read-only view of the pre-C4 consumed-invite record (a SharedPreferences file keyed by a hash of
 * the inviter's ephemeral key). Accepted invites now live in the shared `ConsumedInvites` table,
 * keyed by fingerprint, which these hashed keys can't be converted into; so an invite accepted
 * before the upgrade is still rejected from here until its record lapses, and the file is then
 * deleted. Nothing writes to it any more.
 */
internal class LegacyConsumedInvites(
    private val context: Context,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun contains(qr: QrPayload): Boolean = live().containsKey(key(qr))

    /** Deletes the file once every record has lapsed; the app calls it at start, off Main. */
    fun prune() {
        live()
    }

    private fun live(): Map<String, Any?> {
        val all = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
        if (all.isEmpty()) return emptyMap()
        val now = nowSeconds()
        val live = all.filterValues { it is Long && it >= now }
        // Everything lapsed: the file has nothing left to protect.
        if (live.isEmpty()) context.deleteSharedPreferences(PREFS_NAME)
        return live
    }

    private fun key(qr: QrPayload): String = MessageDigest.getInstance("SHA-256").digest(qr.ekPub.toByteArray()).copyOf(16).toHex()

    companion object {
        const val PREFS_NAME = "consumed_invites"
    }
}
