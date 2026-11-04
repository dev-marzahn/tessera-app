package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #132: the tab list's bulk closes are one change with one Undo, which puts every tab back where it stood. */
class TabsControllerBulkCloseTest {
  private val tabs = TabsController()

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun post(id: String) = Route.Post(id, md5 = null, originTags = emptyList(), label = id)

  /** Opens a post tab for each of [ids], in order, after the Browse tab. */
  private fun openPosts(vararg ids: String) = ids.forEach { tabs.open(post(it), background = true) }

  private fun activate(title: String) = tabs.activate(tabs.tabs.value.first { it.title == title }.id)

  @Test
  fun closingTabsToTheRightIsOneEntryAndOneUndoPutsThemBack() {
    openPosts("a", "b", "c", "d")
    activate("b")
    val before = titles()

    val batch = tabs.closeAll(tabs.tabsToTheRight())!!

    assertEquals(listOf("Browse", "a", "b"), titles())
    assertEquals(listOf("2 tabs"), tabs.closed.value.map { it.title })
    assertEquals(2, batch.size)
    tabs.reopen(batch)
    assertEquals(before, titles())
    assertEquals("b", tabs.activeTab.title)
    assertTrue(tabs.closed.value.isEmpty())
  }

  @Test
  fun forgettingTheClosedTabsLeavesNothingToReopen() {
    openPosts("a", "b")
    tabs.closeAll(tabs.tabs.value.map { it.id })
    assertEquals(listOf("Browse"), titles())
    assertEquals(1, tabs.closed.value.size)

    tabs.forgetClosed()
    tabs.reopenClosed()

    assertTrue(tabs.closed.value.isEmpty())
    assertEquals(listOf("Browse"), titles())
  }

  @Test
  fun closeDuplicatesKeepsTheFirstOfEachPostAndUndoRestoresTheScatteredCopies() {
    openPosts("a", "b", "a", "c", "b", "a")
    val before = titles()

    val batch = tabs.closeAll(tabs.duplicateTabs())!!

    assertEquals(listOf("Browse", "a", "b", "c"), titles())
    assertEquals(3, batch.size)
    tabs.reopenClosed()
    assertEquals(before, titles())
  }

  @Test
  fun closeDuplicatesKeepsTheActiveCopy() {
    openPosts("a", "b", "a")
    val activeCopy = tabs.tabs.value[3]
    tabs.activate(activeCopy.id)

    tabs.closeAll(tabs.duplicateTabs())

    assertEquals(listOf("Browse", "b", "a"), titles())
    assertEquals(activeCopy.id, tabs.active.value)
  }

  @Test
  fun closeFavouritedClosesOnlyPostTabsWhosePostIsFavourited() {
    openPosts("a", "b", "c")
    tabs.open(Route.Grid(listOf("fav")), background = true)

    val ids = tabs.favouritedTabs { it == "a" || it == "c" || it == "fav" }
    tabs.closeAll(ids)

    assertEquals(listOf("Browse", "b", "Fav"), titles())
  }

  @Test
  fun closingTheActiveTabInABatchBringsTheNextOpenOneForwardAndUndoMakesItActiveAgain() {
    openPosts("a", "b", "c", "d")
    activate("b")
    val ids = tabs.tabs.value.filter { it.title in setOf("b", "c") }.map { it.id }

    tabs.closeAll(ids)
    assertEquals("d", tabs.activeTab.title)
    tabs.reopenClosed()

    assertEquals(listOf("Browse", "a", "b", "c", "d"), titles())
    assertEquals("b", tabs.activeTab.title)
  }

  @Test
  fun closingTheActiveLastTabInABatchBringsTheOneToItsLeftForward() {
    openPosts("a", "b", "c")
    activate("c")

    tabs.closeAll(tabs.tabs.value.filter { it.title in setOf("b", "c") }.map { it.id })

    assertEquals("a", tabs.activeTab.title)
  }

  @Test
  fun closeAllButCurrentAt400TabsUndoesInOneStep() {
    repeat(399) { openPosts("p$it") }
    activate("p89")
    val before = titles()

    tabs.closeAll(tabs.otherTabs())
    assertEquals(listOf("p89"), titles())
    assertEquals(1, tabs.closed.value.size)
    tabs.reopenClosed()

    assertEquals(before, titles())
    assertEquals(400, tabs.tabs.value.size)
    assertEquals("p89", tabs.activeTab.title)
  }

  @Test
  fun aSingleTabOrNoneClosesAsCloseDoes() {
    openPosts("a", "b")
    activate("a")

    assertNull(tabs.closeAll(emptyList()))
    assertEquals("b", tabs.closeAll(tabs.tabsToTheRight())?.title)
    assertEquals(listOf("Browse", "a"), titles())
  }

  @Test
  fun aTabClosedAfterABatchStillReopensInPlaceOnceTheBatchIsBack() {
    openPosts("a", "b", "c", "d", "e")
    activate("e")
    val before = titles()
    val batch = tabs.closeAll(tabs.tabs.value.filter { it.title in setOf("a", "c") }.map { it.id })!!
    // Browse, b, d, e: "d" stands at 2.
    tabs.close(tabs.tabs.value.first { it.title == "d" }.id)

    tabs.reopen(batch)
    assertEquals(listOf("Browse", "a", "b", "c", "e"), titles())
    tabs.reopenClosed()

    assertEquals(before, titles())
  }

  @Test
  fun closingEveryTabLeavesAnEmptyGridAndUndoBringsThemBack() {
    openPosts("a", "b")

    tabs.closeAll(tabs.tabs.value.map { it.id })
    assertEquals(listOf("Browse"), titles())
    tabs.reopenClosed()

    assertEquals(listOf("Browse", "a", "b", "Browse"), titles())
  }
}
