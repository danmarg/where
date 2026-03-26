package net.af0.where.e2ee

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent

// CFAbsoluteTime is seconds since 2001-01-01; add the Unix offset to get Unix seconds.
private const val CF_TO_UNIX_OFFSET = 978307200L

actual fun currentTimeSeconds(): Long =
    (CFAbsoluteTimeGetCurrent() + CF_TO_UNIX_OFFSET).toLong()
