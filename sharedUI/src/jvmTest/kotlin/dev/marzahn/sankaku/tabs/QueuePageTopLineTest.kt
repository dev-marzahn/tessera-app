package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.QueuePageAndTopLine
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.components.AppSnackbarVisuals
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #309: on the queue page the top line ("Cleared 3 · Undo") sits under the header, so Find, G only and ⋮ stay in view
 * and take taps; on every other screen it's still at the top. On a phone-sized window.
 */
@OptIn(ExperimentalTestApi::class)
class QueuePageTopLineTest {
  private val tabs = TabsController().apply {
    open(Route.Grid(listOf("a")), background = true)
    open(Route.Grid(listOf("b")), background = true)
  }
  private val privacy = PrivacySettings(InMemoryTextFile())
  private val seen = SeenStore(InMemoryTextFile())

  /** The app's touch layers with "Cleared 3 · Undo" showing for [duration], the queue page open while [open]. */
  private fun ComposeUiTest.show(
    open: MutableState<Boolean> = mutableStateOf(true),
    duration: SnackbarDuration = SnackbarDuration.Indefinite,
  ) {
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
        SankakuTheme {
          val snackbar = remember { SnackbarHostState() }
          LaunchedEffect(snackbar) {
            snackbar.showSnackbar(AppSnackbarVisuals("Cleared 3", "Undo", duration))
          }
          Box(Modifier.fillMaxSize()) {
            QueuePageAndTopLine(snackbar, open.value) { onListTop ->
              QueuePage(
                onDismiss = {},
                controller = tabs,
                posts = TabPosts.None,
                privacy = privacy,
                seen = seen,
                clearViewed = ClearViewed(tabs, seen, Messages()),
                interacted = { false },
                onInteract = {},
                onListTop = onListTop,
              )
            }
          }
        }
      }
    }
    if (duration != SnackbarDuration.Indefinite) return
    waitForIdle()
    onNodeWithText("Cleared 3").assertExists()
    System.getenv("EVIDENCE_DIR")?.let { dir ->
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "queue-top-line-${if (open.value) "page" else "closed"}.png"))
    }
  }

  private val header = listOf("Find in queue", "G only", "More")

  @Test
  fun theMessageSitsBelowTheHeader() = runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
    show()
    val message = onNodeWithText("Cleared 3").getBoundsInRoot()
    for (control in header) {
      val bounds = onNodeWithContentDescription(control).getBoundsInRoot()
      println("$control: bottom ${bounds.bottom}, message top ${message.top}")
      assertTrue(bounds.bottom <= message.top, "$control ends at ${bounds.bottom}, under the message from ${message.top}")
    }
  }

  @Test
  fun findTakesATapWhileTheMessageShows() = runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
    show()
    onNodeWithContentDescription("Find in queue").performClick()
    waitForIdle()
    onNodeWithText("Find by title or tag").assertExists()
  }

  @Test
  fun gOnlyTakesATapWhileTheMessageShows() = runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
    show()
    onNodeWithContentDescription("G only").performClick()
    waitForIdle()
    assertTrue(privacy.gOnly.value)
  }

  @Test
  fun moreTakesATapWhileTheMessageShows() = runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
    show()
    onNodeWithContentDescription("More").performClick()
    waitForIdle()
    onNodeWithText("Clear everything…").assertExists()
  }

  @Test
  fun theMessageStillShowsAtTheTopElsewhere() = runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
    show(open = mutableStateOf(false))
    // The JVM has no status bar: at the window's top, within the snackbar's own margin and padding.
    val top = onNodeWithText("Cleared 3").getBoundsInRoot().top
    println("message top, page closed: $top")
    assertTrue(top < 40.dp, "at $top")
  }

  @Test
  fun closingThePageKeepsTheMessagesWindow() = runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
    mainClock.autoAdvance = false
    val open = mutableStateOf(true)
    show(open = open, duration = SnackbarDuration.Short)
    // Short is 4 s: 3 s of it on the page, then the page closes.
    mainClock.advanceTimeBy(3_000)
    onNodeWithText("Cleared 3").assertExists()
    open.value = false
    mainClock.advanceTimeBy(200)
    onNodeWithText("Cleared 3").assertExists()
    assertTrue(onNodeWithText("Cleared 3").getBoundsInRoot().top < 40.dp)
    // The same window runs out: a new host would have started it again and still show it here.
    mainClock.advanceTimeBy(2_000)
    onNodeWithText("Cleared 3").assertDoesNotExist()
  }
}

/** A phone's window in px, at a density of 2.625. */
private const val PHONE_WIDTH = 1080
private const val PHONE_HEIGHT = 2402
