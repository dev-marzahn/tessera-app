package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #181's queue swipe: only a deliberate sideways swipe moves between queue items (#122's thresholds), and it settles
 * as the viewer's pager does.
 */
@OptIn(ExperimentalTestApi::class)
class QueueSwipeTest {
  private val slop = 8f

  @Test
  fun aDeliberateSwipeIsTakenOnlyPastTwiceTheSlopBeforeOneSlopUpOrDown() {
    assertEquals(SwipeStart.Wait, swipeStart(dx = 1.5f * slop, dy = 0f, slop))
    assertEquals(SwipeStart.Take, swipeStart(dx = -2f * slop, dy = 0.9f * slop, slop))
    assertEquals(SwipeStart.Take, swipeStart(dx = 2f * slop, dy = 0f, slop))
    assertEquals(SwipeStart.LetGo, swipeStart(dx = 2f * slop, dy = slop, slop))
    assertEquals(SwipeStart.LetGo, swipeStart(dx = 0f, dy = -slop, slop))
  }

  @Test
  fun aLetGoSettlesAsThePagerDoes() {
    val width = 400f
    val fling = 400f
    fun target(offset: Float, velocity: Float, previous: Boolean = true, next: Boolean = true) =
      swipeTarget(offset, velocity, width, fling, previous, next)

    // Slow: past half the width moves, short of it stays.
    assertEquals(QueueMove.Next, target(-201f, 0f))
    assertEquals(QueueMove.Stay, target(-199f, 0f))
    assertEquals(QueueMove.Previous, target(201f, 0f))
    // A fling moves however short the drag, the way it flings; a fling back stays.
    assertEquals(QueueMove.Next, target(-20f, -fling))
    assertEquals(QueueMove.Stay, target(-300f, fling))
    // Nowhere to go that way: it stays (and the overscroll stretches).
    assertEquals(QueueMove.Stay, target(-300f, -fling, next = false))
    assertEquals(QueueMove.Stay, target(300f, fling, previous = false))
  }

  /**
   * #340: Back on the first item's lone post opens the queue page; it used to have no handler there, so it fell through
   * to the activity and closed the app. With an item before, it goes there (#181), and on a card off it (#248).
   */
  @Test
  fun backOnTheFirstItemsLonePostOpensTheQueuePage() {
    assertEquals(QueueBack.QueuePage, queueBack(cardUp = false, lonePost = true, previous = false))
    assertEquals(QueueBack.Previous, queueBack(cardUp = false, lonePost = true, previous = true))
    for (lonePost in listOf(true, false)) {
      for (previous in listOf(true, false)) {
        assertEquals(QueueBack.Previous, queueBack(cardUp = true, lonePost = lonePost, previous = previous), "card")
      }
    }
    // A grid, or a post over something in its tab: the tab's own Back, as before.
    assertEquals(QueueBack.Tab, queueBack(cardUp = false, lonePost = false, previous = false))
    assertEquals(QueueBack.Tab, queueBack(cardUp = false, lonePost = false, previous = true))
  }

  /** #248: on a card a swipe only goes back, to the item it stands past; on an item, whichever way has somewhere to go. */
  @Test
  fun onACardASwipeGoesBackOnlyAndOnAnItemEitherWayWithSomewhereToGo() {
    for (ahead in Ahead.entries) {
      for (previous in listOf(true, false)) {
        assertEquals(SwipeWays(back = true, forward = false), swipeWays(cardUp = true, previous = previous, ahead = ahead), "card, $ahead")
      }
    }
    assertEquals(SwipeWays(back = true, forward = true), swipeWays(cardUp = false, previous = true, ahead = Ahead.Item))
    assertEquals(SwipeWays(back = false, forward = true), swipeWays(cardUp = false, previous = false, ahead = Ahead.End))
    assertEquals(SwipeWays(back = true, forward = true), swipeWays(cardUp = false, previous = true, ahead = Ahead.UpNext))
    assertEquals(SwipeWays(back = true, forward = false), swipeWays(cardUp = false, previous = true, ahead = Ahead.Nothing))

    // With forward off, a fling towards the start stays, however far; back settles as on an item.
    val card = swipeWays(cardUp = true, previous = false, ahead = Ahead.End)
    assertEquals(QueueMove.Stay, swipeTarget(-399f, -1000f, 400f, 400f, card.back, card.forward))
    assertEquals(QueueMove.Previous, swipeTarget(201f, 0f, 400f, 400f, card.back, card.forward))
    assertEquals(QueueMove.Previous, swipeTarget(20f, 1000f, 400f, 400f, card.back, card.forward))
  }

