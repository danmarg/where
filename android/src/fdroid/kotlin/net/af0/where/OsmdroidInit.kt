package net.af0.where

import android.content.Context
import org.osmdroid.config.Configuration

fun initOsmdroid(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        // Prefer external cache: the system clears cacheDir under storage pressure, which
        // would cause a sudden re-download storm for all visible tiles. External cache
        // is also cleared eventually but far less aggressively.
        val base = context.getExternalCacheDir() ?: context.cacheDir
        osmdroidTileCache = base.resolve("osmdroid/tiles")
    }
}
