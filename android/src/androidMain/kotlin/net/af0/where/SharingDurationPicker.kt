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

private data class DurationOption(val label: String, val seconds: Long?)

private val DURATION_OPTIONS = listOf(
    DurationOption("30 minutes", 30L * 60),
    DurationOption("1 hour", 60L * 60),
    DurationOption("4 hours", 4L * 60 * 60),
    DurationOption("8 hours", 8L * 60 * 60),
    DurationOption("Until I stop", null),
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
        title = { Text("Share location for…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DURATION_OPTIONS.forEach { opt ->
                    TextButton(
                        onClick = { onSelected(opt.seconds) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
