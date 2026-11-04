package dev.marzahn.sankaku.features.auth

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * How the sign-in form is presented: a dialog on desktop, a full-screen screen on Android. [body] is the fields, the
 * error and the terms notice; [confirm] is Sign in, a full-width button that goes under the body, with "Create an
 * account" under it. [dismiss] is the dialog's Cancel; the Android screen has a back arrow instead. [onDismiss] runs when the user dismisses it by any other means (outside tap, back, the arrow).
 * [reason], if any, says under the title what the sign-in is for.
 */
@Composable
internal expect fun SignInPresentation(
  reason: String?,
  onDismiss: () -> Unit,
  body: @Composable ColumnScope.() -> Unit,
  confirm: @Composable () -> Unit,
  dismiss: @Composable () -> Unit,
)
