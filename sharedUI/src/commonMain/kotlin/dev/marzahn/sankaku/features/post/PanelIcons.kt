package dev.marzahn.sankaku.features.post

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A window with a side panel on the right and a chevron in the main area: towards the panel hides it, away shows it. */
private fun panelIcon(name: String, chevron: String): ImageVector =
  ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
      pathData = addPathNodes("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm0 2v14h9V5H5zm11 0v14h3V5h-3z$chevron"),
      fill = SolidColor(Color.Black),
    )
    .build()

/** Hides the info panel (the chevron points into the panel, the way it leaves). */
val RightPanelClose: ImageVector by lazy { panelIcon("right_panel_close", "M11.4 12l-3-3-1.06 1.06L9.28 12l-1.94 1.94L8.4 15z") }

/** Shows the info panel (the chevron points out of it, the way it arrives). */
val RightPanelOpen: ImageVector by lazy { panelIcon("right_panel_open", "M6.6 12l3 3 1.06-1.06L8.72 12l1.94-1.94L9.6 9z") }
