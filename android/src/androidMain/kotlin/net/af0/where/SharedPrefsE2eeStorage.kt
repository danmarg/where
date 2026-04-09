package net.af0.where

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.af0.where.e2ee.E2eeStorage

class SharedPrefsE2eeStorage(context: Context) : E2eeStorage {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "e2ee_prefs",
        buildMasterKey(context),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private fun buildMasterKey(context: Context): MasterKey {
            // Try StrongBox-backed key (API 28+, requires dedicated security chip).
            // Fall back to standard Android Keystore if StrongBox is unavailable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val spec =
                        KeyGenParameterSpec.Builder(
                            MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setIsStrongBoxBacked(true)
                            .build()
                    return MasterKey.Builder(context)
                        .setKeyGenParameterSpec(spec)
                        .build()
                } catch (_: Exception) {
                    // StrongBox not available on this device; fall through to standard Keystore.
                }
            }
            return MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
    }
}
