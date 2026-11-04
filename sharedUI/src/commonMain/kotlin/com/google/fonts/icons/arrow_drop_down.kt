package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `arrow_drop_down` (weight 400, grade 0, optical size 24). */
public val arrow_drop_down: ImageVector
  get() {
    if (_arrow_drop_down != null) {
      return _arrow_drop_down!!
    }
    _arrow_drop_down =
      ImageVector.Builder(
        name = "arrow_drop_down",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M480-360 280-560h400L480-360Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _arrow_drop_down!!
  }

private var _arrow_drop_down: ImageVector? = null
