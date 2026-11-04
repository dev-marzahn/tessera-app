package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.error

/** A snackbar's visuals, with [isError] for a failure's reason (an error icon before the text, when asked for). */
data class AppSnackbarVisuals(
  override val message: String,
  override val actionLabel: String? = null,
  override val duration: SnackbarDuration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
  val isError: Boolean = false,
  override val withDismissAction: Boolean = false,
) : SnackbarVisuals

/**
 * M3's snackbar host. With [swipeable] (touch), a sideways swipe either way dismisses the snackbar, as Android's own
 * do: it reports `Dismissed`, so its action doesn't run, and a drag that starts on the action cancels the click.
 * Without it (desktop) it's the plain host. With [errorIcons] (the touch top line) a failure's reason starts with the
 * error icon.
 */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, swipeable: Boolean, modifier: Modifier = Modifier, errorIcons: Boolean = swipeable) {
  SnackbarHost(state, modifier) { data ->
    if (swipeable) {
      // One swipe state per snackbar, so the next one starts in place rather than already swiped away.
      key(data) {
        SwipeToDismissBox(
          state = rememberSwipeToDismissBoxState(),
          backgroundContent = {},
          onDismiss = { data.dismiss() },
        ) {
          AppSnackbar(data, errorIcons)
        }
      }
    } else {
      AppSnackbar(data, errorIcons)
    }
  }
}

@Composable
private fun AppSnackbar(data: SnackbarData, errorIcons: Boolean) {
  val visuals = data.visuals
  if (!errorIcons || visuals !is AppSnackbarVisuals || !visuals.isError) {
    Snackbar(data)
    return
  }
  val action = visuals.actionLabel
  // The 12 dp M3's `Snackbar(data)` keeps around itself.
  Snackbar(
    modifier = Modifier.padding(12.dp),
    action = action?.let {
      {
        TextButton(
          onClick = data::performAction,
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
        ) { Text(it) }
      }
    },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      // The scheme's error is made for its own surface; on the inverse one the other scheme's tone reads.
      val onDark = MaterialTheme.colorScheme.inverseSurface.luminance() < 0.5f
      Icon(error, contentDescription = null, tint = if (onDark) ErrorOnDark else ErrorOnLight, modifier = Modifier.size(20.dp))
      Text(visuals.message)
    }
  }
}

/** M3's error tones 80 and 40 (the mockup's `#FFB4AB` on the light scheme's dark inverse surface). */
private val ErrorOnDark = Color(0xFFFFB4AB)
private val ErrorOnLight = Color(0xFFBA1A1A)
