package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `arrow_upward` (weight 400, grade 0, optical size 24). */
public val arrow_upward: ImageVector
  get() {
    if (_arrow_upward != null) {
      return _arrow_upward!!
    }
    _arrow_upward =
      ImageVector.Builder(
        name = "arrow_upward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M440-160v-487L216-423l-56-57 320-320 320 320-56 57-224-224v487h-80Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _arrow_upward!!
  }

private var _arrow_upward: ImageVector? = null
