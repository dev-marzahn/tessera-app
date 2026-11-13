package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import kotlinx.coroutines.delay

/**
 * A one-time tip (#140), the [Tips.current] one: an M3 rich tooltip titled "Tip" with its text and
 * Got it, over whatever this overlays. Lay it over its anchor with `Modifier.matchParentSize()` as the anchor's last
 * child, so adding it never rebuilds the anchor. It takes no touches of its own while hidden.
 *
 * Shown while [visible] and nothing covers the window ([Tips.covered]), once the motion around it has settled; [Tips.appeared] then marks it seen. While shown it's
 * [focusable] by default: a tap elsewhere or back dismisses it without reaching what's under it, and TalkBack moves to
 * it. Gone by any of those, or by leaving the composition, it's no longer current ([Tips.dismissed]), so a tip whose
 * anchor went before it showed is offered again later rather than holding the place of every other tip. Not
 * [focusable], it leaves the keys where they are, and the screen around it takes it away when it should.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipAnchor(
  tip: Tip,
  tips: Tips,
  visible: Boolean,
  modifier: Modifier = Modifier,
  focusable: Boolean = true,
  text: String = tip.text,
  action: TipAction? = null,
) {
  // Never over a page that covers the window (#522).
  val showing = visible && !tips.covered
  val state = rememberTooltipState(isPersistent = true)
  DisposableEffect(tip, tips) { onDispose { tips.dismissed(tip) } }
  LaunchedEffect(showing) {
    // Covered while on screen: cancelling its show() leaves the popup up.
    if (!showing) {
      state.dismiss()
      return@LaunchedEffect
    }
    try {
      // The popup is placed from the anchor's layout, which a slide drawn in a graphics layer (the tab bar coming in)
      // doesn't move: it waits for the motion around it to end.
      delay(TIP_SETTLE_MS)
      tips.appeared(tip)
      // A persistent tooltip's show() returns once it's dismissed, and is cancelled with the anchor. M3's tooltips
      // share one mutex: at the default priority a hover tooltip under the pointer took it and the tip went at once,
      // already seen (desktop, #504). Above user input, nothing but Got it, a click elsewhere or the anchor ends it.
      state.show(MutatePriority.PreventUserInput)
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
          action = {
            Row {
              TextButton(onClick = state::dismiss) { Text("Got it") }
              // Goes with the tip: it's done once shown anyway.
              action?.let { TextButton(onClick = { state.dismiss(); it.onClick() }) { Text(it.label) } }
            }
          },
          caretShape = TooltipDefaults.caretShape(),
        ) { Text(text) }
      },
      state = state,
      focusable = focusable,
      enableUserInput = false,
      hasAction = true,
    ) { Box(Modifier.fillMaxSize()) }
  }
}

/**
 * Tells [tips] when each touch or click here ends ([Tips.gestureEnded]), so a tip that just left the screen holds the
 * next back only until then (#538). It only watches: the gesture goes on to whatever takes it. A tip's own Got it is
 * in its popup, not under this, so it doesn't count.
 */
fun Modifier.endsTipRest(tips: Tips): Modifier = pointerInput(tips) {
  awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    do {
      val event = awaitPointerEvent(PointerEventPass.Initial)
    } while (event.changes.any { it.pressed })
    tips.gestureEnded()
  }
}

/** A tip's second button after Got it ([TipAnchor]), such as "Show queue" (#559): it dismisses the tip and acts. */
class TipAction(val label: String, val onClick: () -> Unit)

/** How long a tip waits, once due, for the motion around its anchor to end. */
private const val TIP_SETTLE_MS = 600L
