package net.af0.where

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.shared.MR

private data class DurationOption(val labelRes: StringResource, val seconds: Long?)

private val DURATION_OPTIONS = listOf(
    DurationOption(MR.strings.share_for_30m, 30L * 60),
    DurationOption(MR.strings.share_for_1h, 60L * 60),
    DurationOption(MR.strings.share_for_4h, 4L * 60 * 60),
    DurationOption(MR.strings.share_for_8h, 8L * 60 * 60),
    DurationOption(MR.strings.share_until_stop, null),
)

/**
 * Picker shown when the user taps the (off) sharing button. The selected option starts
 * sharing; a non-null duration schedules an automatic stop after that interval.
 */
@Composable
fun SharingDurationPicker(
    onDismiss: () -> Unit,
    onSelected: (durationSeconds: Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.share_for_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DURATION_OPTIONS.forEach { opt ->
                    TextButton(
                        onClick = { onSelected(opt.seconds) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(stringResource(opt.labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.cancel)) }
        },
    )
}
