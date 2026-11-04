package dev.marzahn.sankaku.features.settings

import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sidebar's Settings (#205): a tab on desktop, the page over the app on a phone, for a tap and a long-press. */
class OpenSettingsTest {
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
  fun desktopOpensItAsATabHereOrInTheBackground() {
    val navigator = RecordingNavigator()
    val controller = SettingsController()

    openSettingsFromSidebar(touch = false, inNewTab = false, navigator, controller)
    openSettingsFromSidebar(touch = false, inNewTab = true, navigator, controller)

    assertEquals(listOf<Pair<String, Route>>("goTo" to Route.Settings, "openInNewTab" to Route.Settings), navigator.calls)
    assertFalse(controller.isOpen.value)
  }

  @Test
  fun aPhoneOpensThePageForATapAndALongPressAndAddsNothingToTheQueue() {
    for (inNewTab in listOf(false, true)) {
      val navigator = RecordingNavigator()
      val controller = SettingsController()

      openSettingsFromSidebar(touch = true, inNewTab = inNewTab, navigator, controller)

      assertTrue(controller.isOpen.value)
      assertTrue(navigator.calls.isEmpty())
    }
  }

  @Test
  fun theSectionsAreInTheSpecsOrderAndTheSlotsShowOnceFilled() {
    assertEquals(
      listOf("Account", "Privacy", "Queue", "Accessibility", "Storage", "Updates", "Diagnostics", "About"),
      shownSections(touch = true).map { it.title },
    )
    // Accessibility's one switch is for the phone's count pill.
    assertEquals(
      listOf("Account", "Privacy", "Queue", "Storage", "Updates", "Diagnostics", "About"),
      shownSections(touch = false).map { it.title },
    )
    // Updates (#209), Diagnostics (#207) and About (#206) have their places, after Storage.
    assertEquals(
      listOf("Account", "Privacy", "Queue", "Storage", "Updates", "Diagnostics", "About"),
      shownSections(touch = false, updates = true, diagnostics = true, about = true).map { it.title },
    )
  }
}
