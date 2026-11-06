package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `check` (weight 400, grade 0, optical size 24). */
public val check: ImageVector
  get() {
    if (_check != null) {
      return _check!!
    }
    _check =
      ImageVector.Builder(
        name = "check",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M382-240 154-468l57-57 171 171 367-367 57 57-424 424Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _check!!
  }

private var _check: ImageVector? = null