  /** Runs [gesture] on a host over [count] queue items, the second one shown, and returns the active item's index. */
  private fun landsOn(count: Int = 3, gesture: TouchInjectionScope.() -> Unit): Int {
    val tabs = TabsController()
    repeat(count - 1) { tabs.open(Route.Grid(listOf("t$it")), background = true) }
    if (count > 1) tabs.next()
    return landsOn(tabs, gesture)
  }

  /** Runs [gesture] on a host over [tabs]' queue, from the active item, and returns the active item's index. */
  private fun landsOn(tabs: TabsController, gesture: TouchInjectionScope.() -> Unit): Int {
    runComposeUiTest {
      setContent {
        val activeId by tabs.active.collectAsState()
        val tabList by tabs.tabs.collectAsState()
        val active = tabList.first { it.id == activeId }
        Box(Modifier.size(400.dp)) {
          QueueSwipeHost(active, tabs = tabs, upNext = noUpNext(tabs), undo = ClosedTabUndo(tabs), preload = { _, _ -> emptyMap() }, content = {
            // A scrolling column stands in for the grid: its scroll sees each move first, as the grid's does.
            Box(Modifier.size(400.dp).testTag("grid").queueSwipe(enabled = true, from = active.id)) {
              Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Box(Modifier.height(4000.dp)) }
            }
          })
        }
      }
      onNodeWithTag("grid").performTouchInput(gesture)
      waitForIdle()
    }
    return tabs.tabs.value.indexOfFirst { it.id == tabs.active.value }
  }

  @Test
  fun aSwipeTowardsTheStartMovesToTheNextItem() {
    assertEquals(2, landsOn { swipe(Offset(width - 20f, centerY), Offset(20f, centerY), 300) })
  }

  @Test
  fun aSwipeTowardsTheEndMovesToThePreviousItem() {
    assertEquals(0, landsOn { swipe(Offset(20f, centerY), Offset(width - 20f, centerY), 300) })
  }

  /**
   * #236: the content follows the finger, so the finger barely moves within it; the fling is the finger's speed on the
   * screen. A quick flick short of half the width moves, here on the item a drag down closed a kept post onto (#326:
   * the next one).
   */
  @Test
  fun aShortFlingRightAfterADragDownCloseMovesToTheNextItem() {
    val tabs = TabsController()
    tabs.open(Route.Grid(listOf("artist_x")), background = false)
    tabs.openLone(Route.Post("p1", "m1", listOf("artist_x"), "One"))
    val closing = tabs.activeTab
    tabs.openLone(Route.Post("p2", "m2", listOf("artist_x"), "Two"))
    val landing = tabs.activeTab
    tabs.openLone(Route.Post("p3", "m3", listOf("artist_x"), "Three"))
    tabs.activate(closing.id)
    ClosedTabUndo(tabs).dragClose(closing.id, noUpNext(tabs))
    assertEquals(landing, tabs.activeTab)
    val next = tabs.neighbour(forward = true)

    landsOn(tabs) { swipe(Offset(centerX + 80f, centerY), Offset(centerX - 80f, centerY), 100) }

    assertSame(next, tabs.activeTab)
  }

  /**
   * #236: the content follows the finger from where it went down, the way to the swipe's taking included, so a slow
   * drag let go just past half the width moves.
   */
  @Test
  fun aSlowDragJustPastHalfTheWidthMoves() {
    // Half the width and one slop: past half from the down, short of it from where the swipe is taken (twice the slop).
    assertEquals(2, landsOn {
      val half = width / 4 + viewConfiguration.touchSlop / 2
      swipe(Offset(centerX + half, centerY), Offset(centerX - half, centerY), 3000)
    })
  }

  @Test
  fun aSlowShortDragSpringsBack() {
    assertEquals(1, landsOn { swipe(Offset(centerX, centerY), Offset(centerX - 100f, centerY), 3000) })
  }

  /** #122's test: 20 diagonal scrolls, leaning 39° to 60° off horizontal, either way, move nothing. */
  @Test
  fun twentyDiagonalScrollsMoveNothing() {
    repeat(20) { i ->
      val sideways = (if (i % 2 == 0) 1f else -1f) * (120f + 4f * i)
      val down = if (i % 4 < 2) -160f - 2f * i else 160f + 2f * i
      assertEquals(1, landsOn { swipe(Offset(centerX, centerY), Offset(centerX + sideways, centerY + down), 250) }, "scroll $i")
    }
  }

  @Test
  fun aVerticalScrollMovesNothing() {
    assertEquals(1, landsOn { swipe(Offset(centerX, height - 20f), Offset(centerX, 20f), 300) })
  }

  @Test
  fun aSidewaysDragShortOfTwiceTheSlopMovesNothing() {
    assertEquals(1, landsOn { swipe(Offset(centerX, centerY), Offset(centerX - 1.5f * viewConfiguration.touchSlop, centerY), 200) })
  }

  /** A pinch spreading sideways is a zoom: the second finger lets the swipe go. */
  @Test
  fun aSidewaysPinchMovesNothing() {
    assertEquals(1, landsOn {
      pinch(
        Offset(centerX - 10f, centerY), Offset(centerX - 150f, centerY),
        Offset(centerX + 10f, centerY), Offset(centerX + 150f, centerY),
        durationMillis = 300,
      )
    })
  }

  @Test
  fun aDoubleTapMovesNothing() {
    assertEquals(1, landsOn { doubleClick(Offset(centerX, centerY)) })
  }

  @Test
  fun withOneItemASwipeMovesNothing() {
    assertEquals(0, landsOn(count = 1) { swipe(Offset(width - 20f, centerY), Offset(20f, centerY), 300) })
  }

  @Test
  fun eachItemIsAPaneTitledByItsPlaceInTheQueue() {
    val tabs = TabsController()
    tabs.open(Route.Grid(listOf("artist_x")), background = false)
    val source = tabs.activeTab
    tabs.openLone(Route.Post("p1", "m1", listOf("artist_x"), "Original"))
    val kept = tabs.activeTab

    assertEquals("Queue item 2 of 3, source Artist X", queueItemDescription(2, 3, source, null))
    assertEquals("Queue item 3 of 3, kept post from Artist X", queueItemDescription(3, 3, kept, tabs.sourceOf(kept)))
    assertEquals("Queue item 3 of 3, kept post, Original", queueItemDescription(3, 3, kept, null))
    assertEquals("Kept · from Artist X", kindLabel(kept, source))
  }

  @Test
  fun aFeedIsNamedByTheCountedItemsBeforeItWhereverItStands() {
    val tabs = TabsController()
    tabs.pinFeeds(listOf(Feed.Following))
    tabs.open(Route.Grid(listOf("artist_x")), background = true)
    tabs.open(Route.Grid(listOf("artist_y")), background = true)
    // A feed is a source that persists (#327): here Following stands second, with items after it.
    val all = tabs.tabs.value
    assertEquals(listOf("Browse", "Following", "Artist X", "Artist Y"), all.map { it.title })
    val feed = all[1]

    val description = queueItemDescription(countedPosition(all, 1), countedItems(all), feed, null)

    assertEquals("Queue, feed Following, after 1 item", description)
    // First in the queue, it has no items before it.
    tabs.moveInQueue(feed.id, down = false)
    val moved = tabs.tabs.value
    assertEquals("Queue, feed Following, first", queueItemDescription(countedPosition(moved, 0), countedItems(moved), feed, null))
  }
}
