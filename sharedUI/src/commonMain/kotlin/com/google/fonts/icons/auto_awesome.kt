package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `auto_awesome` (weight 400, grade 0, optical size 24). */
public val auto_awesome: ImageVector
  get() {
    if (_auto_awesome != null) {
      return _auto_awesome!!
    }
    _auto_awesome =
      ImageVector.Builder(
        name = "auto_awesome",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
        .apply {
          addPath(
            pathData = addPathNodes("m19 9-1.25-2.75L15 5l2.75-1.25L19 1l1.25 2.75L23 5l-2.75 1.25Zm0 14-1.25-2.75L15 19l2.75-1.25L19 15l1.25 2.75L23 19l-2.75 1.25ZM9 20l-2.5-5.5L1 12l5.5-2.5L9 4l2.5 5.5L17 12l-5.5 2.5Zm0-4.85L10 13l2.15-1L10 11 9 8.85 8 11l-2.15 1L8 13ZM9 12Z"),
            fill = SolidColor(Color.Black),
          )
        }
        .build()
    return _auto_awesome!!
  }

private var _auto_awesome: ImageVector? = null
