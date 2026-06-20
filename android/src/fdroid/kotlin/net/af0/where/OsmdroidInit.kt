package net.af0.where

import android.content.Context
import org.osmdroid.config.Configuration

fun initOsmdroid(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
    }
}
