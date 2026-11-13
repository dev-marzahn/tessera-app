package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * #245: whether a grid was touched (scrolled, a post opened from it, restored at its place) is kept on its tab, not in
 * the grid's screen, so it outlasts the grid leaving composition (the queue page covering it) and a relaunch. A search
 * on a touched grid opens in front of its block (#161); an untouched one is still replaced in place.
 */
class TouchedGridTest {
  private val file = InMemoryTextFile(null)
  private val store = FileTabStateStore(file)
  private val tabs = TabsController(store)
  private val browse = Route.Grid(emptyList())
  private val scenery = Route.Grid(listOf("scenery"))

  private val titles get() = tabs.tabs.value.map { it.title }

  @Test
  fun aSearchOnATouchedGridOpensANewSourceInFrontOfIt() {
    val grid = tabs.activeTab
    grid.markTouched(browse)

    TabsNavigator(tabs).goTo(scenery)

    assertEquals(listOf("Scenery", "Browse"), titles)
    assertEquals(listOf<Route>(browse), grid.backStack.toList())
  }

  @Test
  fun aSearchOnAnUntouchedGridStillReplacesItInPlace() {
    TabsNavigator(tabs).goTo(scenery)

    assertEquals(listOf("Scenery"), titles)
    assertEquals(listOf(browse, scenery), tabs.activeTab.backStack.toList())
  }

  @Test
  fun theFlagIsForTheGridItWasSetOnAndComesBackWithIt() {
    val tab = tabs.activeTab
    tab.markTouched(browse)
    assertTrue(tab.isTouched(browse))

    // The search runs in this tab when it's this source's (a new order): the grid on top isn't the one touched.
    tab.backStack.add(Route.Grid(listOf("order:popularity")))
    assertFalse(tab.topIsTouchedGrid)
    tab.backStack.removeLast()
    assertTrue(tab.topIsTouchedGrid)
  }

  @Test
  fun aTouchedGridIsSavedWithItsTabAndStaysTouchedAfterARelaunch() {
    tabs.activeTab.markTouched(browse)
    store.save(tabs.saved())
    assertTrue(file.read()!!.contains(""""gridTouched":true"""), file.read())

    val relaunched = TabsController(store)
    assertTrue(relaunched.activeTab.topIsTouchedGrid)
    TabsNavigator(relaunched).goTo(scenery)

    assertEquals(listOf("Scenery", "Browse"), relaunched.tabs.value.map { it.title })
  }

  @Test
  fun anUntouchedGridSavesNoFlag() {
    tabs.open(scenery, background = true)
    store.save(tabs.saved())

    assertFalse(file.read()!!.contains("gridTouched"))
    assertTrue(TabsController(store).tabs.value.none { it.topIsTouchedGrid })
  }

  @Test
  fun aFileWithoutTheFlagLoadsAndSavesBackUnchanged() {
    val saved = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
      """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}],"kind":"source","touched":1000,"addedIn":"s1"},""" +
      """{"id":"b","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_b"]}],"place":{"post":"p9"},"kind":"source","touched":1000,"addedIn":"s1"}""" +
      """]}"""
    val existing = InMemoryTextFile(saved)
    val existingStore = FileTabStateStore(existing)
    val before = existingStore.load()
    val clock = object : Clock {
      override fun now() = Instant.fromEpochSeconds(1000)
    }
    val controller = TabsController(existingStore, clock = clock)

    assertEquals(before, controller.saved())
    // A grid kept at its place counts as touched with no flag of its own; one with neither is untouched.
    assertEquals(listOf(false, true), controller.tabs.value.map { it.topIsTouchedGrid })
    existingStore.save(controller.saved())
    assertFalse(existing.read()!!.contains("gridTouched"))
    assertEquals(before, existingStore.load())
  }

  @Test
  fun aV1FileMigratesUntouched() {
    val v1 = """{"activeIndex":0,"tabs":[{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}]}]}"""

    val migrated = TabsController(FileTabStateStore(InMemoryTextFile(v1))).saved()

    assertNull(migrated.tabs.single().gridTouched)
  }

  @Test
  fun aGridWithAPlaceIsTouchedBeforeItScrollsThereAndSavesNoFlag() {
    tabs.activeTab.reportPlace(browse, GridPlace("p30", time = null))

    assertTrue(tabs.activeTab.topIsTouchedGrid)
    assertNull(tabs.saved().tabs.single().gridTouched)
    TabsNavigator(tabs).goTo(scenery)
    assertEquals(listOf("Scenery", "Browse"), titles)
  }

  @Test
  fun theSameGridOpenedAgainAfterBackIsUntouched() {
    val navigator = TabsNavigator(tabs)
    navigator.goTo(scenery)
    tabs.activeTab.markTouched(scenery)

    navigator.goBack()
    navigator.goTo(scenery)

    assertFalse(tabs.activeTab.topIsTouchedGrid)
    assertEquals(listOf(browse, scenery), tabs.activeTab.backStack.toList())
  }

  @Test
  fun aTouchedGridReopenedFromRecentlyClosedIsStillTouched() {
    tabs.open(scenery, background = false)
    tabs.activeTab.markTouched(scenery)

    tabs.close(tabs.activeTab.id)
    tabs.reopenClosed()

    assertEquals("Scenery", tabs.activeTab.title)
    assertTrue(tabs.activeTab.topIsTouchedGrid)
  }

  @Test
  fun aTouchedGridThatFadesComesBackTouchedAfterARelaunch() {
    var now = Instant.parse("2026-11-01T12:00:00Z")
    val clock = object : Clock {
      override fun now() = now
    }
    val fadingStore = FileTabStateStore(InMemoryTextFile(null))
    val controller = TabsController(fadingStore, clock = clock)
    controller.open(scenery, background = true)
    controller.tabs.value.single { it.top == scenery }.markTouched(scenery)
    now += 4.days
    controller.sweep()
    fadingStore.save(controller.saved())

    val relaunched = TabsController(fadingStore, clock = clock)
    val restored = relaunched.restore(relaunched.faded.value.single().id, show = true)!!

    assertTrue(restored.topIsTouchedGrid)
  }

  @Test
  fun scrollingBackToTheTopLeavesItTouched() {
    // The place goes back to null at the top; the flag doesn't follow it.
    val tab = tabs.activeTab
    tab.reportPlace(browse, GridPlace("p30", time = null))
    tab.markTouched(browse)
    tab.reportPlace(browse, null)

    assertTrue(tab.topIsTouchedGrid)
    assertEquals(true, tabs.saved().tabs.single().gridTouched)
  }
}
