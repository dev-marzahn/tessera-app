package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.TabsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * #504: keeping a post from the viewer is the grid's keep. It goes at the end of the grid's block, after the posts kept
 * from it before, and a post already in the queue opens nothing and answers where it is, or "Kept again" when it had
 * been seen since it was kept.
 */
class KeepPostTest {
  private var now = Instant.parse("2026-11-12T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock)
  private val navigator = TabsNavigator(tabs)
  private val grid = Route.Grid(listOf("fixture_tag"))
  private val other = Route.Grid(listOf("other_tag"))

  private fun post(id: String) = PostDto(id = id, md5 = "md5-$id", rating = "s", status = "active")

  private fun keep(post: PostDto) = keepPost(post, grid.tags, navigator, tabs, seen)

  private fun tops() = tabs.tabs.value.map { (it.top as? Route.Post)?.id ?: (it.top as Route.Grid).tags.joinToString(" ") }

  @Test
  fun aPostKeptFromTheViewerGoesAtTheEndOfItsGridsBlock() {
    navigator.goTo(grid)
    tabs.open(other, background = true)
    val source = tabs.activeTab

    assertEquals(Kept(2, already = false), keep(post("a")))
    assertEquals(Kept(3, already = false), keep(post("b")))

    assertEquals(listOf("fixture_tag", "a", "b", "other_tag"), tops())
    assertEquals(source, tabs.tabs.value.drop(1).take(2).map { tabs.sourceOf(it) }.distinct().single())
    // Kept in the background: the grid stays shown.
    assertEquals(source, tabs.activeTab)
  }

  @Test
  fun aPostAlreadyKeptOpensNothingAndSaysWhere() {
    navigator.goTo(grid)
    keep(post("a"))
    keep(post("b"))

    assertEquals(Kept(2, already = true), keep(post("a")))
    assertEquals(listOf("fixture_tag", "a", "b"), tops())
  }

  @Test
  fun aKeptPostSeenSinceItWasKeptIsKeptAgain() {
    navigator.goTo(grid)
    keep(post("a"))
    now += 1.minutes
    seen.record("a", "md5-a")

    assertEquals(Kept(2, already = true, again = true), keep(post("a")))
    // Kept anew: unseen again.
    assertEquals(false, tabs.keptPostSeen("a", seen))
  }

  @Test
  fun theAnswerUsesEachPlatformsWords() {
    assertEquals("Kept · item 4", keptMessage(Kept(4, already = false), touch = true))
    assertEquals("Kept in tab 4", keptMessage(Kept(4, already = false), touch = false))
    assertEquals("Already item 4", keptMessage(Kept(4, already = true), touch = true))
    assertEquals("Already in tab 4", keptMessage(Kept(4, already = true), touch = false))
    assertEquals("Kept again", keptMessage(Kept(4, already = true, again = true), touch = true))
    assertEquals("Kept again", keptMessage(Kept(4, already = true, again = true), touch = false))
  }
}
