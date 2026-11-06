package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.dto.TagType
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.features.post.cellDescription
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.components.seenImage
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * #262 drawn, with synthetic fixture pictures (never a real post): three cells of one grid, each with the cell's own
 * seen mark (dimming, badge, chip and TalkBack description) and `seen` from [TabsController.seenFromGrid]. A post kept
 * from the grid and viewed is marked; one viewed but not kept isn't, nor is one kept from another source. Then Kept
 * again and Mark unseen take the mark away. With `EVIDENCE_DIR` set, each frame is saved there as a PNG for the PR.
 */
@OptIn(ExperimentalTestApi::class)
class SeenFromGridRenderTest {
  private var now = Instant.parse("2026-11-02T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock)
  private val navigator = TabsNavigator(tabs)
  private val scenery = Route.Grid(listOf("scenery"))
  private val sky = Route.Grid(listOf("sky"))

  // Named by a character tag, as a cell's description is.
  private fun post(id: String, name: String) =
    PostDto(id = id, md5 = "$id$id", rating = "s", status = "active", tags = listOf(TagDto(name, type = TagType.Character.id)))

  private val keptHere = post("a", "Fixture A")
  private val viewedOnly = post("b", "Fixture B")
  private val keptElsewhere = post("c", "Fixture C")

  /** A 256 px picture of coloured stripes and a square, so the dimming shows. */
  private fun fixture(first: Int, second: Int): Bitmap {
    val bitmap = Bitmap().apply { allocN32Pixels(256, 256) }
    Canvas(bitmap).apply {
      for (i in 0 until 8) drawRect(Rect.makeXYWH(i * 32f, 0f, 32f, 256f), Paint().apply { color = if (i % 2 == 0) first else second })
      drawRect(Rect.makeXYWH(80f, 80f, 96f, 96f), Paint().apply { color = 0xFFFFFFFF.toInt() })
    }
    return bitmap
  }

  private val pictures = mapOf(
    "a" to fixture(0xFF2E7D32.toInt(), 0xFFFFC107.toInt()).asComposeImageBitmap(),
    "b" to fixture(0xFF1565C0.toInt(), 0xFFE53935.toInt()).asComposeImageBitmap(),
    "c" to fixture(0xFF6A1B9A.toInt(), 0xFF00ACC1.toInt()).asComposeImageBitmap(),
  )

  private fun keep(from: Route.Grid, post: PostDto) {
    navigator.goTo(from)
    navigator.openInNewTab(Route.Post(post.id, post.md5, from.tags, "Fixture ${post.id}", post.rating))
  }

  private fun keptTab(post: PostDto) = tabs.tabs.value.single { (it.top as? Route.Post)?.id == post.id }

  /** One cell as the grid draws it: the picture dimmed when seen, the overlay's badge and chip, its description. */
  @Composable
  private fun Cell(post: PostDto, label: String, grid: Route.Grid) {
    // The grid's own wiring (GridScreen calls it too), with the tab the grid is shown in.
    val isSeen = seenMark(grid, tabs.tabs.value.single { it.top == grid }, tabs, seen)(post)
    val openInTab = post.id in tabs.openPosts(tabs.tabs.value)
    val description = cellDescription(post, openInTab = openInTab, seen = isSeen, touch = true)
    Column(Modifier.padding(8.dp)) {
      Box(
        Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).testTag("cell-${post.id}")
          .semantics(mergeDescendants = true) { contentDescription = description },
      ) {
        Image(pictures.getValue(post.id), null, Modifier.fillMaxSize().seenImage(isSeen), contentScale = ContentScale.Crop)
        // As in the grid, the overlay says nothing of its own: the cell's description says it all.
        CellOverlay(
          favourited = false, unfavourited = false, hovered = false, onToggleFavourite = {},
          modifier = Modifier.clearAndSetSemantics {}, openInTab = openInTab, seen = isSeen,
        )
      }
      Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp).size(150.dp, 40.dp))
    }
  }

  private fun androidx.compose.ui.test.ComposeUiTest.render(heading: String, file: String? = null) {
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
        SankakuTheme {
          Surface {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(heading, style = MaterialTheme.typography.titleSmall)
              Row {
                Cell(keptHere, "Kept from scenery, viewed", scenery)
                Cell(viewedOnly, "Viewed, not kept", scenery)
                Cell(keptElsewhere, "Kept from sky, viewed", scenery)
              }
            }
          }
        }
      }
    }
    waitForIdle()
    if (file != null) System.getenv("EVIDENCE_DIR")?.let { dir -> ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, file)) }
  }

  private fun setUp() {
    tabs.open(sky, background = true)
    keep(scenery, keptHere)
    keep(sky, keptElsewhere)
    now += 1.minutes
    listOf(keptHere, viewedOnly, keptElsewhere).forEach { seen.record(it.id, it.md5) }
  }

  @Test
  fun onlyThePostKeptFromThisGridIsMarked() = runDesktopComposeUiTest(width = 1300, height = 700) {
    setUp()
    render("Grid: scenery", "seen-three-cases.png")

    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Seen. Kept")
    onNodeWithTag("cell-b").assertContentDescriptionEquals("Fixture B")
    onNodeWithTag("cell-c").assertContentDescriptionEquals("Fixture C. Kept")
  }

  @Test
  fun aShownGridFollowsAPostBeingSeenKeptAgainAndClosed() = runDesktopComposeUiTest(width = 1300, height = 700) {
    tabs.open(sky, background = true)
    keep(scenery, keptHere)
    render("Grid: scenery")
    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Kept")

    // Seen while the grid is shown (the viewer closing): the store's revision redraws it.
    now += 1.minutes
    seen.record(keptHere.id, keptHere.md5)
    waitForIdle()
    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Seen. Kept")

    // Kept again: the kept time, snapshot state, redraws it.
    now += 1.minutes
    tabs.keepAgain(keptTab(keptHere).id)
    waitForIdle()
    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Kept")

    // Seen again, then closed: the tabs redraw it, and a post no longer kept isn't marked.
    now += 1.minutes
    seen.record(keptHere.id, keptHere.md5)
    waitForIdle()
    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Seen. Kept")
    tabs.close(keptTab(keptHere).id)
    waitForIdle()
    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A")
  }

  @Test
  fun aTabShowingSomethingElseOnTopMarksNothing() = runDesktopComposeUiTest(width = 400, height = 400) {
    setUp()
    val source = tabs.tabs.value.single { it.top == scenery }
    var onTop: Boolean? = null
    var underneath: Boolean? = null
    setContent {
      onTop = seenMark(scenery, source, tabs, seen)(keptHere)
      // A grid route that isn't the one on top of the tab (a search gone elsewhere in place) marks nothing.
      underneath = seenMark(Route.Grid(listOf("other")), source, tabs, seen)(keptHere)
    }
    waitForIdle()

    assertEquals(true, onTop)
    assertEquals(false, underneath)
  }

  @Test
  fun keptAgainTakesTheMarkAway() = runDesktopComposeUiTest(width = 1300, height = 700) {
    setUp()
    now += 1.minutes
    // A long-press on the cell again: "Kept again".
    navigator.goTo(scenery)
    navigator.openInNewTab(Route.Post(keptHere.id, keptHere.md5, scenery.tags))
    render("Grid: scenery, after Kept again on the first post", "seen-kept-again.png")

    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Kept")
  }

  @Test
  fun markUnseenTakesTheMarkAway() = runDesktopComposeUiTest(width = 1300, height = 700) {
    setUp()
    now += 1.minutes
    // The queue page's Mark unseen on the kept post.
    tabs.keepAgain(keptTab(keptHere).id)
    render("Grid: scenery, after Mark unseen on the first post", "seen-mark-unseen.png")

    onNodeWithTag("cell-a").assertContentDescriptionEquals("Fixture A. Kept")
  }
}
