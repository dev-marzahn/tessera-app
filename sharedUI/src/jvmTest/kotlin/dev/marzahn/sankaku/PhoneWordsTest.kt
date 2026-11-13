package dev.marzahn.sankaku

import dev.marzahn.sankaku.features.grid.openInBackgroundLabel
import dev.marzahn.sankaku.features.post.forLaterLabel
import dev.marzahn.sankaku.features.settings.clearEverythingConfirmText
import dev.marzahn.sankaku.tabs.batchTitle
import dev.marzahn.sankaku.tabs.tabTitle
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.components.alreadyLabel
import dev.marzahn.sankaku.ui.components.alreadySpoken
import dev.marzahn.sankaku.ui.components.inTabLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone has no tabs, so its words are the queue's (#249); desktop keeps "tab" while it has the tab strip. */
class PhoneWordsTest {
  @Test
  fun thePhoneSaysKeptAndItems() {
    // No double-tap on grid cells (#324), so the tip teaches the long-press only.
    assertEquals("Long-press a post to keep it.", Tip.GridGestures.text)
    assertEquals("Kept", inTabLabel(touch = true))
    assertEquals("Already item 4", alreadyLabel(4, touch = true))
    assertEquals("Already item 4", alreadySpoken(4, touch = true))
    assertEquals("Keep", openInBackgroundLabel(touch = true))
    assertEquals("12 items", batchTitle(12, touch = true))
    assertEquals("Item", tabTitle(null, touch = true))
    assertEquals("Search only this tag for later", forLaterLabel("Search only this tag"))
    val clear = clearEverythingConfirmText(touch = true)
    assertTrue(clear.startsWith("Empties the queue and Recently closed, and clears the image and video caches"))
    for (words in listOf(clear, forLaterLabel("Add to search")) + Tip.entries.map { it.text }) {
      assertFalse(Regex("\\btabs?\\b", RegexOption.IGNORE_CASE).containsMatchIn(words), words)
    }
  }

  @Test
  fun desktopKeepsItsTabs() {
    assertEquals("In tab", inTabLabel(touch = false))
    assertEquals("Already tab 4", alreadyLabel(4, touch = false))
    assertEquals("Already in tab 4", alreadySpoken(4, touch = false))
    assertEquals("Open in background tab", openInBackgroundLabel(touch = false))
    assertEquals("12 tabs", batchTitle(12, touch = false))
    assertEquals("Tab", tabTitle(null, touch = false))
    assertEquals(
      "Closes all tabs and the recently closed ones, and clears the image cache and the tags and searches the app " +
        "remembers. You stay signed in. Posts you saved to Download/Tessera stay.",
      clearEverythingConfirmText(touch = false),
    )
  }
}
