package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `volume_off` (weight 400, grade 0, optical size 24). */
public val volume_off: ImageVector
  get() {
    if (_volume_off != null) {
      return _volume_off!!
    }
    _volume_off =
      ImageVector.Builder(
        name = "volume_off",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes(
                "M813-56 681-188q-28 20-60.5 34.5T553-131v-62q23-7 44.5-15.5T638-231L473-397v237L273-360H113v-240h156" +
                  "L49-820l43-43 764 763-43 44Zm-36-232-43-43q20-34 29.5-71.92Q773-440.85 773-481q0-103.32-60-184.66" +
                  "T553-769v-62q124 28 202 125.5T833-481q0 51-14 100t-42 93ZM643-422l-90-90v-130q47 22 73.5 66t26.5 96" +
                  "q0 15-2.5 29.5T643-422ZM473-592 369-696l104-104v208Zm-60 286v-150l-84-84H173v120h126l114 114Zm-42-192Z",
              ),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _volume_off!!
  }

private var _volume_off: ImageVector? = null
