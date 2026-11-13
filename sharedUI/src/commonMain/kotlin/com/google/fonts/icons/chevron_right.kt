package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `chevron_right` (weight 400, grade 0, optical size 24). */
public val chevron_right: ImageVector
  get() {
    if (_chevron_right != null) {
      return _chevron_right!!
    }
    _chevron_right =
      ImageVector.Builder(
        name = "chevron_right",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _chevron_right!!
  }

private var _chevron_right: ImageVector? = null
