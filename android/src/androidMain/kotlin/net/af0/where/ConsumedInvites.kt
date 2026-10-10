package net.af0.where

import android.content.SharedPreferences
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.toHex
import java.security.MessageDigest

/**
 * Invites this device has already accepted, so the same invite link can't pair twice.
 *
 * Accepting an invite creates a pairing with fresh keys; handling the same link again (a
 * redelivered deep link, a second tap, a re-scan) would silently create a duplicate friend.
 * Keyed by a hash of the inviter's ephemeral key, which is unique per invite, so a *new* invite
 * from the same person is never affected. Entries are kept for [RETENTION_SECONDS] past the
 * invite's expiry (or past acceptance, for invites without one) and then pruned; an expired
 * invite is rejected by [QrPayload.isExpired] anyway. Excluded from backup like all app data
 * (data_extraction_rules.xml).
 */
class ConsumedInvites(
    private val prefs: SharedPreferences,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun contains(qr: QrPayload): Boolean {
        val keepUntil = prefs.getLong(key(qr), Long.MIN_VALUE)
        return keepUntil != Long.MIN_VALUE && keepUntil >= nowSeconds()
    }

    fun add(qr: QrPayload) {
        val now = nowSeconds()
        val editor = prefs.edit()
        prefs.all.forEach { (k, v) -> if (v is Long && v < now) editor.remove(k) }
        editor.putLong(key(qr), maxOf(qr.expiresAt ?: now, now) + RETENTION_SECONDS)
        editor.apply()
    }

    fun remove(qr: QrPayload) {
        prefs.edit().remove(key(qr)).apply()
    }

    private fun key(qr: QrPayload): String = MessageDigest.getInstance("SHA-256").digest(qr.ekPub).copyOf(16).toHex()

    companion object {
        const val PREFS_NAME = "consumed_invites"
        const val RETENTION_SECONDS = 7L * 24 * 60 * 60
    }
}
