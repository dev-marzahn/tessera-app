package dev.marzahn.sankaku.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.marzahn.sankaku.platformIsTouch

/**
 * The opening rule used everywhere: a click calls [onOpen]; a middle click, or a click with Cmd or Ctrl held, calls
 * [onOpenInNewTab]. On [touch] a long-press is the middle click: it calls [onLongPress] (by default [onOpenInNewTab])
 * at once, with a haptic tick. A tap opens as the finger lifts: there's no double-tap, which would make every tap wait
 * out the system's double-tap timeout (`ViewConfiguration`, about 300 ms) first (#324). The pointer is only observed,
 * never consumed, so the click keeps its ripple and semantics. With an [interactionSource] the click draws no indication of its own: something inside shows it from that source.
 * [longPressLabel] names the long-press for TalkBack ("Double-tap and hold to …"). With [inTab] (a post some tab
 * already shows, so opening it in a new tab opens nothing) the long-press's haptic is the "reject" one instead of the
 * tick.
 */
fun Modifier.openOnClick(
  onOpen: () -> Unit,
  onOpenInNewTab: () -> Unit,
  interactionSource: MutableInteractionSource? = null,
  touch: Boolean = platformIsTouch,
  onLongPress: () -> Unit = onOpenInNewTab,
  longPressLabel: String? = null,
  inTab: Boolean = false,
): Modifier = composed {
  var modified by remember { mutableStateOf(false) }
  var middlePressed by remember { mutableStateOf(false) }
  // The pointer loop below starts once and outlives recompositions, so it must read the latest handler.
  val openInNewTab by rememberUpdatedState(onOpenInNewTab)
  val haptics = LocalHapticFeedback.current
  val click = { if (modified) onOpenInNewTab() else onOpen() }
  this
    .pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          when (event.type) {
            PointerEventType.Press -> {
              modified = event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed
              middlePressed = event.buttons.isTertiaryPressed
            }
            PointerEventType.Release -> if (middlePressed) {
              middlePressed = false
              openInNewTab()
            }
          }
        }
      }
    }
    .then(
      when {
        // Our own tick, not the long-press buzz the modifier would make: the post is queued, nothing more to come.
        touch -> Modifier.combinedClickable(
          interactionSource = interactionSource,
          indication = if (interactionSource == null) LocalIndication.current else null,
          hapticFeedbackEnabled = false,
          onLongClickLabel = longPressLabel,
          onLongClick = {
            haptics.performHapticFeedback(if (inTab) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            onLongPress()
          },
          onClick = click,
        )
        interactionSource == null -> Modifier.clickable(onClick = click)
        else -> Modifier.clickable(interactionSource, indication = null, onClick = click)
      },
    )
}
