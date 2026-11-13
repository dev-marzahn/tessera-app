package dev.marzahn.sankaku.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.theme.Amber

/**
 * The keyboard's ring: [FocusRingWidth] of brand amber, [FocusRingGap] outside the element. The grid cursor's too: 7 dp
 * in all, so in the grid's 12 dp gap it stays 5 dp clear of the next post (owner decision 2026-11-09, #459).
 */
val FocusRingGap: Dp = 2.dp
val FocusRingWidth: Dp = 5.dp

/**
 * The keyboard's ring around the element while it, or something in it, has focus, in [shape] grown by the gap. For
 * buttons where Enter acts on the focused one (the first-launch screen, #418), whose own focus state layer is too
 * faint to tell which that is.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
  var focused by remember { mutableStateOf(false) }
  onFocusChanged { focused = it.hasFocus }.drawWithContent {
    drawContent()
    if (!focused) return@drawWithContent
    val ring = FocusRingWidth.toPx()
    val out = FocusRingGap.toPx() + ring / 2
    val outline = shape.createOutline(Size(size.width + 2 * out, size.height + 2 * out), layoutDirection, this)
    translate(-out, -out) { drawOutline(outline, Amber, style = Stroke(ring)) }
  }
}
