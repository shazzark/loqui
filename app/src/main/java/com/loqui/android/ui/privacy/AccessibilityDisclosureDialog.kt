package com.loqui.android.ui.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.loqui.android.R

/**
 * The prominent disclosure Play requires before an accessibility service is enabled.
 *
 * Shown at the moment the user asks for accessibility insertion, explains the one job the
 * service does, and only then opens the system settings deep-link. Declining is a
 * supported answer: the clipboard fallback stays fully functional.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.disclosure_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.disclosure_bullets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.disclosure_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.disclosure_decline))
            }
        },
    )
}
