package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `play_arrow` (weight 400, grade 0, optical size 24). */
public val play_arrow: ImageVector
  get() {
    if (_play_arrow != null) {
      return _play_arrow!!
    }
    _play_arrow =
      ImageVector.Builder(
        name = "play_arrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M320-203v-560l440 280-440 280Zm60-280Zm0 171 269-171-269-171v342Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _play_arrow!!
  }

private var _play_arrow: ImageVector? = null
