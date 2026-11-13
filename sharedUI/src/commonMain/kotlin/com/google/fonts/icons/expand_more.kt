package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val expand_more: ImageVector
  get() {
    if (_expand_more != null) {
      return _expand_more!!
    }
    _expand_more =
      ImageVector.Builder(
        name = "expand_more",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(12f, 15.375f)
            lineTo(6f, 9.375f)
            lineTo(7.4f, 7.975f)
            lineTo(12f, 12.575f)
            lineTo(16.6f, 7.975f)
            lineTo(18f, 9.375f)
            lineTo(12f, 15.375f)
            close()
          }
        }
        .build()
    return _expand_more!!
  }

private var _expand_more: ImageVector? = null
