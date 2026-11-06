package dev.marzahn.sankaku.tabs

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.memory.MemoryCache
import dev.marzahn.sankaku.QueuePageAndTopLine
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.ShellBox
import dev.marzahn.sankaku.UnderQueuePage
import dev.marzahn.sankaku.rememberQueuePageTransitions
import dev.marzahn.sankaku.shell.LocalQueuePageOpening
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag

import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.features.grid.imageKey
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.shell.LocalShellTransitions
import dev.marzahn.sankaku.shell.LocalUnderQueuePage
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import org.jetbrains.skia.Bitmap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #320: the queue page as a transition in the shell's one shared-transition scope, with placeholder pictures (never a
 * real post). These check state and keys only; how the move looks is QA'd in the app (#332).
 *
 * The lone post's side is a stand-in for `PhoneViewer`'s picture: the same [imageKey] on [LocalUnderQueuePage], the
 * visibility the shell gives the tab's content. The page's side is the real [QueuePage].
 */
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class, DelicateCoilApi::class)
class QueuePageTransitionTest {
  private val tabs = TabsController()
  private val privacy = PrivacySettings(InMemoryTextFile())
  private var pageOpen by mutableStateOf(false)
  private var interacted by mutableStateOf(true)

  // The stand-in: composed, its element's state, whether that's matched with the row's now, and on any frame so far.
  private var pictureComposed = false
  private var picture: SharedTransitionScope.SharedContentState? = null
  private val matched get() = pictureComposed && picture?.isMatchFound == true
  private var everMatched = false
  private var transition: Transition<Boolean>? = null

  @AfterTest
  fun tearDown() = SingletonImageLoader.reset()

  /** Keeps the post [id] (rated [rating]) under a grid of its own, and makes it the current item. */
  private fun keep(id: String, rating: String) {
    tabs.openInBlock(listOf(Route.Grid(listOf("scenery"), at = id), Route.Post(id, "md5$id", listOf("scenery"), "Fixture $id", rating)))
    tabs.activate(tabs.tabs.value.single { it.title == "Fixture $id" }.id)
  }

  /** The shell's scope, the tab's content (the stand-in for the post [id]) and the queue page, as `Shell` lays them. */
  private fun ComposeUiTest.shell(id: String) {
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
      .memoryCache { MemoryCache.Builder().maxSizeBytes(4L * 1024 * 1024).build() }
      .build()
    loader.memoryCache!![MemoryCache.Key("md5$id-Preview")] = MemoryCache.Value(Bitmap().apply { allocN32Pixels(32, 32) }.asImage())
    SingletonImageLoader.setUnsafe(loader)
    mainClock.autoAdvance = false
    setContent {
      SankakuTheme {
        ShellBox(shared = true, Modifier.fillMaxSize()) {
          val transitions = rememberQueuePageTransitions(pageOpen)
          transition = transitions.under
          UnderQueuePage(transitions) { LonePicture(id) }
          QueuePageAndTopLine(remember { SnackbarHostState() }, transitions) { onListTop ->
            QueuePage(
              onDismiss = { pageOpen = false },
              controller = tabs,
              posts = TabPosts.None,
              privacy = privacy,
              seen = null,
              clearViewed = ClearViewed(tabs, SeenStore(InMemoryTextFile()), Messages()),
              interacted = { interacted },
              onInteract = {},
              onListTop = onListTop,
              leaving = !pageOpen,
            )
          }
        }
      }
    }
    mainClock.advanceTimeByFrame()
  }

  /**
   * The post [id]'s picture as `PhoneViewer` shares it under the queue page, [PICTURE] at the window's top left: its
   * element attached only while its visibility moves, or while the page is about to open over it.
   */
  @Composable
  private fun LonePicture(id: String) {
    val under = LocalUnderQueuePage.current!!
    val settled = under.transition.currentState == EnterExitState.Visible && under.transition.targetState == EnterExitState.Visible
    val attached = !settled || LocalQueuePageOpening.current
    // As the viewer's chrome does, something on its own visibility that takes a while.
    under.transition.animateFloat({ spring(stiffness = Spring.StiffnessMediumLow) }) { if (it == EnterExitState.Visible) 1f else 0f }
    with(LocalShellTransitions.current!!) {
      val state = rememberSharedContentState(imageKey(id))
      DisposableEffect(Unit) {
        pictureComposed = true
        picture = state
        onDispose { pictureComposed = false }
      }
      Box(Modifier.size(PICTURE).then(if (attached) Modifier.sharedElement(state, under, clipInOverlayDuringTransition = OverlayClip(RectangleShape)) else Modifier).testTag(PICTURE_TAG))
    }
  }

