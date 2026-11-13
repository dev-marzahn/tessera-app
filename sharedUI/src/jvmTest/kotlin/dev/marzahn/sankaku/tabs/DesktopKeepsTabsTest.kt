package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.SeenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * #489: desktop has no Faded list, so nothing fades there (`fades = false`): a tab stays until it's closed, and sources
 * an earlier version faded come back as tabs at launch. The phone's fading is [FadingTest]'s.
 */
class DesktopKeepsTabsTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val file = InMemoryTextFile()

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private fun post(id: String) = Route.Post(id, "md5$id", listOf("a"), label = "Post $id")

  private fun launch(fades: Boolean) = TabsController(FileTabStateStore(file), clock = clock, seen = seen, fades = fades)

  // Browse (shown), then the source "a" with the unseen kept post p1 in its block, and the source "b", all touched at
  // [start] and saved.
  private fun saveUntouched(fades: Boolean): TabsController {
    val first = launch(fades)
    first.open(grid("a"), background = false)
    first.openInBlock(listOf(post("p1")))
    first.open(grid("b"), background = true)
    first.activate(first.tabs.value.first().id)
    FileTabStateStore(file).save(first.saved())
    return first
  }

  @Test
  fun aDesktopLaunchAfterFourDaysKeepsAnUntouchedSourceAndItsKeptPosts() {
    saveUntouched(fades = false)

    now = start + 4.days
    val second = launch(fades = false)

    assertEquals(listOf("Browse", "A", "Post p1", "B"), second.tabs.value.map { it.title })
    assertEquals(second.tabs.value[1].id, second.tabs.value[2].block)
    assertTrue(second.faded.value.isEmpty())
  }

  @Test
  fun nothingFadesOrIsDeletedOnDesktopHoweverLongItWasClosed() {
    saveUntouched(fades = false)

    now = start + 40.days
    val second = launch(fades = false)
    second.sweep()

    assertEquals(listOf("Browse", "A", "Post p1", "B"), second.tabs.value.map { it.title })
    assertTrue(second.faded.value.isEmpty())
  }

  @Test
  fun sourcesFadedBeforeComeBackAsTabsAtADesktopLaunch() {
    val phone = saveUntouched(fades = true)
    val a = phone.tabs.value.single { it.title == "A" }
    a.reportPlace(a.top as Route.Grid, GridPlace("p3", time = null))
    now = start + 4.days
    phone.sweep()
    assertEquals(listOf("A", "B"), phone.faded.value.map { it.title })
    FileTabStateStore(file).save(phone.saved())

    now = start + 20.days
    val desktop = launch(fades = false)

    assertEquals(listOf("Browse", "A", "Post p1", "B"), desktop.tabs.value.map { it.title })
    val restored = desktop.tabs.value.single { it.title == "A" }
    assertEquals(restored.id, desktop.tabs.value.single { it.title == "Post p1" }.block)
    assertEquals(GridPlace("p3", time = null), restored.place)
    assertTrue(desktop.faded.value.isEmpty())
    assertTrue(desktop.saved().faded.isEmpty())
  }

  @Test
  fun thePhoneStillFadesAtLaunch() {
    saveUntouched(fades = true)

    now = start + 4.days
    val second = launch(fades = true)

    assertEquals(listOf("Browse"), second.tabs.value.map { it.title })
    assertEquals(listOf("A", "B"), second.faded.value.map { it.title })
  }
}
