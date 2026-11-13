package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tab switcher (#461): it opens on the active tab, W/A/S/D move the selection, Space goes to the selected tab as a
 * click on its tab does and closes the switcher, X closes the selected tab and keeps it open, Esc closes it, and a click
 * on a card goes there.
 */
@OptIn(ExperimentalTestApi::class)
class TabSwitcherTest {
  private val tabs = TabsController()
  private var open by mutableStateOf(true)

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  init {
    tabs.open(Route.Grid(listOf("a")), background = false)
    for (i in 1..5) tabs.open(post("p$i"), background = true)
  }

  private val ids get() = tabs.tabs.value.map { it.id }
  private fun indexOf(id: TabId) = ids.indexOf(id)

  private fun ComposeUiTest.show() {
    setContent {
      SankakuTheme {
        TabSwitcher(open, onClose = { open = false }, controller = tabs, tabPosts = TabPosts.None, privacy = PrivacySettings(InMemoryTextFile()))
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.press(key: Key) {
    onNodeWithTag(TAB_SWITCHER_TAG).performKeyInput { pressKey(key) }
    waitForIdle()
  }

  @Test
  fun opensOnTheActiveTab() = runComposeUiTest {
    show()
    val active = indexOf(tabs.active.value)
    onNodeWithTag(switcherCardTag(active)).assertIsSelected()
    onNodeWithTag(switcherCardTag(active + 1)).assertIsNotSelected()
  }

  @Test
  fun dMovesTheSelectionAndSpaceGoesThere() = runComposeUiTest {
    show()
    val from = indexOf(tabs.active.value)
    press(Key.D)
    onNodeWithTag(switcherCardTag(from + 1)).assertIsSelected()
    onNodeWithTag(switcherCardTag(from)).assertIsNotSelected()

    press(Key.Spacebar)
    assertEquals(ids[from + 1], tabs.active.value)
    assertFalse(open)
  }

  @Test
  fun xClosesTheSelectedTabAndStaysOpen() = runComposeUiTest {
    show()
    val from = indexOf(tabs.active.value)
    press(Key.D)
    val closed = ids[from + 1]
    val next = ids[from + 2]
    press(Key.X)

    assertFalse(closed in ids)
    assertTrue(open)
    // The card that took its place is selected; the active tab is unchanged.
    assertEquals(from + 1, indexOf(next))
    onNodeWithTag(switcherCardTag(from + 1)).assertIsSelected()
    assertEquals(ids[from], tabs.active.value)
  }

  @Test
  fun escClosesItWhereItWas() = runComposeUiTest {
    show()
    val active = tabs.active.value
    press(Key.D)
    press(Key.Escape)
    assertFalse(open)
    assertEquals(active, tabs.active.value)
  }

  @Test
  fun aClickOnACardGoesThere() = runComposeUiTest {
    show()
    val last = ids.lastIndex
    onNodeWithTag(switcherCardTag(last)).performClick()
    waitForIdle()
    assertEquals(ids[last], tabs.active.value)
    assertFalse(open)
  }
}
