package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import kotlinx.coroutines.delay

/**
 * A one-time tip (#140), the [Tips.current] one: an M3 rich tooltip titled "Tip" with its text and
 * Got it, over whatever this overlays. Lay it over its anchor with `Modifier.matchParentSize()` as the anchor's last
 * child, so adding it never rebuilds the anchor. It takes no touches of its own while hidden.
 *
 * Shown while [visible], once the motion around it has settled; [Tips.appeared] then marks it seen. It's focusable
 * while shown: a tap elsewhere or back dismisses it without reaching what's under it, and TalkBack moves to it. Gone
 * by any of those, or by leaving the composition, it's no longer current ([Tips.dismissed]), so a tip whose anchor
 * went before it showed is offered again later rather than holding the place of every other tip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipAnchor(tip: Tip, tips: Tips, visible: Boolean, modifier: Modifier = Modifier) {
  val state = rememberTooltipState(isPersistent = true)
  DisposableEffect(tip, tips) { onDispose { tips.dismissed(tip) } }
  LaunchedEffect(visible) {
    if (!visible) return@LaunchedEffect
    try {
      // The popup is placed from the anchor's layout, which a slide drawn in a graphics layer (the tab bar coming in)
      // doesn't move: it waits for the motion around it to end.
      delay(TIP_SETTLE_MS)
      tips.appeared(tip)
      // A persistent tooltip's show() returns once it's dismissed, and is cancelled with the anchor.
      state.show()
    } finally {
      tips.dismissed(tip)
    }
  }
  // The popup is placed against the tooltip box's own outer box, which takes its size from its content, so the
  // content fills this box, which [modifier] sizes to the anchor.
  Box(modifier) {
    TooltipBox(
      positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
      tooltip = {
        RichTooltip(
          title = { Text("Tip") },
          action = { TextButton(onClick = state::dismiss) { Text("Got it") } },
          caretShape = TooltipDefaults.caretShape(),
        ) { Text(tip.text) }
      },
      state = state,
      focusable = true,
      enableUserInput = false,
      hasAction = true,
    ) { Box(Modifier.fillMaxSize()) }
  }
}

/** How long a tip waits, once due, for the motion around its anchor to end. */
private const val TIP_SETTLE_MS = 600L
