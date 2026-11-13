package dev.marzahn.sankaku.features.grid

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.features.post.keepingFrom
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.queue.Viewing
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.keptLeft
import org.koin.core.context.GlobalContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * #537: a post kept from the viewer is born unseen. The viewer had fully shown it, and moving on from it then made it
 * "seen since kept", so Clear viewed removed it and "Queue done" came while it was still in the queue. That showing
 * came before the keep, so it doesn't count.
 */
@OptIn(ExperimentalTestApi::class)
class KeptFromViewerUnseenTest {
  private val fixture = GridScreenFixture(images = true)

  @BeforeTest
  fun start() = fixture.start()

  @AfterTest
  fun stop() = fixture.stop()

  @Test
  fun aPostKeptWithEInTheViewerIsUnseen() = runDesktopComposeUiTest(width = 1280, height = 832) {
    with(fixture) {
      showGrid()
      cursorTo(1)
      press(Key.Spacebar)
      press(Key.D)
      assertEquals(2, viewerPost())
      val seen = GlobalContext.get().get<SeenStore>()
      // The viewer does fully show its posts here: moving on from post 1 made it seen.
      assertTrue(seen.isSeen("fixture-1"), "the viewer never fully showed a post")

      press(Key.E)
      assertEquals("Kept in tab 2", messages.last())
      press(Key.Escape)
      waitForIdle()
      assertFalse(viewerOpen(), "Esc didn't close the viewer")

      val kept = tabs.tabs.value.single { (it.top as? Route.Post)?.id == "fixture-2" }
      assertFalse(kept.seenSinceKept(seen), "a post kept from the viewer is born seen")
      // Clear viewed leaves it, and with it left the queue isn't done (#502's "Queue done" reads keptLeft).
      assertEquals(emptyList(), tabs.viewedKeptPosts(seen))
      assertEquals(1, keptLeft(tabs.tabs.value) { it.seenSinceKept(seen) })

      // Back in the viewer on it, E again: kept already and unseen, so "Already", not "Kept again".
      press(Key.Spacebar)
      assertEquals(2, viewerPost())
      press(Key.E)
      assertEquals("Already in tab 2", messages.last())
    }
  }
}

/**
 * The phone's ⋮ Keep for later goes through [keepingFrom] as desktop's E does; no test composes the phone viewer, so
 * this drives that keep with the viewer's own [Viewing] and the grid's [keepPost].
 */
class PhoneKeepUnseenTest {
  private var now = Instant.parse("2026-11-13T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now.also { now += 1.seconds }
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock)
  private val navigator = TabsNavigator(tabs)
  private val grid = Route.Grid(listOf("sky"))
  private val post = PostDto(id = "p1", md5 = "aa11", rating = "s", status = "active")

  @Test
  fun keepForLaterFromThePhoneViewerIsUnseen() {
    tabs.open(grid, background = false)
    tabs.tabs.value.filter { it.top != grid }.forEach { tabs.close(it.id) }
    val viewing = Viewing(seen)
    val keep = viewing.keepingFrom { shown: PostDto -> keepPost(shown, grid.tags, navigator, tabs, seen) }
    viewing.current(post.id, post.md5)
    viewing.loaded(post.id)

    keep(post)
    // Force-stopped, or swiped on: the viewer leaves the post.
    viewing.leave()

    val kept = tabs.tabs.value.single { (it.top as? Route.Post)?.id == "p1" }
    assertFalse(kept.seenSinceKept(seen))
    assertEquals(emptyList(), tabs.viewedKeptPosts(seen))
    assertEquals(1, keptLeft(tabs.tabs.value) { it.seenSinceKept(seen) })
  }
}
