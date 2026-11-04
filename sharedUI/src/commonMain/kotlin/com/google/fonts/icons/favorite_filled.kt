package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `favorite` (weight 400, grade 0, optical size 24, Fill on). */
public val favorite_filled: ImageVector
  get() {
    if (_favorite_filled != null) {
      return _favorite_filled!!
    }
    _favorite_filled =
      ImageVector.Builder(
        name = "favorite_filled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
      )
        .apply {
          group(translationY = 960f) {
            addPath(
              pathData = addPathNodes("m480-120-58-52q-101-91-167-157T150-447.5Q111-500 95.5-544T80-634q0-94 63-157t157-63q52 0 99 22t81 62q34-40 81-62t99-22q94 0 157 63t63 157q0 46-15.5 90T810-447.5Q771-395 705-329T538-172l-58 52Z"),
              fill = SolidColor(Color.Black),
            )
          }
        }
        .build()
    return _favorite_filled!!
  }

private var _favorite_filled: ImageVector? = null
