package dev.marzahn.sankaku.features.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.arrow_back
import dev.marzahn.sankaku.ui.components.AppTopBar

/**
 * A full-screen sign-in screen over the whole app. Not a bottom sheet: M3's modal bottom sheet moves its resting
 * position on every frame of the keyboard's animation, so it jumped when opening and moved when switching fields.
 * The back arrow and system back close it; with the keyboard open, back hides the keyboard first, as anywhere else.
 */
@Composable
internal actual fun SignInPresentation(
  reason: String?,
  onDismiss: () -> Unit,
  body: @Composable ColumnScope.() -> Unit,
  confirm: @Composable () -> Unit,
  dismiss: @Composable () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  Scaffold(
    // A pane, so accessibility services announce it and treat it as the screen.
    modifier = Modifier.semantics { paneTitle = listOfNotNull("Sign in to Sankaku", reason).joinToString(" ") },
    topBar = {
      AppTopBar(
        title = "Sign in to Sankaku",
        subtitle = reason,
        navigationIcon = {
          IconButton(onClick = onDismiss) { Icon(arrow_back, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    Column(
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .consumeWindowInsets(padding)
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
      body()
      confirm()
    }
  }
}
