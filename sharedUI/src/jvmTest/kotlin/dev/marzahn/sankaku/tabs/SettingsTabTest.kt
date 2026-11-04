package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.QueueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #205: on desktop Settings is a tab of its own (`Route.Settings`), one at most, that Back closes back to where it was
 * opened from. It's never saved in `tabs.json`.
 */
class SettingsTabTest {
  private val file = InMemoryTextFile(null)
  private val store = FileTabStateStore(file)
  private val tabs = TabsController(store)
  private val navigator = TabsNavigator(tabs)
  private val artist = Route.Grid(listOf("artist_x"))
  private val tag = Route.Grid(listOf("tag_t"))
  private val browse = Route.Grid(emptyList())

  private fun post(id: String) = Route.Post(id, "m$id", artist.tags, label = id)

  private fun tops() = tabs.tabs.value.map { it.top }

  // [Browse, artist_x, k1], artist_x shown: a source with a kept post in its block.
  private fun onASourceWithAKeptPost() {
    tabs.open(artist, background = false)
    navigator.openInNewTab(post("k1"))
    assertEquals(listOf(browse, artist, post("k1")), tops())
  }

  @Test
  fun aClickOpensItAfterTheBlockAndBackClosesItBackThere() {
    onASourceWithAKeptPost()
    val source = tabs.activeTab

    navigator.goTo(Route.Settings)

    // After the source's block, not inside it, and alone in its tab.
    assertEquals(listOf(browse, artist, post("k1"), Route.Settings), tops())
    assertEquals(listOf<Route>(Route.Settings), tabs.activeTab.backStack.toList())
    assertNull(tabs.activeTab.block)
    assertEquals(QueueKind.Other, tabs.activeTab.kind)
    assertEquals(SETTINGS_TITLE, tabs.activeTab.title)
    // The kept post is still the source's.
    assertSame(source, tabs.sourceOf(tabs.tabs.value[2]))

    navigator.goBack()
    assertEquals(listOf(browse, artist, post("k1")), tops())
    assertSame(source, tabs.activeTab)
    // Z brings it back.
    assertEquals(Route.Settings, tabs.closed.value.last().top)
  }

  @Test
  fun openingItAgainShowsTheOneOpenAndBackReturnsToWhereItWasOpenedFromThisTime() {
    onASourceWithAKeptPost()
    navigator.goTo(Route.Settings)
    val settings = tabs.activeTab
    tabs.activate(tabs.tabs.value.first().id)

    navigator.goTo(Route.Settings)

    assertSame(settings, tabs.activeTab)
    assertEquals(1, tabs.tabs.value.count { it.top is Route.Settings })
    navigator.goBack()
    assertEquals(browse, tabs.activeTab.top)
  }

  @Test
  fun aMiddleClickOpensItLaterInTheBackgroundOrSaysWhereItIs() {
    onASourceWithAKeptPost()
    val source = tabs.activeTab

    assertNull(navigator.openInNewTab(Route.Settings))

    assertSame(source, tabs.activeTab)
    // "Later": at the end of this session's tabs.
    assertEquals(listOf(browse, artist, post("k1"), Route.Settings), tops())
    assertNull(tabs.tabs.value.last().openedFrom)
    // Open already: nothing new, and its position.
    assertEquals(4, navigator.openInNewTab(Route.Settings))
    assertEquals(4, tabs.tabs.value.size)
    assertSame(source, tabs.activeTab)
  }

  @Test
  fun backOnOneOpenedInTheBackgroundShowsTheTabBeforeIt() {
    navigator.openInNewTab(Route.Settings)
    tabs.open(artist, background = true)
    tabs.activate(tabs.settingsTab()!!.id)

    navigator.goBack()

    assertEquals(listOf(browse, artist), tops())
    assertEquals(browse, tabs.activeTab.top)
  }

  @Test
  fun aSearchOrASidebarGridFromItOpensInFrontAndSettingsStaysAlone() {
    navigator.goTo(Route.Settings)
    val settings = tabs.activeTab

    navigator.goTo(tag)

    assertEquals(listOf<Route>(Route.Settings), settings.backStack.toList())
    assertEquals(listOf(browse, tag, Route.Settings), tops())
    // Back on the grid returns to Settings, as on any grid opened from an item (#349).
    navigator.goBack()
    assertSame(settings, tabs.activeTab)
  }

