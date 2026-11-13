package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `bookmark` (weight 400, grade 0, optical size 24). */
public val bookmark: ImageVector
  get() {
    if (_bookmark != null) {
      return _bookmark!!
    }
    _bookmark =
      ImageVector.Builder(
        name = "bookmark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M200-120v-640q0-33 23.5-56.5T280-840h400q33 0 56.5 23.5T760-760v640L480-240 200-120Zm80-122 200-86 200 86v-518H280v518Zm0-518h400-400Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _bookmark!!
  }

private var _bookmark: ImageVector? = null
