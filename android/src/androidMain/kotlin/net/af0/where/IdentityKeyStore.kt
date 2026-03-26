package net.af0.where

import android.content.Context
import net.af0.where.e2ee.IdentityKeys
import net.af0.where.e2ee.RawKeyPair
import net.af0.where.e2ee.generateEd25519KeyPair
import net.af0.where.e2ee.generateX25519KeyPair
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object IdentityKeyStore {
    private const val PREFS_NAME = "identity_prefs"
    private const val KEY_IK_PRIV = "ik_priv"
    private const val KEY_IK_PUB = "ik_pub"
    private const val KEY_SIGIK_PRIV = "sigik_priv"
    private const val KEY_SIGIK_PUB = "sigik_pub"

    fun getOrCreate(context: Context): IdentityKeys {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val ikPriv = prefs.getString(KEY_IK_PRIV, null)
        val ikPub = prefs.getString(KEY_IK_PUB, null)
        val sigikPriv = prefs.getString(KEY_SIGIK_PRIV, null)
        val sigikPub = prefs.getString(KEY_SIGIK_PUB, null)

        return if (ikPriv != null && ikPub != null && sigikPriv != null && sigikPub != null) {
            IdentityKeys(
                ik = RawKeyPair(Base64.decode(ikPriv), Base64.decode(ikPub)),
                sigIk = RawKeyPair(Base64.decode(sigikPriv), Base64.decode(sigikPub)),
            )
        } else {
            val ik = generateX25519KeyPair()
            val sigIk = generateEd25519KeyPair()

            prefs.edit()
                .putString(KEY_IK_PRIV, Base64.encode(ik.priv))
                .putString(KEY_IK_PUB, Base64.encode(ik.pub))
                .putString(KEY_SIGIK_PRIV, Base64.encode(sigIk.priv))
                .putString(KEY_SIGIK_PUB, Base64.encode(sigIk.pub))
                .apply()

            IdentityKeys(ik, sigIk)
        }
    }
}
