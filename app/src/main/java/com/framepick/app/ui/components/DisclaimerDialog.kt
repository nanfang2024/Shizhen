package com.framepick.app.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framepick.app.R
import com.framepick.app.data.preferences.DisclaimerStore

private val DISCLAIMER_CLAUSE_RES = listOf(
    R.string.disclaimer_clause_1,
    R.string.disclaimer_clause_2,
    R.string.disclaimer_clause_3,
    R.string.disclaimer_clause_4,
    R.string.disclaimer_clause_5,
)

/**
 * Blocking first-launch disclaimer. Agreeing persists the accepted version;
 * exiting (or dismissing) finishes the host activity.
 *
 * Pass [onDismiss] for read-only review mode (settings page): the dialog then
 * shows a single close button and neither persists acceptance nor exits.
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val exitApp: () -> Unit = { (context as? Activity)?.finish() }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss ?: exitApp,
        title = {
            Text(text = stringResource(R.string.disclaimer_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DISCLAIMER_CLAUSE_RES.forEach { clauseRes ->
                    Text(
                        text = stringResource(clauseRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (onDismiss == null) {
                TextButton(
                    onClick = {
                        DisclaimerStore.accept(context)
                        onAccept()
                    },
                ) {
                    Text(stringResource(R.string.disclaimer_accept))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.disclaimer_close))
                }
            }
        },
        dismissButton = {
            if (onDismiss == null) {
                TextButton(onClick = exitApp) {
                    Text(stringResource(R.string.disclaimer_exit))
                }
            }
        },
    )
}
