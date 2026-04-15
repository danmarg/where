package net.af0.where

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import net.af0.where.e2ee.QrPayload
import net.af0.where.shared.MR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(
    qrPayload: QrPayload,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val qrUrl = remember(qrPayload) { QrUtils.payloadToUrl(qrPayload) }
    val qrBitmap = remember(qrUrl) { QrUtils.generateBitmap(qrUrl) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(MR.strings.invite_a_friend), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(MR.strings.invite_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(MR.strings.your_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(MR.strings.invite),
                    modifier = Modifier.size(240.dp),
                )
            }
            val joinMeSubject = stringResource(MR.strings.join_me_on_where)
            val shareInviteTitle = stringResource(MR.strings.share_invite)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(MR.strings.cancel)) }
                Button(onClick = {
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, qrUrl)
                            putExtra(Intent.EXTRA_SUBJECT, joinMeSubject)
                        }
                    context.startActivity(Intent.createChooser(intent, shareInviteTitle))
                }) {
                    Text(stringResource(MR.strings.share_link))
                }
            }
        }
    }
}
