package dev.marzahn.sankaku

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.snapshots.Snapshot
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.SavedTab
import dev.marzahn.sankaku.tabs.SavedTabs
import dev.marzahn.sankaku.tabs.TabId
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.di.shellModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NavigatorTest {
  private val tabs = TabsController()
  private val navigator = TabsNavigator(tabs)
  private val grid = Route.Grid(listOf("blue_hair"))

  @Test
  fun goBackOnASingleEntryDoesNothing() {
    navigator.goBack()

    assertEquals(listOf(Route.Grid(emptyList())), tabs.activeTab.backStack.toList())
  }

  /** #181: a post tapped open anywhere but a grid cell is a lone post after this tab, and Back returns to this tab. */
  @Test
  fun aPostTappedOpensAloneAfterThisTabAndBackReturns() {
    navigator.goTo(grid)
    val source = tabs.activeTab
    val p1 = Route.Post("p1", "m1", grid.tags)
    val p0 = Route.Post("p0", null, emptyList())
    navigator.goTo(p1)
    navigator.goTo(p0)

    assertEquals(listOf(source.top, p1, p0), tabs.tabs.value.map { it.top })
    assertEquals(listOf(listOf<Route>(p1), listOf(p0)), tabs.tabs.value.drop(1).map { it.backStack.toList() })
    // Both in the source's block, so it's what they were kept from.
    assertEquals(listOf(source, source), tabs.tabs.value.drop(1).map { tabs.sourceOf(it) })
    assertEquals(p0, tabs.activeTab.top)

    navigator.goBack()
    assertEquals(p1, tabs.activeTab.top)
    navigator.goBack()
    assertEquals(source, tabs.activeTab)
    // A grid's Back stays within its tab.
    navigator.goBack()
    assertEquals(listOf(Route.Grid(emptyList())), source.backStack.toList())
    assertEquals(source, tabs.activeTab)
  }

  @Test
  fun aPostTappedThatATabShowsGoesToThatTab() {
    val post = Route.Post("p1", "m1", grid.tags)
    navigator.openInNewTab(post)

    navigator.goTo(post)

    assertEquals(2, tabs.tabs.value.size)
    assertEquals(post, tabs.activeTab.top)
  }

  @Test
  fun backOnTheFirstItemsLonePostDoesNothing() {
    val post = Route.Post("p1", "m1", grid.tags)
    navigator.goTo(post)
    tabs.close(tabs.tabs.value.first().id)

    navigator.goBack()

    assertEquals(post, tabs.activeTab.top)
  }

  @Test
  fun aPostOpenedInANewTabIsAlone() {
    val post = Route.Post("p1", "m1", grid.tags)

    navigator.openInNewTab(post)

    assertEquals(listOf<Route>(post), tabs.tabs.value.last().backStack.toList())
  }

  @Test
  fun aPostOnlyUnderATabsTopOpensAgain() {
    navigator.openInNewTab(Route.Post("p1", "m1", grid.tags))
    tabs.tabs.value.last().backStack.add(grid)

    assertEquals(null, navigator.openInNewTab(Route.Post("p1", "m1", grid.tags)))
    assertEquals(3, tabs.tabs.value.size)
  }

  @Test
  fun openInNewTabLeavesTheCurrentTabActive() {
    val before = tabs.active.value

    navigator.openInNewTab(grid)

    assertEquals(before, tabs.active.value)
    assertEquals(2, tabs.tabs.value.size)
  }


  @Test
  fun aPostAlreadyInATabOpensNoNewTabAndGivesItsPosition() {
    tabs.open(grid, background = true)
    assertEquals(null, navigator.openInNewTab(Route.Post("p1", "m1", grid.tags)))

    // From another search, or without its md5 (a parent link), it's still the same post: tab 2, in the active
    // Browse tab's block, before the grid.
    assertEquals(2, navigator.openInNewTab(Route.Post("p1", null, emptyList())))
    assertEquals(3, tabs.tabs.value.size)
  }


  @Test
  fun theSameSearchOpensOnce() {
    assertEquals(null, navigator.openInNewTab(grid))

    // One source per search (#174): the second opens nothing and says where the source is.
    assertEquals(tabs.tabs.value.indexOfFirst { it.top == grid } + 1, navigator.openInNewTab(grid))
    assertEquals(2, tabs.tabs.value.size)
  }
}

