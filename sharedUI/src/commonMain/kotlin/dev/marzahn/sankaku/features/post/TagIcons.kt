package dev.marzahn.sankaku.features.post

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A Material Symbols glyph from its path in the symbols' 0 -960 960 960 viewBox. */
private fun symbol(name: String, path: String): ImageVector =
  ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f)
    .addGroup(translationY = 960f)
    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
    .clearGroup()
    .build()

/** A followed tag (Material Symbols `notifications`, filled). */
val TagFollowed: ImageVector by lazy {
  symbol(
    "notifications_filled",
    "M160-200v-80h80v-280q0-83 50-147.5T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5" +
      "T720-560v280h80v80H160ZM480-80q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80Z",
  )
}

/** Follow a tag (Material Symbols `notifications`, outlined). */
val TagFollow: ImageVector by lazy {
  symbol(
    "notifications",
    "M160-200v-80h80v-280q0-83 50-147.5T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5" +
      "T720-560v280h80v80H160Zm320-300Zm0 420q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280" +
      "q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z",
  )
}

/** Exclude a tag from the search (Material Symbols `remove`). */
val TagExclude: ImageVector by lazy { symbol("remove", "M200-440v-80h560v80H200Z") }

/** Copy a tag's name (Material Symbols `content_copy`, outlined). */
val TagCopy: ImageVector by lazy {
  symbol(
    "content_copy",
    "M360-240q-33 0-56.5-23.5T280-320v-480q0-33 23.5-56.5T360-880h360q33 0 56.5 23.5T800-800v480q0 33-23.5 56.5" +
      "T720-240H360Zm0-80h360v-480H360v480ZM200-80q-33 0-56.5-23.5T120-160v-560h80v560h440v80H200Zm160-240v-480 480Z",
  )
}
