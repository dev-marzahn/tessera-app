package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `star`, filled (weight 400, grade 0, optical size 24). */
public val star_filled: ImageVector
  get() {
    if (_star_filled != null) {
      return _star_filled!!
    }
    _star_filled =
      ImageVector.Builder(
        name = "star_filled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("m233-120 65-281L80-590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _star_filled!!
  }

private var _star_filled: ImageVector? = null
