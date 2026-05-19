package net.af0.where.e2ee

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

// CFAbsoluteTime is seconds since 2001-01-01; add the Unix offset to get Unix seconds.
private const val CF_TO_UNIX_OFFSET = 978307200L

actual fun platformCurrentTimeSeconds(): Long = (CFAbsoluteTimeGetCurrent() + CF_TO_UNIX_OFFSET).toLong()

actual fun platformCurrentTimeMillis(): Long = ((CFAbsoluteTimeGetCurrent() + CF_TO_UNIX_OFFSET) * 1000).toLong()

private val formatter =
    NSDateFormatter().apply {
        dateFormat = "HH:mm:ss"
        timeZone = NSTimeZone.localTimeZone
    }

actual fun platformFormatLocalTime(seconds: Long): String {
    val date = NSDate(timeIntervalSinceReferenceDate = seconds.toDouble() - CF_TO_UNIX_OFFSET)
    return formatter.stringFromDate(date)
}
