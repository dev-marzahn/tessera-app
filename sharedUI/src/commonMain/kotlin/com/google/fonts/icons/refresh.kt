package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `refresh` (weight 400, grade 0, optical size 24). */
public val refresh: ImageVector
  get() {
    if (_refresh != null) {
      return _refresh!!
    }
    _refresh =
      ImageVector.Builder(
        name = "refresh",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes(
                "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168" +
                  "q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84" +
                  "q-28 106-114 173t-196 67Z",
              ),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _refresh!!
  }

private var _refresh: ImageVector? = null
