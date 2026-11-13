package dev.marzahn.sankaku.shell

import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.features.settings.SettingsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sidebar opened over Settings (#460) or the phone's queue page (#478): what it opens closes them. */
class SidebarNavigatorTest {
  private class RecordingNavigator : Navigator {
    val calls = mutableListOf<Pair<String, Route>>()

    override fun goTo(route: Route) {
      calls += "goTo" to route
    }

    override fun goBack() = Unit

    override fun replaceTop(route: Route) = Unit

    override fun openInNewTab(route: Route): Int? {
      calls += "openInNewTab" to route
      return null
    }
  }

  @Test
  fun aGridOpenedFromTheSidebarOverSettingsOrTheQueuePageOpensInTheTabAndClosesThem() {
    val grid = Route.Grid(listOf("artist_x"))
    for (inNewTab in listOf(false, true)) {
      val navigator = RecordingNavigator()
      val settings = SettingsController().apply { open() }
      // The sidebar opened from the queue page's ☰ shows what it opens, not the page over it.
      var queuePageOpen = true
      val sidebar = SidebarNavigator(navigator, settings, closeQueuePage = { queuePageOpen = false })

      if (inNewTab) sidebar.openInNewTab(grid) else sidebar.goTo(grid)

      assertFalse(settings.isOpen.value)
      assertFalse(queuePageOpen)
      assertEquals(listOf<Pair<String, Route>>((if (inNewTab) "openInNewTab" else "goTo") to grid), navigator.calls)
    }
  }

  @Test
  fun backAndReplaceTopLeaveSettingsAndTheQueuePageOpen() {
    val navigator = RecordingNavigator()
    val settings = SettingsController().apply { open() }
    var queuePageOpen = true
    val sidebar = SidebarNavigator(navigator, settings, closeQueuePage = { queuePageOpen = false })

    sidebar.goBack()
    sidebar.replaceTop(Route.Grid(emptyList()))

    assertTrue(settings.isOpen.value)
    assertTrue(queuePageOpen)
  }
}