  /** The lone post's picture's width where it's laid out now, in dp: in flight, where the move has it. */
  private fun ComposeUiTest.pictureWidth() =
    with(density) { onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().boundsInRoot.width.toDp() }

  /**
   * The owner's phone (#320): from the pill, the move began at the row's thumbnail (or the window's corner), not at the
   * picture. The picture's element had never been laid out matched, and the row, shown with the page before the post
   * left, took the picture's place at once. Now it leaves from the picture's size and shrinks on every frame towards
   * the thumbnail. Bounds, not pixels.
   */
  @Test
  fun fromThePillThePictureShrinksFromItsOwnSizeIntoTheRow() = runComposeUiTest {
    keep("g", "s")
    shell("g")
    // Opened from its row first, as on the phone, then back to the page.
    pageOpen = true
    settle()
    pageOpen = false
    settle()

    pageOpen = true
    // The page's frames before the picture leaves.
    frames(OPENING_FRAMES)
    val widths = List(10) {
      frames(1)
      pictureWidth()
    }
    assertTrue(matched, "the picture and its row are one element")
    assertTrue(widths.first() > PICTURE.width * 0.9f, "it leaves from the picture's size: $widths")
    assertTrue(widths.zipWithNext().all { (a, b) -> b <= a }, "it only shrinks: $widths")
    assertTrue(widths.last() < PICTURE.width * 0.7f, "on its way to the thumbnail: $widths")
  }

  /** A few frames into the page's transition, with the picture on its way. */
  private fun ComposeUiTest.someFrames() = frames(4)

  /** Long past any spring's end. */
  private fun ComposeUiTest.settle() = frames(200)

  private fun ComposeUiTest.frames(count: Int) = repeat(count) {
    mainClock.advanceTimeByFrame()
    if (matched) everMatched = true
  }

