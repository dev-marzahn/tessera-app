package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `chevron_left` (weight 400, grade 0, optical size 24). */
public val chevron_left: ImageVector
  get() {
    if (_chevron_left != null) {
      return _chevron_left!!
    }
    _chevron_left =
      ImageVector.Builder(
        name = "chevron_left",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M560-240 320-480l240-240 56 56-184 184 184 184-56 56Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _chevron_left!!
  }

private var _chevron_left: ImageVector? = null
