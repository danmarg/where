package net.af0.where

import android.content.Context
import org.maplibre.android.MapLibre

fun initMapLibre(context: Context) {
    try {
        MapLibre.getInstance(context)
    } catch (_: UnsatisfiedLinkError) {
        // Native library unavailable in unit test environments — safe to skip.
    }
}
