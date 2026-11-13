package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.Route

/**
 * Cmd/Ctrl-T and the tab strip's + (#542, desktop): a Browse tab comes forward with its search panel open, so what's
 * typed next goes into the search, not to the grid's letter keys. The shell opens the panel once the tab is shown
 * ([take]); Browse's source, when one is open, comes forward instead of a second tab (one source per search).
 */
class NewTab(private val tabs: TabsController) {
  /** The tab whose search should open as it's shown, until the shell [take]s it. */
  var searchFor: TabId? by mutableStateOf(null)
    private set

  fun open() {
    tabs.open(Route.Grid(emptyList()), background = false)
    searchFor = tabs.active.value
  }

  /**
   * Whether [shown]'s search should open now, which uses the request up. A request for another tab stays: the shell
   * may see it a frame before it sees that tab shown.
   */
  fun take(shown: TabId): Boolean {
    if (searchFor != shown) return false
    searchFor = null
    return true
  }
}
