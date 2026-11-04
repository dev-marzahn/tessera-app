package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `edit` (weight 400, 24 px), its path as the SVG gives it. */
public val edit: ImageVector
  get() {
    _edit?.let { return it }
    return ImageVector.Builder(
      name = "edit",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 960f,
      viewportHeight = 960f,
    )
      .addGroup(translationY = 960f)
      .addPath(
        addPathNodes(
          "M200-200h57l391-391-57-57-391 391v57Zm-80 80v-170l528-527q12-11 26.5-17t30.5-6q16 0 31 6t26 18l55 56q12 11 " +
            "17.5 26t5.5 30q0 16-5.5 30.5T817-647L290-120H120Zm640-584-56-56 56 56Zm-141 85-28-29 57 57-29-28Z",
        ),
        fill = SolidColor(Color.Black),
      )
      .clearGroup()
      .build()
      .also { _edit = it }
  }

private var _edit: ImageVector? = null
