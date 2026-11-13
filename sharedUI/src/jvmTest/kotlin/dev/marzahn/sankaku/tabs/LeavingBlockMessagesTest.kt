package dev.marzahn.sankaku.tabs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * #502: leaving a block whose kept posts were all seen puts it on offer for Shift+X, and the offer ends with its
 * message: when another message replaces it, or when the offered block is shown again.
 */
@OptIn(ExperimentalTestApi::class)
class LeavingBlockMessagesTest {
  private val tabs = TabsController()

  // Seen an hour on, so every post recorded counts as seen since it was kept.
  private val seen = SeenStore(InMemoryTextFile(), object : Clock {
    override fun now() = Clock.System.now() + 1.hours
  })
  private val messages = Messages()
  private val offer = BlockOffer()

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id, rating = "s")

  private fun keep(id: String, from: String) = tabs.openInBlock(listOf(Route.Grid(listOf(from), at = id), post(id)))

  private fun id(title: String) = tabs.tabs.value.single { it.title == title }.id

  init {
    tabs.open(Route.Grid(listOf("landscape")), background = false)
    keep("l1", "landscape")
    keep("l2", "landscape")
    tabs.open(Route.Grid(listOf("cat")), background = false)
    keep("c1", "cat")
    seen.record("l1", null)
    seen.record("l2", null)
  }

  private fun androidx.compose.ui.test.ComposeUiTest.leaveLandscape() {
    setContent { LeavingBlockMessages(offer, tabs, seen, ClearViewed(tabs, seen, Messages()), messages) }
    tabs.activate(id("l2"))
    waitForIdle()
    tabs.activate(id("Cat"))
    waitForIdle()
    assertEquals(id("Landscape"), offer.pending)
  }

  @Test
  fun anotherMessageEndsTheOffer() = runComposeUiTest {
    leaveLandscape()
    messages.show(Message("Cleared 2", actionLabel = "Undo"))
    waitForIdle()
    assertNull(offer.pending)
  }

  @Test
  fun goingBackIntoTheBlockEndsTheOffer() = runComposeUiTest {
    leaveLandscape()
    tabs.activate(id("Landscape"))
    waitForIdle()
    assertNull(offer.pending)
  }

  @Test
  fun takingTheOfferClosesTheBlock() = runComposeUiTest {
    leaveLandscape()
    offer.take(tabs, messages)
    assertEquals(listOf("Browse", "Cat", "c1"), tabs.tabs.value.map { it.title })
  }
}
