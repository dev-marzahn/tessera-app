package dev.marzahn.sankaku.shell

import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.features.settings.SettingsController

/**
 * The sidebar's [Navigator]: a grid or search it opens, here or in a new tab, closes Settings (#460) and the phone's
 * queue page (#478) first, so the sidebar opened over either lands on the tab as from anywhere else.
 */
internal class SidebarNavigator(
  private val navigator: Navigator,
  private val settings: SettingsController,
  private val closeQueuePage: () -> Unit,
) : Navigator by navigator {
  override fun goTo(route: Route) {
    closeCovers()
    navigator.goTo(route)
  }

  override fun openInNewTab(route: Route): Int? {
    closeCovers()
    return navigator.openInNewTab(route)
  }

  private fun closeCovers() {
    settings.close()
    closeQueuePage()
  }
}