  @Test
  fun itIsNeverSavedAndTheTabItWasOpenedFromIsSavedAsActive() {
    onASourceWithAKeptPost()
    navigator.goTo(Route.Settings)

    val saved = tabs.saved()

    assertEquals(listOf(browse, artist, post("k1")), saved.tabs.map { it.routes.last() })
    assertEquals(1, saved.activeIndex)
    store.save(saved)
    assertFalse(file.read()!!.contains("Settings"))
    val relaunched = TabsController(FileTabStateStore(file))
    assertEquals(listOf(browse, artist, post("k1")), relaunched.tabs.value.map { it.top })
    assertEquals(artist, relaunched.activeTab.top)
  }

  @Test
  fun withItsOpenerClosedTheTabBeforeItIsSavedAsActive() {
    navigator.goTo(Route.Settings)
    tabs.open(artist, background = true)
    tabs.open(tag, background = true)
    // [Browse, Settings, artist_x, tag_t]: opened from Browse, which then closes.
    assertEquals(listOf(browse, Route.Settings, artist, tag), tops())
    tabs.close(tabs.tabs.value.first().id)
    tabs.activate(tabs.settingsTab()!!.id)

    val saved = tabs.saved()

    assertEquals(listOf(artist, tag), saved.tabs.map { it.routes.last() })
    // Nothing before it is left: the first saved tab.
    assertEquals(0, saved.activeIndex)
  }

  @Test
  fun onlySettingsOpenSavesNoTabsAndALaunchStartsOnBrowse() {
    navigator.goTo(Route.Settings)
    tabs.close(tabs.tabs.value.first().id)
    assertEquals(listOf<Route?>(Route.Settings), tops())

    store.save(tabs.saved())

    val relaunched = TabsController(FileTabStateStore(file))
    assertEquals(listOf<Route?>(browse), relaunched.tabs.value.map { it.top })
  }

  @Test
  fun reopeningAClosedOneWhileAnotherIsOpenShowsTheOpenOne() {
    navigator.goTo(Route.Settings)
    navigator.goBack()
    navigator.goTo(Route.Settings)
    val open = tabs.activeTab
    tabs.activate(tabs.tabs.value.first().id)

    tabs.reopenClosed()

    assertEquals(1, tabs.tabs.value.count { it.top is Route.Settings })
    assertSame(open, tabs.activeTab)
    assertTrue(tabs.closed.value.none { it.top is Route.Settings })
  }

  @Test
  fun aTabWithSettingsAnywhereInItsBackStackIsNotSaved() {
    navigator.goTo(Route.Settings)
    // Nothing pushes onto it today; were something to, the file still mustn't hold the route.
    tabs.activeTab.backStack.add(tag)

    val saved = tabs.saved()

    assertEquals(listOf(listOf<Route>(browse)), saved.tabs.map { it.routes })
    assertEquals(0, saved.activeIndex)
  }

  @Test
  fun anExistingFileLoadsAndSavesBackUnchanged() {
    val saved = """{"version":2,"session":"s1","activeIndex":1,"tabs":[""" +
      """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["tag_t"]}],"kind":"source","touched":1000,"addedIn":"s1"},""" +
      """{"id":"b","routes":[{"type":"dev.marzahn.sankaku.Route.Post","id":"p","md5":"mp","originTags":["tag_t"]}],"block":"a","kind":"post","touched":1000,"addedIn":"s1","keptAt":1000}""" +
      """]}"""
    val existing = InMemoryTextFile(saved)
    val existingStore = FileTabStateStore(existing)
    val before = existingStore.load()
    assertNotNull(before)
    val clock = object : Clock {
      override fun now() = Instant.fromEpochSeconds(1000)
    }
    val controller = TabsController(existingStore, clock = clock)

    assertEquals(before, controller.saved())
    // Settings opened and closed again leaves the file as it was.
    TabsNavigator(controller).goTo(Route.Settings)
    assertEquals(before, controller.saved())
    existingStore.save(controller.saved())
    assertEquals(before, existingStore.load())
    assertFalse(existing.read()!!.contains("Settings"))
  }
}
