package net.af0.where

import android.content.Context
import org.maplibre.android.MapLibre

fun initMapLibre(context: Context) {
    MapLibre.getInstance(context)
}
