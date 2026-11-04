package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `play_arrow` (weight 400, grade 0, optical size 24, Fill on). */
public val play_arrow_filled: ImageVector
  get() {
    if (_play_arrow_filled != null) {
      return _play_arrow_filled!!
    }
    _play_arrow_filled =
      ImageVector.Builder(
        name = "play_arrow_filled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M320-200v-560l440 280-440 280Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _play_arrow_filled!!
  }

private var _play_arrow_filled: ImageVector? = null
