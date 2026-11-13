package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `open_in_new` (weight 400, grade 0, optical size 24). */
public val open_in_new: ImageVector
  get() {
    if (_open_in_new != null) {
      return _open_in_new!!
    }
    _open_in_new =
      ImageVector.Builder(
        name = "open_in_new",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h280v80H200v560h560v-280h80v280q0 33-23.5 56.5T760-120H200Zm188-212-56-56 372-372H560v-80h280v280h-80v-144L388-332Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _open_in_new!!
  }

private var _open_in_new: ImageVector? = null
