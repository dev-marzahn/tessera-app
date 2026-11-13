package dev.marzahn.sankaku.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal actual fun SignInPresentation(
  reason: String?,
  onDismiss: () -> Unit,
  body: @Composable ColumnScope.() -> Unit,
  confirm: @Composable () -> Unit,
  dismiss: @Composable () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column {
        Text("Sign in to Sankaku")
        if (reason != null) {
          Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    },
    // Sign in goes under the body, across the dialog; only Cancel sits in the button row.
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        body()
        confirm()
      }
    },
    confirmButton = {},
    dismissButton = dismiss,
  )
}
