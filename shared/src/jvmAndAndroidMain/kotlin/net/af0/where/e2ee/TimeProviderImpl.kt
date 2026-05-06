package net.af0.where.e2ee

actual fun platformCurrentTimeSeconds(): Long = System.currentTimeMillis() / 1000L

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()
