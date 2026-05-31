package net.af0.where

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.compose.stringResource
import java.text.DateFormat
import java.util.Date
import net.af0.where.shared.MR

@Composable
fun timeAgoStringFromMs(lastPingMs: Long?): String {
    if (lastPingMs == null) return stringResource(MR.strings.never)
    return timeAgoStringFromSeconds(lastPingMs / 1000)
}

@Composable
fun timeAgoStringFromSeconds(timestampSeconds: Long?): String {
    if (timestampSeconds == null || timestampSeconds == 0L) return stringResource(MR.strings.never)
    val seconds = (System.currentTimeMillis() / 1000) - timestampSeconds
    return when {
        seconds < 60 -> stringResource(MR.strings.just_now)
        seconds < 3600 -> stringResource(MR.strings.m_ago, seconds / 60)
        seconds < 86400 -> stringResource(MR.strings.h_ago, seconds / 3600)
        else -> stringResource(MR.strings.d_ago, seconds / 86400)
    }
}

private fun formatLocalTime(epochSeconds: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000L))

private fun formatLocalDate(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000L))

@Composable
fun peerSubtitleText(display: PeerDisplay): String = when (display) {
    is PeerDisplay.StoppedRecently -> "stopped sharing at ${formatLocalTime(display.timestampSeconds)}"
    is PeerDisplay.StoppedLongAgo -> "stopped sharing on ${formatLocalDate(display.timestampSeconds)}"
    is PeerDisplay.StationarySince -> "here since ${formatLocalTime(display.timestampSeconds)}"
    is PeerDisplay.LastSeen -> timeAgoStringFromSeconds(display.timestampSeconds)
}
