package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.ui.components.BottomRowLayout
import dev.marzahn.sankaku.ui.components.ScrollToTopButton
import dev.marzahn.sankaku.ui.components.UNDO_CLOSE_LABEL
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #261: ↑ Top is the count pill's and Undo's size, on their line, and clear of the pill. The row is drawn as the grid
 * draws it on a 360 dp phone with a 24 dp navigation bar, over placeholder tiles (no post images). With `EVIDENCE_DIR`
 * set, each frame is saved there as a PNG for the PR.
 */
@OptIn(ExperimentalTestApi::class)
class BottomRowSizesTest {
  private val scale = 2.625f
  private val navigationBar = 24.dp
  private val width = 360.dp
  private val height = 220.dp

  private fun queue(items: Int) = TabsController().apply {
    repeat(items) { open(Route.Grid(listOf("tag$it")), background = true) }
  }

  private fun ComposeUiTest.showRow(tabs: TabsController, fontScale: Float, arrows: Boolean) {
    val settings = AccessibilitySettings(InMemoryTextFile()).apply { setShowQueueButtons(arrows) }
    val undo = ClosedTabUndo(tabs)
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(scale, fontScale)) {
        SankakuTheme {
          Box(Modifier.size(width, height).background(MaterialTheme.colorScheme.surface)) {
            // The grid's box ends at the navigation bar, as on a phone; ↑ is placed in it as GridScreen places it.
            Box(Modifier.fillMaxSize().padding(bottom = navigationBar)) {
              PlaceholderTiles()
              ScrollToTopButton(
                visible = true,
                onClick = {},
                compact = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(BottomRowLayout.topButtonPadding(room = BottomRowLayout.roomAbove(navigationBar = 0.dp))),
              )
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(navigationBar).background(Color.Black))
            QueueBar(
              onOpenQueue = {},
              bottom = BottomRowLayout.roomAbove(navigationBar),
              controller = tabs,
              undo = undo,
              settings = settings,
              tips = Tips(InMemoryTextFile(), touch = false),
              upNext = noUpNext(tabs),
              modifier = Modifier.align(Alignment.BottomCenter),
            )
          }
        }
      }
    }
    // Undo after a close, so all three pieces are in the frame.
    runOnIdle { undo.close(tabs.activeTab.id) }
    waitForIdle()
  }

  private fun ComposeUiTest.assertRow(name: String, items: Int, fontScale: Float, arrows: Boolean = false) {
    showRow(queue(items), fontScale, arrows)
    System.getenv("EVIDENCE_DIR")?.let { dir -> ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png")) }

    val pill = onNodeWithTag(COUNT_PILL).getUnclippedBoundsInRoot()
    val undo = onNodeWithContentDescription(UNDO_CLOSE_LABEL).getUnclippedBoundsInRoot()
    val top = onNodeWithContentDescription("Top").getUnclippedBoundsInRoot()
    println("#261 $name: pill $pill, undo $undo, top $top")

    // The same 48 dp height (the drawn 40 dp inside each), and round.
    assertEquals(48f, pill.height.value, 0.5f, "pill $pill")
    assertEquals(48f, undo.height.value, 0.5f, "undo $undo")
    assertEquals(48f, top.height.value, 0.5f, "top $top")
    assertEquals(48f, top.width.value, 0.5f, "top $top")
    // One centre line, 32 dp above the navigation bar, give or take the pixel each piece rounds to. With queue
    // buttons on, Undo's ↶ circle sits above ✕ when the pill leaves no room beside it (#178), so it's off the line.
    val line = (height - navigationBar - BottomRowLayout.CentreLine).value
    val onLine = if (arrows) listOf(pill, top) else listOf(pill, undo, top)
    onLine.forEach { assertEquals(line, it.middleY, 1f, "$it") }
    assertEquals(pill.middleY, top.middleY, 1 / scale, "pill $pill, top $top")
    // ↑'s middle 44 dp in from the right edge, and clear of the pill.
    assertEquals((width - BottomRowLayout.CornerCentre).value, (top.left + top.right).value / 2, 0.5f)
    assertTrue(pill.right <= top.left, "pill $pill overlaps top $top")
    // #276: every piece stays on the screen, and Undo clear of the pill, with queue buttons on and off.
    listOf(pill, undo, top).forEach { assertTrue(it.left >= 0.dp && it.right <= width, "off screen: $it") }
    if (!arrows) assertTrue(undo.right <= pill.left, "undo $undo overlaps pill $pill")
    // The line stays where #118 left it, so the target ends 8 dp above the navigation bar, as the pill's does.
    assertEquals(8f, (height - navigationBar - top.bottom).value, 1f, "top $top")
  }

  @Test
  fun topMatchesThePillAndUndo() = runDesktopComposeUiTest(width = px(width), height = px(height)) {
    assertRow("bottom-row-1x", items = 9, fontScale = 1f)
  }

  @Test
  fun topClearsThePillAtTwiceTheFontScale() = runDesktopComposeUiTest(width = px(width), height = px(height)) {
    assertRow("bottom-row-2x", items = 9, fontScale = 2f)
  }

  @Test
  fun topClearsALongCountAtTwiceTheFontScale() = runDesktopComposeUiTest(width = px(width), height = px(height)) {
    assertRow("bottom-row-2x-long-count", items = 120, fontScale = 2f)
  }

  @Test
  fun topClearsThePillWithQueueButtonsAtTwiceTheFontScale() = runDesktopComposeUiTest(width = px(width), height = px(height)) {
    assertRow("bottom-row-2x-queue-buttons", items = 9, fontScale = 2f, arrows = true)
  }

  private fun px(dp: androidx.compose.ui.unit.Dp) = (dp.value * scale).toInt()
}

private val DpRect.middleY get() = ((top + bottom) / 2).value

/** Flat coloured tiles where the grid's posts would be: no post images. */
@androidx.compose.runtime.Composable
private fun PlaceholderTiles() {
  val colours = listOf(Color(0xFF90A4AE), Color(0xFFB0BEC5), Color(0xFF78909C))
  Column(Modifier.fillMaxSize().padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    repeat(3) { row ->
      Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { column -> Box(Modifier.weight(1f).fillMaxSize().background(colours[(row + column) % 3])) }
      }
    }
  }
}
