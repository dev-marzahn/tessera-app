package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `pause` (weight 400, grade 0, optical size 24). */
public val pause: ImageVector
  get() {
    if (_pause != null) {
      return _pause!!
    }
    _pause =
      ImageVector.Builder(
        name = "pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes(
                "M525-200v-560h235v560H525Zm-325 0v-560h235v560H200Zm385-60h115v-440H585v440Zm-325 0h115v-440H260v440Z" +
                  "m0-440v440-440Zm325 0v440-440Z",
              ),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _pause!!
  }

private var _pause: ImageVector? = null
