package net.af0.where

import android.content.Context
import net.af0.where.e2ee.E2eeStorage

class SharedPrefsE2eeStorage(context: Context) : E2eeStorage {
    private val prefs = context.getSharedPreferences("e2ee_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String): String? {
        return prefs.getString(key, null)
    }

    override fun putString(
        key: String,
        value: String,
    ) {
        // SharedPreferences.apply() queues writes asynchronously and is safe to call from any thread.
        // Unlike commit(), apply() does not block and will not cause ANR issues.
        prefs.edit().putString(key, value).apply()
    }
}
