package dev.marzahn.sankaku.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings over the app (#205, #460): its sections in order. The sidebar's grids closing it: `SidebarNavigatorTest`. */
class OpenSettingsTest {
  @Test
  fun theSectionsAreInTheSpecsOrderAndTheSlotsShowOnceFilled() {
    assertEquals(
      listOf("Account", "Privacy", "Queue", "Grid", "Videos", "Accessibility", "Storage", "Updates", "Diagnostics", "About"),
      shownSections(touch = true).map { it.title },
    )
    // Accessibility's one switch is for the phone's count pill; both have Grid's Post size (#468). Keyboard (#521) is the
    // desktop's: the shortcut sheet.
    assertEquals(
      listOf("Account", "Privacy", "Queue", "Grid", "Videos", "Keyboard", "Storage", "Updates", "Diagnostics", "About"),
      shownSections(touch = false).map { it.title },
    )
    // Updates (#209), Diagnostics (#207) and About (#206) have their places, after Storage.
    assertEquals(
      listOf("Account", "Privacy", "Queue", "Grid", "Videos", "Keyboard", "Storage", "Updates", "Diagnostics", "About"),
      shownSections(touch = false, updates = true, diagnostics = true, about = true).map { it.title },
    )
  }
}
