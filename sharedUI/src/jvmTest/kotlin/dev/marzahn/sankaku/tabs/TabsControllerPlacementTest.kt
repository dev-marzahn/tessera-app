package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where new tabs go (#161): blocks, "now" (a search in place), "later" (a new tab) and the touched rule. */
class TabsControllerPlacementTest {
  private val tabs = TabsController()
  private val navigator = TabsNavigator(tabs)

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private val titles get() = tabs.tabs.value.map { it.title }

  private val activePosition get() = tabs.tabs.value.indexOfFirst { it.id == tabs.active.value } + 1

  private fun positionOf(title: String) = titles.indexOf(title) + 1

  // What GridScreen does once the grid is scrolled or a post is opened from it.
  private fun touchActiveGrid() {
    val tab = tabs.activeTab
    tab.markTouched(tab.top as Route.Grid)
  }

  private fun openPost(id: String, from: Route.Grid) =
    navigator.openInNewTab(Route.Post(id, "m$id", from.tags, label = id))

  @Test
  fun postsOpenedInTheBackgroundGoAtTheEndOfTheirGridsBlock() {
    // Grid A is tab 2, followed by 47 other tabs.
    val a = grid("a")
    tabs.open(a, background = false)
    repeat(47) { tabs.open(grid("other$it"), background = true) }
    touchActiveGrid()

    listOf("p1", "p2", "p3").forEach { openPost(it, a) }

    assertEquals(listOf("A", "p1", "p2", "p3", "Other0"), titles.subList(1, 6))
    openPost("p4", a)
    assertEquals(6, positionOf("p4"))
    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun aPostOpenedFromAPostTabGoesAtTheEndOfThatTabsBlock() {
    val a = grid("a")
    tabs.open(a, background = false)
    tabs.open(grid("b"), background = true)
    openPost("p1", a)
    openPost("p2", a)
    tabs.activate(tabs.tabs.value[2].id)

    // A recommended post, or a parent, held from post p1.
    navigator.openInNewTab(Route.Post("p9", "m9", emptyList(), label = "p9"))

    assertEquals(listOf("Browse", "A", "p1", "p2", "p9", "B"), titles)
  }

  @Test
  fun aSearchOnAnUntouchedGridReplacesIt() {
    tabs.open(grid("blue"), background = false)

    navigator.goTo(grid("red"))

    assertEquals(listOf("Browse", "Red"), titles)
    assertEquals(listOf(grid("blue"), grid("red")), tabs.activeTab.backStack.toList())
  }

  @Test
  fun aSearchOnATouchedGridOpensInFrontOfItsBlockAndLeavesItInPlace() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    tabs.open(grid("later"), background = true)
    touchActiveGrid()
    val blueTab = tabs.activeTab
    openPost("p1", blue)

    navigator.goTo(grid("red"))

    assertEquals(listOf("Browse", "Red", "Blue", "p1", "Later"), titles)
    assertEquals("Red", tabs.activeTab.title)
    assertEquals(listOf<Route>(blue), blueTab.backStack.toList())
  }

  @Test
  fun aTagTappedOnAPostTabOpensInFrontOfItsBlock() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    openPost("p1", blue)
    openPost("p2", blue)
    tabs.activate(tabs.tabs.value[3].id)
    val p2 = tabs.activeTab
    val stack = p2.backStack.toList()

    navigator.goTo(grid("red"))

    assertEquals(listOf("Browse", "Red", "Blue", "p1", "p2"), titles)
    assertEquals(stack, p2.backStack.toList())
  }

  @Test
  fun movingOnPastANewSearchsBlockLandsOnTheItemYouLeft() {
    // Blue with 50 posts in its block, the 31st of them active.
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    (1..50).forEach { openPost("b$it", blue) }
    tabs.open(grid("after"), background = true)
    tabs.activate(tabs.tabs.value[positionOf("b31") - 1].id)

    // Tapping the tag red: red opens right before blue, and its posts follow it.
    val red = grid("red")
    navigator.goTo(red)
    assertEquals(positionOf("Blue") - 1, positionOf("Red"))
    touchActiveGrid()
    openPost("r1", red)
    openPost("r2", red)
    assertEquals(listOf("Red", "r1", "r2", "Blue"), titles.subList(1, 5))

    tabs.activate(tabs.tabs.value[positionOf("r2") - 1].id)
    tabs.step(forward = true, wrap = false)

    assertEquals("b31", tabs.activeTab.title)
    // Once back, the block steps as usual.
    tabs.step(forward = false, wrap = false)
    assertEquals("b30", tabs.activeTab.title)
    repeat(31) { tabs.step(forward = false, wrap = false) }
    assertEquals("r2", tabs.activeTab.title)
  }

  @Test
  fun aRabbitHoleUnwindsDepthFirst() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    openPost("b2", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    navigator.goTo(grid("red"))
    touchActiveGrid()
    openPost("r1", grid("red"))
    tabs.activate(tabs.tabs.value[positionOf("r1") - 1].id)
    navigator.goTo(grid("green"))

    assertEquals(listOf("Browse", "Green", "Red", "r1", "Blue", "b1", "b2"), titles)
    tabs.step(forward = true, wrap = false)
    assertEquals("r1", tabs.activeTab.title)
    tabs.step(forward = true, wrap = false)
    assertEquals("b1", tabs.activeTab.title)
  }

  @Test
  fun closingASearchOpenedNowReturnsToTheItemItWasOpenedFrom() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    openPost("b2", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    navigator.goTo(grid("red"))

    // ✕ or "Done with red?", as moving on past it would: the post it was opened on, not Blue to its right.
    tabs.close(tabs.active.value)

    assertEquals("b1", tabs.activeTab.title)
  }

  @Test
  fun closingAnyOtherTabStillActivatesTheTabToTheRight() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    openPost("b2", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)

    tabs.close(tabs.active.value)

    assertEquals("b2", tabs.activeTab.title)
  }

  @Test
  fun closingTheLastTabInTheStripGoesToItsLeftNeighbourNotTheItemYouLeft() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    openPost("b2", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    navigator.goTo(grid("red"))
    tabs.open(grid("x"), background = false)

    tabs.close(tabs.active.value)

    assertEquals("b2", tabs.activeTab.title)
  }

  @Test
  fun aReopenedBlockStillReturnsToTheItemYouLeft() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    navigator.goTo(grid("red"))
    val block = tabs.tabs.value.filter { it.title == "Blue" || it.title == "b1" }.map { it.id }

    // A batch with no active member comes back without activating, so the marker carries over to the new ids.
    tabs.closeAll(block)
    tabs.reopen(tabs.closed.value.last())
    tabs.step(forward = true, wrap = false)

    assertEquals("b1", tabs.activeTab.title)
  }

  @Test
  fun aLookAtAnotherItemOfTheBlockKeepsTheItemYouLeft() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    navigator.goTo(grid("red"))

    // From the queue page, a look at blue, then back to red and on.
    tabs.activate(tabs.tabs.value[positionOf("Blue") - 1].id)
    tabs.step(forward = false, wrap = false)
    tabs.step(forward = true, wrap = false)

    assertEquals("b1", tabs.activeTab.title)
  }

  @Test
  fun theItemYouLeftIsForgottenOnceShown() {
    val blue = grid("blue")
    tabs.open(blue, background = false)
    touchActiveGrid()
    openPost("b1", blue)
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    navigator.goTo(grid("red"))

    // Reached from the queue page: moving on from red is its neighbour again.
    tabs.activate(tabs.tabs.value[positionOf("b1") - 1].id)
    tabs.activate(tabs.tabs.value[positionOf("Red") - 1].id)
    tabs.step(forward = true, wrap = false)

    assertEquals("Blue", tabs.activeTab.title)
  }

  @Test
  fun aSearchOpenedInTheBackgroundGoesAfterThisSessionsTabs() {
    val saved = SavedTabs(0, (1..4).map { SavedTab("old$it", listOf(Route.Grid(listOf("old$it")))) })
    val tabs = TabsController(FakeStore(saved))
    val navigator = TabsNavigator(tabs)

    // Nothing added in this session yet: the end of the queue, never ahead of what's being gone through.
    navigator.openInNewTab(grid("red"))
    assertEquals(listOf("Old1", "Old2", "Old3", "Old4", "Red"), tabs.tabs.value.map { it.title })

    // After a post opened into an old grid's block, which joins this session, "later" goes after the last item added.
    tabs.activate(TabId("old2"))
    navigator.openInNewTab(Route.Post("p1", "m1", listOf("old2"), label = "p1"))
    navigator.openInNewTab(grid("green"))

    assertEquals(listOf("Old1", "Old2", "p1", "Old3", "Old4", "Red", "Green"), tabs.tabs.value.map { it.title })
    assertEquals("Old2", tabs.activeTab.title)
  }

  @Test
  fun aNewForegroundTabGoesAtTheEndOfTheSessionToo() {
    val saved = SavedTabs(0, listOf(SavedTab("old", listOf(grid("old")))))
    val tabs = TabsController(FakeStore(saved))

    tabs.open(Route.Grid(emptyList()), background = false)

    assertEquals(listOf("Old", "Browse"), tabs.tabs.value.map { it.title })
    assertEquals("Browse", tabs.activeTab.title)
  }

  @Test
  fun aReopenedTabReturnsToItsBlock() {
    val a = grid("a")
    tabs.open(a, background = false)
    tabs.open(grid("b"), background = true)
    openPost("p1", a)
    val aTab = tabs.activeTab

    tabs.close(aTab.id)
    tabs.reopenClosed()
    openPost("p2", a)

    assertEquals(listOf("Browse", "A", "p1", "p2", "B"), titles)
    assertTrue(tabs.tabs.value.drop(2).take(2).all { it.block == tabs.tabs.value[1].id })
  }

  @Test
  fun thePillsArrowsStopAtTheEnds() {
    tabs.open(grid("a"), background = true)

    assertFalse(tabs.step(forward = false, wrap = false))
    assertEquals(1, activePosition)
    assertTrue(tabs.step(forward = true, wrap = false))
    assertFalse(tabs.step(forward = true, wrap = false))
    assertEquals(2, activePosition)
  }

  @Test
  fun blocksSurviveARestart() {
    val file = InMemoryTextFile(null)
    val store = FileTabStateStore(file)
    val a = grid("a")
    tabs.open(a, background = false)
    openPost("p1", a)
    store.save(tabs.saved())

    val after = TabsController(store)
    after.activate(after.tabs.value[1].id)
    TabsNavigator(after).openInNewTab(Route.Post("p2", "m2", a.tags, label = "p2"))

    assertEquals(listOf("Browse", "A", "p1", "p2"), after.tabs.value.map { it.title })
  }

  @Test
  fun aFileSavedBeforeBlocksLoadsWithItsTabsAndNoBlocks() {
    // The format as it was before blocks: each tab only its id and routes. It migrates to v2 (#174), with nothing added
    // to the tabs but the queue's facts.
    fun routes(vararg routes: Route) = routes.joinToString(",", "[", "]") { Json.encodeToString(Route.serializer(), it) }
    val text = """{"activeIndex":1,"tabs":[{"id":"t0","routes":${routes(Route.Grid(emptyList()))}},""" +
      """{"id":"t1","routes":${routes(Route.Grid(listOf("a"), at = "p1"), Route.Post("p1", "m1", listOf("a")))}}]}"""
    val loaded = FileTabStateStore(InMemoryTextFile(text)).load()!!
    val controller = TabsController(FakeStore(loaded))

    val saved = controller.saved()

    assertEquals(listOf("Browse", "Untitled post"), controller.tabs.value.map { it.title })
    assertEquals(1, saved.activeIndex)
    assertEquals(listOf("t0", "t1"), saved.tabs.map { it.id })
    assertEquals(listOf(null, null), saved.tabs.map { it.block })
    assertEquals(listOf(null, null), saved.tabs.map { it.place })
  }

  private class FakeStore(val saved: SavedTabs?) : TabStateStore {
    override fun load() = saved

    override fun save(tabs: SavedTabs) = Unit

    override fun clear() = Unit
  }
}
