package dev.marzahn.sankaku

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * The window's Esc, when no focused element handled it: goes back, while [enabled]. Compose it in the window before
 * the app.
 *
 * It's a handler on the window's back dispatcher, not the window's `onKeyEvent`. Compose hands that dispatcher the Esc
 * nothing else took, and gives it to the handler added last: an open dialog's own (every `Dialog`, `AlertDialog` and
 * sheet adds one), then `NavDisplay`'s, then this. As `onKeyEvent` it ran before the dispatcher, so Esc on a dialog
 * with nothing focused in it went back behind the dialog: on Settings' confirmations it closed the Settings tab (#364).
 */
@Composable
fun WindowBackHandler(enabled: Boolean, onBack: () -> Unit) {
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = enabled,
    onBackCompleted = onBack,
  )
}
