package net.af0.where

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.shared.MR

@Composable
fun timeAgoStringFromMs(lastPingMs: Long?): String {
    if (lastPingMs == null) return stringResource(MR.strings.never)
    return timeAgoStringFromSeconds(lastPingMs / 1000)
}

@Composable
fun timeAgoStringFromSeconds(timestampSeconds: Long?): String {
    if (timestampSeconds == null) return stringResource(MR.strings.never)
    val seconds = (System.currentTimeMillis() / 1000) - timestampSeconds
    return when {
        seconds < 60 -> stringResource(MR.strings.just_now)
        seconds < 3600 -> stringResource(MR.strings.m_ago, seconds / 60)
        seconds < 86400 -> stringResource(MR.strings.h_ago, seconds / 3600)
        else -> stringResource(MR.strings.d_ago, seconds / 86400)
    }
}
