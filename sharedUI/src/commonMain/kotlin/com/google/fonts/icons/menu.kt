package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `menu` (weight 400, grade 0, optical size 24). */
public val menu: ImageVector
  get() {
    if (_menu != null) {
      return _menu!!
    }
    _menu =
      ImageVector.Builder(
        name = "menu",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M120-240v-80h720v80H120Zm0-200v-80h720v80H120Zm0-200v-80h720v80H120Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _menu!!
  }

private var _menu: ImageVector? = null
