package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `play_disabled` (weight 400, grade 0, optical size 24, Fill on). */
public val play_disabled_filled: ImageVector
  get() {
    if (_play_disabled_filled != null) {
      return _play_disabled_filled!!
    }
    _play_disabled_filled =
      ImageVector.Builder(
        name = "play_disabled_filled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M658-416 320-754v-6l440 280-102 64ZM790-56 520-328 320-200v-328L56-792l56-56 736 736-58 56Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _play_disabled_filled!!
  }

private var _play_disabled_filled: ImageVector? = null
