package com.google.fonts.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `cloud_off`, 24 px, from its SVG (viewBox `0 -960 960 960`). */
public val cloud_off: ImageVector by lazy {
  symbol(
    "cloud_off",
    "M792-56 686-160H260q-92 0-156-64T40-380q0-77 47.5-137T210-594q3-8 6-15.5t6-16.5L56-792l56-56 736 736-56 56Z" +
      "M260-240h346L284-562q-2 11-3 21t-1 21h-20q-58 0-99 41t-41 99q0 58 41 99t99 41Zm185-161Zm419 191-58-56q17-14 " +
      "25.5-32.5T840-340q0-42-29-71t-71-29h-60v-80q0-83-58.5-141.5T480-720q-27 0-52 6.5T380-693l-58-58q35-24 74.5-" +
      "36.5T480-800q117 0 198.5 81.5T760-520q69 8 114.5 59.5T920-340q0 39-15 72.5T864-210ZM593-479Z",
  )
}

/** A Material Symbol from its SVG path, drawn in the symbol's own 960-unit box, which starts at y = -960. */
internal fun symbol(name: String, pathData: String): ImageVector =
  ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f)
    .addGroup(translationY = 960f)
    .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
    .clearGroup()
    .build()
