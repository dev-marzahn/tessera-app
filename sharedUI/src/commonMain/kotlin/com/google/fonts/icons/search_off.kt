package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `search_off` (weight 400, 24 px), its path as the SVG gives it. */
public val search_off: ImageVector
  get() {
    _search_off?.let { return it }
    return ImageVector.Builder(
      name = "search_off",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 960f,
      viewportHeight = 960f,
    )
      .addGroup(translationY = 960f)
      .addPath(
        addPathNodes(
          "M138.5-138.5Q80-197 80-280t58.5-141.5Q197-480 280-480t141.5 58.5Q480-363 480-280t-58.5 141.5Q363-80 280-80" +
            "t-141.5-58.5ZM824-120 568-376q-12-13-25.5-26.5T516-428q38-24 61-64t23-88q0-75-52.5-127.5T420-760q-75 0-127.5 " +
            "52.5T240-580q0 6 .5 11.5T242-557q-18 2-39.5 8T164-535q-2-11-3-22t-1-23q0-109 75.5-184.5T420-840q109 0 184.5 " +
            "75.5T680-580q0 43-13.5 81.5T629-428l251 252-56 56Zm-615-61 71-71 70 71 29-28-71-71 71-71-28-28-71 71-71-71-28 " +
            "28 71 71-71 71 28 28Z",
        ),
        fill = SolidColor(Color.Black),
      )
      .clearGroup()
      .build()
      .also { _search_off = it }
  }

private var _search_off: ImageVector? = null
