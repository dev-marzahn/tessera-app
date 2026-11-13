package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #509: a search changed on a touched grid opens as a new item in front of it (#161). That item keeps its own title,
 * and its queue row says where it came from, "Grid · from Landscape", however much the search changed. It keeps
 * that after its parent closes, after Undo and after a relaunch. A search on an untouched grid still replaces it.
 */
class RefinedFromTest {
  private val file = InMemoryTextFile(null)
  private val store = FileTabStateStore(file)
  private val tabs = TabsController(store)
  private val navigator = TabsNavigator(tabs)
  private val landscape = Route.Grid(listOf("landscape"))
  private val landscapeSky = Route.Grid(listOf("landscape", "sky"))

  private val titles get() = tabs.tabs.value.map { it.title }

  private fun subtitle(tab: TabState) = queueSubtitle(tab, current = false, seen = false, rating = null)

  private fun onTouchedLandscape() {
    navigator.goTo(landscape)
    tabs.activeTab.markTouched(landscape)
  }

  @Test
  fun aSearchChangedOnATouchedGridKeepsItsTitleAndSaysWhereItCameFrom() {
    onTouchedLandscape()

    navigator.goTo(landscapeSky)

    assertEquals(listOf("Landscape Sky", "Landscape"), titles)
    assertEquals("Landscape", tabs.activeTab.refinedFrom)
    assertEquals("Grid · from Landscape", subtitle(tabs.activeTab))
    // The parent says nothing.
    assertEquals("Grid", subtitle(tabs.tabs.value[1]))
  }

  @Test
  fun anyChangeReadsTheSame() {
    onTouchedLandscape()

    // Several tags added, an exclusion, a new order: still from Landscape, under the search's own title.
    navigator.goTo(Route.Grid(listOf("Landscape", "-cloud", "tree", "river", "order:popularity")))

    assertEquals("Grid · popular · from Landscape", subtitle(tabs.activeTab))
  }

  @Test
  fun aSearchThatKeepsNoneOfTheGridsTagsSaysNothing() {
    onTouchedLandscape()

    // A sidebar entry (Popular), Child posts, another tag altogether: not a change of Landscape.
    navigator.goTo(Route.Grid(listOf("order:popularity")))
    assertNull(tabs.activeTab.refinedFrom)
    navigator.goTo(landscape)
    tabs.activeTab.markTouched(landscape)
    navigator.goTo(Route.Grid(listOf("parent:123")))
    assertNull(tabs.activeTab.refinedFrom)
    navigator.goTo(landscape)
    tabs.activeTab.markTouched(landscape)
    navigator.goTo(Route.Grid(listOf("sky")))
    assertNull(tabs.activeTab.refinedFrom)
  }

  @Test
  fun aSearchOnAnUntouchedGridReplacesItAndSaysNothing() {
    navigator.goTo(landscape)

    navigator.goTo(landscapeSky)

    assertEquals(listOf("Landscape Sky"), titles)
    assertNull(tabs.activeTab.refinedFrom)
  }

  @Test
  fun aSearchFromBrowseOrAPostSaysNothing() {
    tabs.activeTab.markTouched(Route.Grid(emptyList()))
    navigator.goTo(landscape)
    assertNull(tabs.activeTab.refinedFrom)

    tabs.openLone(Route.Post("p", "mp", originTags = listOf("landscape")))
    navigator.goTo(landscapeSky)
    assertNull(tabs.activeTab.refinedFrom)
  }

  @Test
  fun aSearchRunInPlaceOverItSaysNothingAndBackBringsItBack() {
    onTouchedLandscape()
    navigator.goTo(landscapeSky)
    val refined = tabs.activeTab

    // It's untouched, so this search replaces it in place.
    navigator.goTo(Route.Grid(listOf("landscape", "night")))
    assertNull(refined.refinedFrom)

    navigator.goBack()
    assertEquals("Landscape", refined.refinedFrom)
  }

  @Test
  fun itStillSaysSoWhenItsParentCloses() {
    onTouchedLandscape()
    navigator.goTo(landscapeSky)

    tabs.close(tabs.tabs.value.single { it.title == "Landscape" }.id)

    assertEquals("Landscape", tabs.activeTab.refinedFrom)
  }

  @Test
  fun closedAndReopenedItStillSaysSo() {
    onTouchedLandscape()
    navigator.goTo(landscapeSky)

    tabs.reopen(tabs.close(tabs.activeTab.id)!!)

    assertEquals("Landscape", tabs.tabs.value.single { it.title == "Landscape Sky" }.refinedFrom)
  }

  @Test
  fun itStillSaysSoAfterARelaunch() {
    onTouchedLandscape()
    navigator.goTo(landscapeSky)
    store.save(tabs.saved())
    assertTrue(file.read()!!.contains(""""refines":["landscape"]"""), file.read())

    val relaunched = TabsController(FileTabStateStore(file))

    assertEquals("Landscape", relaunched.tabs.value.single { it.title == "Landscape Sky" }.refinedFrom)
  }

  @Test
  fun aTabOpenedFromNoGridSavesNoRefines() {
    navigator.goTo(landscape)
    store.save(tabs.saved())

    assertFalse(file.read()!!.contains("refines"))
  }
}
