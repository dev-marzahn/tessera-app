package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `volume_up` (weight 400, grade 0, optical size 24). */
public val volume_up: ImageVector
  get() {
    if (_volume_up != null) {
      return _volume_up!!
    }
    _volume_up =
      ImageVector.Builder(
        name = "volume_up",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes(
                "M560-131v-62q97-28 158.5-107.5T780-481q0-101-61-181T560-769v-62q124 28 202 125.5T840-481q0 127-78 224.5" +
                  "T560-131ZM120-360v-240h160l200-200v640L280-360H120Zm420 48v-337q55 17 87.5 64T660-480q0 57-33 104t-87 64Z" +
                  "M420-648 307-540H180v120h127l113 109v-337Zm-94 168Z",
              ),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _volume_up!!
  }

private var _volume_up: ImageVector? = null