  private fun ComposeUiTest.blurred() = onAllNodesWithTag(BLURRED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

  @Test
  fun aKeptRowsThumbnailAndItsLonePostsPictureAreOneElementBothWays() = runComposeUiTest {
    keep("g", "s")
    shell("g")
    assertTrue(pictureComposed)

    // The pill: the page opens, and the picture shrinks into its row on the way.
    pageOpen = true
    someFrames()
    assertTrue(pictureComposed, "the lone post stays composed while the page opens")
    assertTrue(matched, "the picture and its row's thumbnail are one element as the page opens")
    settle()
    assertFalse(pictureComposed, "nothing is composed under the open page")

    // Continue: the page closes, and the picture grows out of its row.
    pageOpen = false
    mainClock.advanceTimeByFrame()
    assertTrue(pictureComposed, "the lone post is composed from the moment the page starts to close")
    someFrames()
    assertTrue(matched, "the row's thumbnail and the picture are one element as the page closes")
    settle()
    assertFalse(matched, "settled, nothing is in flight")
  }

  @Test
  fun aTapOnAKeptRowWhileAnotherItemIsCurrentGrowsItsPicture() = runComposeUiTest {
    keep("g", "s")
    // The grid it was kept from is current; the page is open over it.
    tabs.activate(tabs.tabs.value.first { it.title != "Fixture g" }.id)
    pageOpen = true
    shell("g")
    settle()
    assertFalse(pictureComposed)

    val row = onNodeWithText("Fixture g").fetchSemanticsNode().boundsInRoot
    onNodeWithText("Fixture g").performClick()
    mainClock.advanceTimeByFrame()
    assertTrue(pictureComposed, "the tapped post is composed as the page starts to close")
    pictureGrowsFrom(row)
    settle()
    assertFalse(matched)
  }

  /**
   * #322: a kept post reopened from Recently closed grows out of that row's thumbnail, which stays in its place on the
   * page as it leaves: the move starts there, not at the row the reopened item gets in the queue. Bounds, not pixels.
   */
  @Test
  fun aKeptPostReopenedFromRecentlyClosedGrowsOutOfThatRowsThumbnail() = runComposeUiTest {
    keep("g", "s")
    tabs.close(tabs.activeTab.id)
    assertTrue(tabs.tabs.value.none { it.title == "Fixture g" })
    pageOpen = true
    shell("g")
    settle()
    assertFalse(pictureComposed)
    val row = onNodeWithText("Fixture g").fetchSemanticsNode().boundsInRoot

    onNodeWithText("Fixture g").performClick()
    mainClock.advanceTimeByFrame()
    assertTrue(pictureComposed, "the reopened post is composed as the page starts to close")
    assertEquals("Fixture g", tabs.activeTab.title)
    assertEquals(1, onAllNodesWithText("Fixture g").fetchSemanticsNodes().size, "only its Recently closed row, kept in place")
    pictureGrowsFrom(row)
    settle()
    assertFalse(matched, "settled, nothing is in flight")
  }

  /**
   * The picture, in flight as the page closes: it starts small, within the [row] it leaves from, and only grows. Before
   * #322 it was there at its own size from the first frame: the row's element was made anew as the page started to
   * close, so the move had nothing to leave from.
   */
  private fun ComposeUiTest.pictureGrowsFrom(row: androidx.compose.ui.geometry.Rect) {
    val bounds = List(10) {
      frames(1)
      onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().boundsInRoot
    }
    assertTrue(matched, "the row's thumbnail and the picture are one element")
    val full = with(density) { PICTURE.width.toPx() }
    val first = bounds.first()
    assertTrue(first.width < full * 0.5f, "it starts small, from the thumbnail: $first")
    assertTrue(first.center.y in row.top..row.bottom, "from its row: $first, row $row")
    val widths = bounds.map { it.width }
    assertTrue(widths.zipWithNext().all { (a, b) -> b >= a }, "it only grows: $widths")
    assertTrue(widths.last() > first.width * 2, "on its way to the picture's size: $widths")
  }

  @Test
  fun aBlurredRowNeverMatchesAndStaysBlurredAsThePageLeaves() = runComposeUiTest {
    keep("q", "q")
    // A launch: the page is open and the queue not used yet, so the R15 row is blurred.
    pageOpen = true
    interacted = false
    shell("q")
    settle()
    assertEquals(1, blurred())

    // Back without a touch: a use of the queue (which unblurs) and the close, at once.
    interacted = true
    pageOpen = false
    repeat(6) {
      mainClock.advanceTimeByFrame()
      assertEquals(1, blurred(), "still blurred on its way out")
    }
    settle()
    assertTrue(pictureComposed)

    // A start after 5 minutes away opens the page by itself over the post, the queue still not used.
    interacted = false
    pageOpen = true
    someFrames()
    assertEquals(1, blurred())
    settle()
    assertFalse(everMatched, "a blurred row opens and closes with the plain transition")
  }

  @Test
  fun onlyTheCurrentItemsRowShares() = runComposeUiTest {
    keep("g", "s")
    // The grid it was kept from is current: the kept post's row isn't.
    tabs.activate(tabs.tabs.value.first { it.title != "Fixture g" }.id)
    shell("g")
    pageOpen = true
    someFrames()
    settle()
    pageOpen = false
    someFrames()
    settle()
    assertFalse(everMatched)
  }

  @Test
  fun withAnimationsOffThePageOpensAndClosesAtOnce() = runComposeUiTest(effectContext = AnimationsOff) {
    keep("g", "s")
    shell("g")

    pageOpen = true
    repeat(3) { mainClock.advanceTimeByFrame() }
    assertEquals(true, transition!!.currentState, "open at once")
    assertFalse(pictureComposed)

    pageOpen = false
    repeat(3) { mainClock.advanceTimeByFrame() }
    assertEquals(false, transition!!.currentState, "shut at once")
    assertTrue(pictureComposed)
  }

  @Test
  fun theContentUnderThePageIsComposedOnlyWhileThePageIsntFullyOpen() = runComposeUiTest {
    var composed = false
    mainClock.autoAdvance = false
    setContent {
      UnderQueuePage(rememberQueuePageTransitions(pageOpen)) {
        // Something on the content's own visibility that takes a while, as a lone post's picture does.
        LocalUnderQueuePage.current!!.transition.animateFloat({ tween(300) }) { if (it == EnterExitState.Visible) 1f else 0f }
        DisposableEffect(Unit) {
          composed = true
          onDispose { composed = false }
        }
      }
    }
    mainClock.advanceTimeByFrame()
    assertTrue(composed, "composed while the page is shut")

    pageOpen = true
    repeat(3) { mainClock.advanceTimeByFrame() }
    assertTrue(composed, "composed until the page has finished opening")
    mainClock.advanceTimeBy(1_000)
    assertFalse(composed, "never under the open page")

    pageOpen = false
    mainClock.advanceTimeByFrame()
    assertTrue(composed, "composed from the moment it starts to close")
  }

  private object AnimationsOff : MotionDurationScale {
    override val scaleFactor = 0f
  }

  private companion object {
    /** The stand-in lone post's picture. */
    val PICTURE = DpSize(300.dp, 400.dp)

    /** The shell's: the page is composed this many frames before what's under it leaves (2 × STAGE_FRAMES). */
    const val OPENING_FRAMES = 4

    const val PICTURE_TAG = "lone post picture"
  }
}

