package dev.marzahn.sankaku.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.features.post.SheetStrip
import dev.marzahn.sankaku.features.post.opensAsOne
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabsController
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #321: a Recommended thumbnail opens its post through the shell's [PostHandoff]: the post tapped on keeps its
 * composition and fades out over the new lone post, which comes in on a visibility of its own, until both settle. The
 * queue gets the post where `openLone` puts it. Any other change of item cuts as before.
 */
@OptIn(ExperimentalTestApi::class)
class PostHandoffTest {
  private val tabs = TabsController(FileTabStateStore(InMemoryTextFile(null)))
  private val navigator = TabsNavigator(tabs)
  private val handoff = PostHandoff()

  /** What each item saw on its last composition, by post id. */
  private val seen = HashMap<String, ItemSeen>()

  private class ItemSeen(val identity: Any, val entering: Boolean, val leaving: Boolean, val hasVisibility: Boolean)

  /** Stands in for a lone post: its post id as a tag, and a "thumbnail" of [NEXT] that opens it as the sheet's does. */
  @Composable
  private fun Item(item: TabState) {
    val id = (item.top as Route.Post).id
    val identity = remember { Any() }
    seen[id] = ItemSeen(identity, LocalHandoffEntering.current != null, LocalHandoffLeaving.current, LocalItemVisibility.current != null)
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().testTag("item-$id")) {
      Box(
        Modifier.size(72.dp, 96.dp).testTag("thumb-$id").clickable {
          scope.launch { handoff.open(item.id, NEXT.id, active = { tabs.active.value }) { navigator.goTo(NEXT) } }
        },
      )
    }
  }

  @Composable
  private fun Host() {
    val list by tabs.tabs.collectAsState()
    val activeId by tabs.active.collectAsState()
    val active = list.first { it.id == activeId }
    PostHandoffHost(active, list, handoff) { Item(it) }
  }

  @Test
  fun aThumbnailOpensItsPostOverThePostItLeaves() = runComposeUiTest {
    tabs.openLone(FIRST)
    val from = tabs.active.value
    mainClock.autoAdvance = false
    setContent { Host() }
    mainClock.advanceTimeByFrame()
    val before = assertNotNull(seen[FIRST.id]).identity
    assertTrue(assertNotNull(seen[FIRST.id]).hasVisibility, "a thumbnail has its item's visibility to share on")

    onNodeWithTag("thumb-${FIRST.id}").performClick()
    mainClock.advanceTimeByFrame()
    assertTrue(handoff.sharing(from, NEXT.id), "the thumbnail's element is switched on before its post opens")
    assertEquals(from, tabs.active.value, "it opens only once the element has been laid out")

    // The attach frames, then the post opens.
    repeat(4) { mainClock.advanceTimeByFrame() }
    val list = tabs.tabs.value
    val to = tabs.active.value
    assertEquals(list.indexOfFirst { it.id == from } + 1, list.indexOfFirst { it.id == to }, "the post is right after the one it came from, as openLone puts it")
    assertEquals(PostOpening(from, NEXT.id, to), handoff.current)
    assertTrue(handoff.sharing(from, NEXT.id), "still the one moving")

    onNodeWithTag("item-${FIRST.id}").assertExists()
    onNodeWithTag("item-${NEXT.id}").assertExists()
    assertSame(before, assertNotNull(seen[FIRST.id]).identity, "the post tapped on keeps its composition, and its thumbnail its bounds")
    assertTrue(assertNotNull(seen[FIRST.id]).leaving)
    assertFalse(assertNotNull(seen[FIRST.id]).entering)
    assertTrue(assertNotNull(seen[NEXT.id]).entering, "the new post's picture grows on its own coming in")
    assertFalse(assertNotNull(seen[NEXT.id]).leaving)

    // Settled: the post left is gone, and the new one is an item as any other.
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    onAllNodesWithTag("item-${FIRST.id}").assertCountEquals(0)
    onNodeWithTag("item-${NEXT.id}").assertExists()
    assertNull(handoff.current)
    assertFalse(assertNotNull(seen[NEXT.id]).entering, "settled, its picture is the queue page's again")
  }

  @Test
  fun anyOtherChangeOfItemCuts() = runComposeUiTest {
    tabs.openLone(FIRST)
    tabs.openLone(NEXT)
    mainClock.autoAdvance = false
    setContent { Host() }
    mainClock.advanceTimeByFrame()

    runOnUiThread { navigator.goBack() }
    mainClock.advanceTimeByFrame()
    onNodeWithTag("item-${FIRST.id}").assertExists()
    onAllNodesWithTag("item-${NEXT.id}").assertCountEquals(0)
    assertNull(handoff.current)
    assertFalse(assertNotNull(seen[FIRST.id]).entering, "Back is the plain transition")
  }

  @Test
  fun withAnimationsOffItOnlyOpens() = runTest {
    tabs.openLone(FIRST)
    val from = tabs.active.value
    var opened = 0
    withContext(NoMotion) {
      handoff.open(from, NEXT.id, active = { tabs.active.value }) {
        opened++
        navigator.goTo(NEXT)
      }
    }
    assertEquals(1, opened)
    assertNull(handoff.current)
  }

  @Test
  fun theThumbnailStaysOnWhileThePostIsHeld() = runTest {
    tabs.openLone(FIRST)
    val from = tabs.active.value
    withContext(InstantFrames) {
      handoff.open(from, NEXT.id, active = { tabs.active.value }) {
        // Holding the post can wait on the repository's lock.
        kotlinx.coroutines.yield()
        assertTrue(handoff.sharing(from, NEXT.id), "switched off while the post was held, the move would start from nowhere")
        navigator.goTo(NEXT)
      }
    }
    assertEquals(PostOpening(from, NEXT.id, tabs.active.value), handoff.current)
    handoff.end()
    assertNull(handoff.current, "the queue page opening over it ends it")
  }

  /** #342: a post in two strips (a child that's also recommended) shares its key only from the thumbnail tapped. */
  @Test
  fun onlyTheTappedStripsThumbnailIsSwitchedOn() = runTest {
    tabs.openLone(FIRST)
    val from = tabs.active.value
    withContext(InstantFrames) {
      handoff.open(from, NEXT.id, active = { tabs.active.value }, strip = SheetStrip.Children) {
        assertTrue(handoff.sharing(from, NEXT.id, SheetStrip.Children), "the child tapped")
        assertFalse(handoff.sharing(from, NEXT.id, SheetStrip.Recommended), "the same post among the recommendations")
        navigator.goTo(NEXT)
      }
    }
    assertTrue(handoff.sharing(from, NEXT.id, SheetStrip.Children))
    assertFalse(handoff.sharing(from, NEXT.id, SheetStrip.Recommended))
  }

  @Test
  fun aSheetGoneOrAFailedOpenLeavesNothingOn() = runTest {
    for (failure in listOf(kotlinx.coroutines.CancellationException("gone"), IllegalStateException("failed"))) {
      tabs.openLone(FIRST)
      val from = tabs.active.value
      val result = runCatching {
        withContext(InstantFrames) {
          handoff.open(from, NEXT.id, active = { tabs.active.value }) { throw failure }
        }
      }
      assertTrue(result.isFailure)
      assertNull(handoff.current, "the next tap starts afresh")
    }
  }

  private object InstantFrames : androidx.compose.runtime.MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(0L)
  }

  @Test
  fun onlyAShownThumbnailWithAFileOpensAsOne() {
    val post = PostDto(id = "p", md5 = "m", rating = "s", status = "active", fileUrl = "f")
    assertTrue(opensAsOne(post, openInTab = false, gOnly = false))
    assertTrue(opensAsOne(post, openInTab = false, gOnly = true))
    assertFalse(opensAsOne(post, openInTab = true, gOnly = false), "open in a tab: it's shown there")
    assertFalse(opensAsOne(post.copy(fileUrl = null), openInTab = false, gOnly = false), "nothing to open loaded")
    assertFalse(opensAsOne(post.copy(rating = "q"), openInTab = false, gOnly = true), "G only hides it")
  }

  private object NoMotion : MotionDurationScale {
    override val scaleFactor = 0f
  }

  private companion object {
    val FIRST = Route.Post("first", "m1", emptyList(), "First")
    val NEXT = Route.Post("next", "m2", emptyList(), "Next")
  }
}
