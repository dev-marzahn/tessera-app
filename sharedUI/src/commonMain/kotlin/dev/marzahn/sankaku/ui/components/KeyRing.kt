package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.theme.Amber

/**
 * The keys' selection in a list W/S move through (the sidebar, #416): a 3 dp ring in the grid cursor's amber, inside
 * [shape], while focus is on this element or in it. A mouse click doesn't move focus, so only the keys draw it.
 */
fun Modifier.keyRing(shape: Shape): Modifier = composed {
  var focused by remember { mutableStateOf(false) }
  onFocusChanged { focused = it.hasFocus }
    .then(if (focused) Modifier.border(3.dp, Amber, shape) else Modifier)
}
