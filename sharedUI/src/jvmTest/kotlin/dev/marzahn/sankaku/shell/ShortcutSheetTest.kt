package dev.marzahn.sankaku.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.marzahn.sankaku.ui.components.ShortcutGroups
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.matching
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #521: the keyboard shortcuts sheet lists every key from [Shortcuts], by where it works, finds by what a key does or
 * by the key, and closes on ? and Esc.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutSheetTest {
  private val controller = ShortcutSheetController()

  private fun ComposeUiTest.sheet() {
    controller.open()
    setContent { SankakuTheme { ShortcutSheet(controller) } }
    waitForIdle()
  }

  /** Keys go to what has the focus in the sheet's popup, a root of its own. */
  private fun ComposeUiTest.sheetKeys() = onNodeWithTag(SHORTCUTS_SCROLL_TAG)

  private fun heading(title: String) = hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

  @Test
  fun itListsEveryGroupUnderHeadings() = runComposeUiTest {
    sheet()

    onNode(heading(SHORTCUTS_TITLE)).assertExists()
    listOf("Anywhere", "Tabs", "Grid", "Viewer", "Tag list", "Video", "Book reader", "Tab switcher", "Menus").forEach {
      onNode(heading(it), useUnmergedTree = true).assertExists(it)
    }
    // A row is one node: what it does and its keys, "or" between the ways.
    onNode(hasText("Close tab") and hasText("X") and hasText("or") and hasText("⌘W")).assertExists()
  }

  @Test
  fun everyKeyOfShortcutsIsListed() {
    val listed = ShortcutGroups.flatMap { group -> group.rows.flatMap { it.keys.flatten() } }.toSet()
    with(Shortcuts) {
      listOf(
        Back, Favourite, Previous, Next, Up, Down, Details, Search, Find, ReopenTab, CloseTab, PreviousTab, NextTab,
        ClearViewed, NewTab, CloseTabCommand, ReopenTabCommand, NextTabCommand, PreviousTabCommand, Refresh, Help,
        Sidebar, TabSwitcher, SwitcherMode, CloseBlock, TagList, OpenInTab, OpenHere, Include, Exclude, Remove, Menu,
        PlayPause, Mute, Scrub, Vote,
      ).forEach { assertTrue(it.label in listed, "${it.label} isn't listed") }
    }
  }

  @Test
  fun findMatchesWhatAKeyDoesOrTheKey() {
    // By what it does: every "close" row, in every group that has one.
    val close = ShortcutGroups.matching("close tab")
    assertEquals(listOf("Tabs"), close.map { it.title })
    // By the key, ignoring case: X closes a tab and, in the switcher, its row.
    val x = ShortcutGroups.matching("x")
    assertEquals(listOf("Close tab"), x.single { it.title == "Tabs" }.rows.map { it.action })
    assertTrue(x.any { it.title == "Tab switcher" })
    // By where it works: the whole group.
    assertEquals(ShortcutGroups.single { it.title == "Video" }, ShortcutGroups.matching("video").single())
    assertTrue(ShortcutGroups.matching("nothing like this").isEmpty())
    assertEquals(ShortcutGroups, ShortcutGroups.matching("  "))
  }

  @Test
  fun slashFindsAndTheListNarrows() = runComposeUiTest {
    sheet()

    sheetKeys().performKeyInput { pressKey(Key.Slash) }
    waitForIdle()
    onNodeWithTag(SHORTCUTS_FIND_TAG).assertIsFocused()
    onNodeWithTag(SHORTCUTS_FIND_TAG).performTextInput("mute")
    waitForIdle()

    onNode(hasText("Mute") and hasText("M")).assertExists()
    onNodeWithText("Favourite").assertDoesNotExist()
    onNodeWithTag(SHORTCUTS_FIND_TAG).performTextInput("zzz")
    waitForIdle()
    onNodeWithText("No shortcut matches “mutezzz”").assertExists()
  }

  @Test
  fun questionMarkAndEscClose() = runComposeUiTest {
    sheet()

    sheetKeys().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Slash) } }
    waitForIdle()
    assertFalse(controller.isOpen.value, "? closes it")

    controller.open()
    waitForIdle()
    sheetKeys().performKeyInput { pressKey(Key.Escape) }
    waitForIdle()
    assertFalse(controller.isOpen.value, "Esc closes it")
  }
}
