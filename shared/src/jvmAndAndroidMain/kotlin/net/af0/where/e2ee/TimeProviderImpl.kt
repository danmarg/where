package net.af0.where.e2ee

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual fun platformCurrentTimeSeconds(): Long = System.currentTimeMillis() / 1000L

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

// DateTimeFormatter is immutable and thread-safe; safe to share as a top-level val.
private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

actual fun platformFormatLocalTime(seconds: Long): String =
    formatter.format(Instant.ofEpochSecond(seconds))