class TabsControllerTest {
  private val tabs = TabsController()

  private class Probe : ViewModel() {
    var cleared = false

    override fun onCleared() {
      cleared = true
    }
  }

  private fun probeIn(owner: dev.marzahn.sankaku.tabs.TabState): Probe =
    ViewModelProvider.create(
      owner,
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T =
          Probe() as T
      },
    )[Probe::class]

  @Test
  fun newTabsOpenAtTheEndAndOnlyAForegroundOneActivates() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.open(Route.Grid(listOf("b")), background = false)

    assertEquals(listOf("Browse", "A", "B"), tabs.tabs.value.map { it.title })
    assertEquals("B", tabs.activeTab.title)
  }

  @Test
  fun switchingTabsKeepsTheirViewModels() {
    val first = tabs.activeTab
    val probe = probeIn(first)
    tabs.open(Route.Grid(listOf("a")), background = false)
    tabs.activate(first.id)

    assertTrue(probe === probeIn(first))
    assertEquals(false, probe.cleared)
  }

  @Test
  fun closingATabClearsItsViewModelsAndActivatesANeighbour() {
    tabs.open(Route.Grid(listOf("a")), background = false)
    val closing = tabs.activeTab
    val probe = probeIn(closing)

    tabs.close(closing.id)

    assertTrue(probe.cleared)
    assertEquals("Browse", tabs.activeTab.title)
  }

  @Test
  fun closingTheLastTabLeavesAFreshGrid() {
    val only = tabs.activeTab.id

    tabs.close(only)

    assertEquals(1, tabs.tabs.value.size)
    assertNotEquals(only, tabs.active.value)
  }

  @Test
  fun cyclingWrapsAround() {
    tabs.open(Route.Grid(listOf("a")), background = true)

    tabs.cycle(forward = false)

    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun closedTabsReopenInTheirPlacesWithTheirBackStacksMostRecentFirst() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.open(listOf(Route.Grid(listOf("b"), at = "p1"), Route.Post("p1", "m1", listOf("b"))), background = true)
    tabs.open(Route.Grid(listOf("c")), background = true)
    val before = tabs.stacks
    val (_, a, b, _) = tabs.tabs.value

    tabs.close(a.id)
    tabs.close(b.id)
    tabs.reopenClosed()

    assertEquals(listOf(before[0], before[2], before[3]), tabs.stacks)
    assertEquals(before[2], tabs.activeTab.backStack.toList())
    tabs.reopenClosed()
    assertEquals(before, tabs.stacks)
    assertEquals(before[1], tabs.activeTab.backStack.toList())
  }

  @Test
  fun reopeningWithNothingClosedDoesNothing() {
    tabs.reopenClosed()

    assertEquals(listOf(listOf<Route>(Route.Grid(emptyList()))), tabs.stacks)
  }

  @Test
  fun onlyTheLastTwentyClosedTabsCanBeReopened() {
    repeat(21) { tabs.open(Route.Grid(listOf("t$it")), background = true) }
    tabs.tabs.value.drop(1).forEach { tabs.close(it.id) }

    repeat(25) { tabs.reopenClosed() }

    assertEquals(listOf("Browse") + (1..20).map { "T$it" }, tabs.tabs.value.map { it.title })
  }

  @Test
  fun closingTheLastTabCanBeUndone() {
    tabs.activeTab.backStack.add(Route.Grid(listOf("a")))

    tabs.close(tabs.active.value)
    tabs.reopenClosed()

    assertEquals(listOf(Route.Grid(emptyList()), Route.Grid(listOf("a"))), tabs.activeTab.backStack.toList())
  }

  @Test
  fun postsShowingInATabAreOpenUntilItCloses() {
    tabs.open(listOf(Route.Grid(listOf("a"), at = "p1"), Route.Post("p1", "m1", listOf("a"))), background = true)
    tabs.open(listOf(Route.Post("p2", "m2", emptyList()), Route.Grid(listOf("b"))), background = true)
    val p1 = tabs.tabs.value[1]

    assertEquals(setOf("p1"), tabs.openPosts(tabs.tabs.value))
    tabs.close(p1.id)
    assertEquals(emptySet<String>(), tabs.openPosts(tabs.tabs.value))
    tabs.reopenClosed()
    assertEquals(setOf("p1"), tabs.openPosts(tabs.tabs.value))
  }

  @Test
  fun anOlderClosedTabReopensAtItsPlaceAndLeavesTheRestReopenable() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.open(Route.Grid(listOf("b")), background = true)
    tabs.open(Route.Grid(listOf("c")), background = true)
    val (_, a, _, c) = tabs.tabs.value
    tabs.close(a.id)
    tabs.close(c.id)

    tabs.reopen(tabs.closed.value.first())

    assertEquals(listOf("Browse", "A", "B"), tabs.tabs.value.map { it.title })
    assertEquals("A", tabs.activeTab.title)
    assertEquals(listOf("C"), tabs.closed.value.map { it.title })
    tabs.reopenClosed()
    assertEquals(listOf("Browse", "A", "B", "C"), tabs.tabs.value.map { it.title })
    assertTrue(tabs.closed.value.isEmpty())
  }

  @Test
  fun anUndoHeldSinceAnotherReopenStillReturnsToTheRightPlace() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.open(Route.Grid(listOf("b")), background = true)
    tabs.open(Route.Grid(listOf("c")), background = true)
    val (_, a, _, c) = tabs.tabs.value
    tabs.close(a.id)
    // The snackbar's Undo keeps the copy it was given.
    val undoC = tabs.close(c.id)!!

    tabs.reopen(tabs.closed.value.first())
    tabs.reopen(undoC)

    assertEquals(listOf("Browse", "A", "B", "C"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun closingATabThatIsGoneClosesNothing() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    val a = tabs.tabs.value[1]
    tabs.close(a.id)

    assertEquals(null, tabs.close(a.id))
    assertEquals(1, tabs.closed.value.size)
  }

  @Test
  fun aClosedTabReopensOnlyOnce() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.close(tabs.tabs.value[1].id)
    val closed = tabs.closed.value.single()

    tabs.reopen(closed)
    tabs.reopen(closed)

    assertEquals(listOf("Browse", "A"), tabs.tabs.value.map { it.title })
  }

  @Test
  fun movingATabKeepsTheActiveOne() {
    tabs.open(Route.Grid(listOf("a")), background = true)
    tabs.open(Route.Grid(listOf("b")), background = true)
    val active = tabs.active.value

    tabs.move(0, 2)
    assertEquals(listOf("A", "B", "Browse"), tabs.tabs.value.map { it.title })
    tabs.move(2, 1)
    assertEquals(listOf("A", "Browse", "B"), tabs.tabs.value.map { it.title })
    tabs.move(0, 5)
    assertEquals(listOf("A", "Browse", "B"), tabs.tabs.value.map { it.title })
    assertEquals(active, tabs.active.value)
  }

  @Test
  fun aPostOpenedWithinATabCounts() {
    tabs.activeTab.backStack.add(Route.Post("p3", null, emptyList()))

    assertEquals(setOf("p3"), tabs.openPosts(tabs.tabs.value))
  }

  private val TabsController.stacks get() = tabs.value.map { it.backStack.toList() }

  private class FakeStore(var saved: SavedTabs? = null) : TabStateStore {
    var writes = 0

    override fun load() = saved

    override fun save(tabs: SavedTabs) {
      saved = tabs
      writes++
    }

    override fun clear() {
      saved = null
    }
  }

  @Test
  fun tabsTheirBackStacksAndTheActiveTabSurviveARestart() = runTest {
    val store = FakeStore()
    val before = TabsController(store, backgroundScope)
    runCurrent()
    val post = Route.Post("p1", "m1", listOf("a"))
    before.open(listOf(post), background = true)
    before.open(Route.Grid(listOf("b")), background = false)
    before.activeTab.backStack.add(Route.Grid(listOf("b", "c")))
    Snapshot.sendApplyNotifications()
    advanceTimeBy(TabsController.SAVE_DELAY + 100.milliseconds)

    val after = TabsController(store)

    assertEquals(before.stacks, after.stacks)
    assertEquals(before.tabs.value.map { it.id }, after.tabs.value.map { it.id })
    assertEquals(before.active.value, after.active.value)
  }

  @Test
  fun aBurstOfChangesIsSavedOnce() = runTest {
    val store = FakeStore()
    val controller = TabsController(store, backgroundScope)
    runCurrent()

    repeat(5) {
      controller.open(Route.Grid(listOf("t$it")), background = false)
      controller.activeTab.backStack.add(Route.Grid(listOf("t$it", "more")))
      Snapshot.sendApplyNotifications()
      advanceTimeBy(200.milliseconds)
    }
    assertEquals(0, store.writes)
    advanceTimeBy(TabsController.SAVE_DELAY)

    assertEquals(1, store.writes)
    assertEquals(6, store.saved?.tabs?.size)
    assertEquals(5, store.saved?.activeIndex)
  }

  @Test
  fun nothingIsSavedUntilSomethingChanges() = runTest {
    val store = FakeStore()
    TabsController(store, backgroundScope)

    advanceTimeBy(TabsController.SAVE_DELAY * 3)

    assertEquals(0, store.writes)
  }

  /**
   * Created first inside a composition's snapshot (as Android did, from App()), the restored back stacks read as empty
   * to the save, which wrote every tab with no routes. So Koin creates the controller at start, outside any snapshot.
   */
  @Test
  fun koinCreatesTheControllerAtStartSoRestoredTabsAreSavedWithTheirRoutes() = runTest {
    val stacks = listOf(listOf(Route.Grid(emptyList())), listOf(Route.Grid(emptyList()), Route.Grid(listOf("a"))))
    val store = FakeStore(SavedTabs(1, stacks.mapIndexed { i, routes -> SavedTab("t$i", routes) }))
    val app = koinApplication {
      modules(module { single<TabStateStore> { store }; single<CoroutineScope> { backgroundScope } }, shellModule)
    }
    try {
      val controller = app.koin.get<TabsController>()
      assertEquals(stacks, controller.stacks)
      runCurrent()
      controller.activate(TabId("t0"))
      Snapshot.sendApplyNotifications()
      advanceTimeBy(TabsController.SAVE_DELAY + 100.milliseconds)

      assertEquals(1, store.writes)
      assertEquals(stacks, store.saved?.tabs?.map { it.routes })
    } finally {
      app.close()
    }
  }

  @Test
  fun theControllerIsCreatedDuringKoinStart() {
    var loads = 0
    val store = object : TabStateStore {
      override fun load(): SavedTabs? = null.also { loads++ }

      override fun save(tabs: SavedTabs) = Unit

      override fun clear() = Unit
    }
    val app = koinApplication {
      modules(module { single<TabStateStore> { store }; single<CoroutineScope> { CoroutineScope(Job()) } }, shellModule)
    }

    assertEquals(1, loads)
    app.close()
  }

  @Test
  fun aCorruptFileGivesOneBrowseTab() {
    listOf("{not json", """{"activeIndex":0,"tabs":[{"id":"x","routes":[{"type":"Gone"}]}]}""", "{}").forEach { text ->
      val file = InMemoryTextFile(text)

      val controller = TabsController(FileTabStateStore(file))

      assertEquals(listOf(listOf<Route>(Route.Grid(emptyList()))), controller.stacks, text)
    }
  }

  @Test
  fun anOutOfRangeActiveIndexFallsBackToATab() {
    val saved = SavedTabs(activeIndex = 7, tabs = listOf(SavedTab("a", listOf(Route.Grid(listOf("a"))))))

    val controller = TabsController(FakeStore(saved))

    assertEquals("A", controller.activeTab.title)
  }
}
