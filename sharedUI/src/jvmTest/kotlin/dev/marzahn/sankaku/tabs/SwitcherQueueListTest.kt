package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import coil3.SingletonImageLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The switcher's queue list (#502): Q changes the view, the rows group by block with what's left, W/S move, Space goes,
 * X closes an item, Shift+X its block, and `/` finds.
 */
@OptIn(ExperimentalTestApi::class)
class SwitcherQueueListTest {
  private val tabs = TabsController()
  private val seen = SeenStore(InMemoryTextFile())
  private var open by mutableStateOf(true)
  private var list by mutableStateOf(false)

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  private fun keep(id: String, from: String) = tabs.openInBlock(listOf(Route.Grid(listOf(from), at = id), post(id)))

  init {
    tabs.open(Route.Grid(listOf("landscape")), background = false)
    keep("l1", "landscape")
    keep("l2", "landscape")
    tabs.open(Route.Grid(listOf("cat")), background = false)
    keep("c1", "cat")
  }

  private fun id(title: String) = tabs.tabs.value.single { it.title == title }.id

  // A row's picture may make Coil's singleton loader; later classes set their own.
  @AfterTest
  fun tearDown() = SingletonImageLoader.reset()

  private fun ComposeUiTest.show() {
    setContent {
      SankakuTheme {
        TabSwitcher(
          open,
          onClose = { open = false },
          list = list,
          onList = { list = it },
          controller = tabs,
          tabPosts = TabPosts.None,
          privacy = PrivacySettings(InMemoryTextFile()),
          seen = seen,
          clearViewed = ClearViewed(tabs, seen, Messages()),
        )
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.press(key: Key, shift: Boolean = false) {
    onNodeWithTag(TAB_SWITCHER_TAG).performKeyInput {
      if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
    }
    waitForIdle()
  }

  private fun ComposeUiTest.row(title: String) = onNodeWithTag(queueListRowTag(id(title)), useUnmergedTree = true)

  @Test
  fun qShowsTheQueueListAndBack() = runComposeUiTest {
    show()
    press(Key.Q)
    assertTrue(list)
    onNodeWithTag(QUEUE_LIST_TAG, useUnmergedTree = true).assertExists()
    press(Key.Q)
    assertFalse(list)
  }

  @Test
  fun rowsSayTheirBlockAndWhatsLeft() = runComposeUiTest {
    list = true
    seen.record("l1", null)
    show()
    onNodeWithTag(QUEUE_LIST_COUNT_TAG, useUnmergedTree = true).assertTextContains("6 items · 2 kept left")
    row("Landscape").assert(hasContentDescription("1 of 2 left", substring = true))
    row("l2").assert(hasContentDescription("Kept from Landscape", substring = true))
    row("l1").assert(hasContentDescription("seen", substring = true))
    // It opens on the current item.
    row("Cat").assertIsSelected()
  }

  @Test
  fun wMovesAndSpaceGoes() = runComposeUiTest {
    list = true
    show()
    press(Key.W)
    row("l2").assertIsSelected()
    press(Key.Spacebar)
    assertEquals(id("l2"), tabs.active.value)
    assertFalse(open)
  }

  @Test
  fun xClosesTheItemAndShiftXItsBlock() = runComposeUiTest {
    list = true
    show()
    press(Key.S)
    press(Key.X)
    assertEquals(listOf("Browse", "Landscape", "l1", "l2", "Cat"), tabs.tabs.value.map { it.title })
    // The selection took the closed one's place. Shift+X on l2 closes the whole Landscape block.
    row("Cat").assertIsSelected()
    press(Key.W)
    row("l2").assertIsSelected()
    press(Key.X, shift = true)
    assertEquals(listOf("Browse", "Cat"), tabs.tabs.value.map { it.title })
    assertTrue(open)
    // Z brings the block back as one.
    press(Key.Z)
    assertEquals(listOf("Browse", "Landscape", "l1", "l2", "Cat"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun rOpensTheBulkCloses() = runComposeUiTest {
    list = true
    show()
    press(Key.R)
    onNodeWithText("Close all but current (5)").assertExists()
  }

  @Test
  fun findListsAKeptItemWithoutItsHeadsProgress() = runComposeUiTest {
    list = true
    show()
    press(Key.Slash)
    onNodeWithTag(QUEUE_LIST_FIND_TAG).performTextInput("l2")
    waitForIdle()
    row("l2").assert(hasContentDescription("left", substring = true).not())
  }

  @Test
  fun slashFindsByTitle() = runComposeUiTest {
    list = true
    show()
    press(Key.Slash)
    onNodeWithTag(QUEUE_LIST_FIND_TAG).performTextInput("l1")
    waitForIdle()
    row("l1").assertExists()
    row("c1").assertDoesNotExist()
  }
}
