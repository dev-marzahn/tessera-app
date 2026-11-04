package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Viewer choices that hold across posts, grids and tabs for the rest of the session. Not saved to disk. */
class ViewerPreferences {
  /** The info panel is hidden and the image panel takes its space (the I key). */
  var infoCollapsed by mutableStateOf(false)
}
