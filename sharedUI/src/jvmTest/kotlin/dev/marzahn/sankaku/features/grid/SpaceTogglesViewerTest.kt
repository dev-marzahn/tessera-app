package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.features.post.VideoPlayback
import dev.marzahn.sankaku.shell.BackOverrides
import dev.marzahn.sankaku.ui.components.Shortcuts
import org.koin.core.context.GlobalContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #424: Space toggles the viewer over a grid. Space on the cursor's cell opens it (as before), and Space in it closes
 * it again onto the cell of the post it closed on, as Esc does, on an image and a GIF; held, its auto-repeat closes
 * nothing. On a video Space plays and pauses, and while the tag list has the keys it searches the tag; neither closes
 * the viewer. The real [GridScreen] in its tab ([GridScreenFixture]): a GIF at 2 and an mp4 at 4, and a fake player.
 */
@OptIn(ExperimentalTestApi::class)
class SpaceTogglesViewerTest {
  /** Plays and pauses as asked; no media, so nothing is drawn. */
  private class FakePlayback : VideoPlayback {
    override var isPlaying by mutableStateOf(false)
    override val hasMedia = false
    override val failed = false
    override val firstFrameShown = false
    override val duration = 10.0
    override val position = 0f
    override var volume = 1f
    override var dragging = false

    override fun open(url: String, cacheKey: String, play: Boolean) {
      isPlaying = play
    }

    override fun play() {
      isPlaying = true
    }

    override fun pause() {
      isPlaying = false
    }

    override fun seekTo(fraction: Float) {}
  }

  private val player = FakePlayback()
  private val fixture = GridScreenFixture(types = mapOf(2 to "image/gif", 4 to "video/mp4"), player = player)

  @BeforeTest
  fun start() = fixture.start()

  @AfterTest
  fun stop() = fixture.stop()

  private val overrides get() = GlobalContext.get().get<BackOverrides>()

  @Test
  fun spaceOpensAndClosesTheViewerOnAnImageAndAGifOntoThePostItClosedOn() = runDesktopComposeUiTest(width = 1280, height = 832) {
    with(fixture) {
      showGrid()
      cursorTo(1)
      press(Key.Spacebar)
      assertTrue(viewerOpen(), "Space didn't open the viewer")
      assertEquals(1, viewerPost())
      // The top bar's Back shows Space beside Esc.
      assertEquals(Shortcuts.CloseViewer, overrides.alsoKey)

      // Stepped on to the GIF, Space closes onto it, not onto the post it opened on.
      press(Key.D)
      assertEquals(2, viewerPost())
      press(Key.Spacebar)
      assertTrue(!viewerOpen(), "Space didn't close the viewer on a GIF")
      assertEquals(2, cursorPost(), "the cursor isn't on the post the viewer closed on")
      assertNull(overrides.alsoKey)

      // And on an image, opened from that cell.
      cursorTo(3)
      press(Key.Spacebar)
      assertEquals(3, viewerPost())
      press(Key.Spacebar)
      assertTrue(!viewerOpen(), "Space didn't close the viewer on an image")
      assertEquals(3, cursorPost())
    }
  }

  @Test
  fun aHeldSpaceOpensTheViewerOnceAndClosesNothing() = runDesktopComposeUiTest(width = 1280, height = 832) {
    with(fixture) {
      showGrid()
      cursorTo(1)
      // Held: the press and its auto-repeat, with no release between.
      press(Key.Spacebar, downs = 4)
      assertTrue(viewerOpen(), "the auto-repeat closed the viewer it opened")
      press(Key.Spacebar, downs = 4)
      assertTrue(!viewerOpen())
      assertEquals(1, cursorPost())
    }
  }

  @Test
  fun onAVideoSpacePlaysAndPausesAndKeepsTheViewerOpen() = runDesktopComposeUiTest(width = 1280, height = 832) {
    with(fixture) {
      showGrid()
      cursorTo(4)
      press(Key.Spacebar)
      assertEquals(4, viewerPost())
      waitUntil(timeoutMillis = 5_000) { player.isPlaying }
      // No Space beside Esc: here it's the video's.
      assertNull(overrides.alsoKey)

      press(Key.Spacebar)
      assertTrue(viewerOpen(), "Space closed the viewer on a video")
      assertTrue(!player.isPlaying, "Space didn't pause the video")
      press(Key.Spacebar)
      assertTrue(viewerOpen())
      assertTrue(player.isPlaying, "Space didn't play the video again")

      // Esc still closes it.
      press(Key.Escape)
      assertTrue(!viewerOpen(), "Esc didn't close the viewer")
      assertEquals(4, cursorPost())
    }
  }

  @Test
  fun inTheTagListSpaceSearchesTheTagInsteadOfClosing() = runDesktopComposeUiTest(width = 1280, height = 832) {
    with(fixture) {
      showGrid()
      cursorTo(1)
      press(Key.Spacebar)
      press(Key.T)
      assertEquals(grid, tabs.activeTab.top)
      press(Key.Spacebar)
      // The tag list took Space: it searched the selected tag alone, by popularity, shown now (Tags → keyboard). Had
      // the viewer taken it, it would have closed onto the grid, which would still be shown.
      assertEquals(Route.Grid(listOf("order:popularity", "fixture_1")), tabs.activeTab.top)
    }
  }
}
