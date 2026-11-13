package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.marzahn.sankaku.platformBackKeys

/**
 * A modal side sheet over the whole window, at its right edge (#460, option A of its mockup): [SideSheetWidth] wide
 * (the window's width when that's narrower), its inner corners rounded, over a 32% scrim. It slides in on M3's
 * emphasized decelerate in 350 ms and out on emphasized accelerate in 250 ms, the scrim fading with it.
 *
 * It's a focusable popup while [open], so the keys and Tab stay in it, and anything opened from it (a confirmation, a
 * dialog) comes over it and closes first. Esc, the mouse back button and a click on the scrim call [onClose]. Closed,
 * it lets go of the focus at once, so the caller can hand the keys back while it slides out.
 */
@Composable
fun ModalSideSheet(
  open: Boolean,
  onClose: () -> Unit,
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val visible = remember { MutableTransitionState(false) }
  visible.targetState = open
  if (!visible.currentState && !visible.targetState) return
  val close by rememberUpdatedState(onClose)
  val transition = rememberTransition(visible)
  val scrim by transition.animateFloat(
    transitionSpec = {
      if (targetState) tween(SHEET_IN_MS, easing = EmphasizedDecelerate) else tween(SHEET_OUT_MS, easing = EmphasizedAccelerate)
    },
  ) { shown -> if (shown) SCRIM_ALPHA else 0f }
  Popup(
    popupPositionProvider = WholeWindow,
    onDismissRequest = { if (open) close() },
    properties = PopupProperties(focusable = open, dismissOnBackPress = open, dismissOnClickOutside = false),
  ) {
    Box(Modifier.fillMaxSize().platformBackKeys { if (open) close() }) {
      Box(
        Modifier
          .fillMaxSize()
          .graphicsLayer { alpha = scrim }
          .background(Color.Black)
          .testTag(SideSheetScrimTag)
          .pointerInput(open) { if (open) detectTapGestures { close() } },
      )
      AnimatedVisibility(
        visibleState = visible,
        enter = slideInHorizontally(tween(SHEET_IN_MS, easing = EmphasizedDecelerate)) { it },
        exit = slideOutHorizontally(tween(SHEET_OUT_MS, easing = EmphasizedAccelerate)) { it },
        modifier = Modifier.align(Alignment.CenterEnd),
      ) {
        Surface(
          shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          modifier = Modifier
            .widthIn(max = SideSheetWidth)
            .fillMaxWidth()
            .fillMaxHeight()
            // A pane, so screen readers announce it and treat it as the screen.
            .semantics { paneTitle = title },
        ) {
          Column(modifier.fillMaxSize(), content = content)
        }
      }
    }
  }
}

/** The sheet's width: M3's side sheets top out at 400 dp; Settings' rows and its slider want more (#460). */
val SideSheetWidth = 480.dp

internal const val SideSheetScrimTag = "side-sheet-scrim"

// The sheet's scrim and motion, which a tag's page on the desktop shares (#522).
internal const val SCRIM_ALPHA = 0.32f
internal const val SHEET_IN_MS = 350
internal const val SHEET_OUT_MS = 250

// M3's emphasized decelerate and accelerate curves.
internal val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Puts the popup at the window's top left; its content fills the window. */
private object WholeWindow : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset = IntOffset.Zero
}
