package net.af0.where.e2ee

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun platformCurrentTimeSeconds(): Long = System.currentTimeMillis() / 1000L

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

actual fun platformFormatLocalTime(seconds: Long): String {
    return formatter.format(Date(seconds * 1000L))
}
