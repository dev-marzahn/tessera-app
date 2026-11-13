package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * #232: a grid's queue-page mosaic and loaded count are saved with its tab in `tabs.json`, as optional fields, so a
 * source restored at launch shows them as before. The md5s are synthetic.
 */
class QueueMosaicTest {
  private val file = InMemoryTextFile(null)
  private val store = FileTabStateStore(file)
  private val tabs = TabsController(store)
  private val a = Route.Grid(listOf("artist_a"))

  private fun posts(count: Int, rating: (Int) -> String = { "s" }) =
    (0 until count).map { PostDto(id = "p$it", md5 = "m$it", rating = rating(it), status = "active") }

  private fun openA(): TabState {
    tabs.open(a, background = false)
    return tabs.activeTab
  }

  @Test
  fun aGridsMosaicIsFourThumbnailsFromWhereItIsScrolledWithTheirRatings() {
    val mosaic = QueueMosaic.of(posts(60) { if (it == 11) "q" else if (it == 12) "" else "s" }, firstShown = 10)!!

    assertEquals(listOf("m10", "m11", "m12", "m13"), mosaic.thumbs.map { it.md5 })
    assertEquals(listOf("s", "q", null, "s"), mosaic.thumbs.map { it.rating })
    assertEquals(listOf(true, false, false, true), mosaic.thumbs.map { it.isG })
    assertEquals(60, mosaic.posts)
    assertEquals(listOf("m56", "m57", "m58", "m59"), QueueMosaic.of(posts(60), firstShown = 59)!!.thumbs.map { it.md5 })
    assertNull(QueueMosaic.of(emptyList(), firstShown = 0))
  }

  @Test
  fun theMosaicAndCountAreSavedAndRestoredWithTheTab() {
    openA().reportMosaic(a, QueueMosaic.of(posts(60) { if (it == 1) "e" else "s" }, firstShown = 0)!!)
    store.save(tabs.saved())

    val text = file.read()!!
    assertTrue(
      text.contains(""""mosaic":[{"md5":"m0","rating":"s"},{"md5":"m1","rating":"e"},{"md5":"m2","rating":"s"},{"md5":"m3","rating":"s"}],"posts":60"""),
      text,
    )
    val restored = TabsController(store).tabs.value.single { it.top == a }
    assertEquals(listOf("m0", "m1", "m2", "m3"), restored.mosaic!!.thumbs.map { it.md5 })
    assertEquals(listOf(true, false, true, true), restored.mosaic!!.thumbs.map { it.isG })
    assertEquals("Grid · 60 posts", queueSubtitle(restored, current = false, seen = false, rating = null))
  }

  @Test
  fun aRestoredSourceWithAPlaceSaysPlaceKept() {
    openA().apply {
      reportPlace(a, GridPlace("p30", time = null))
      reportMosaic(a, QueueMosaic.of(posts(60), firstShown = 30)!!)
    }
    store.save(tabs.saved())

    val restored = TabsController(store).tabs.value.single { it.top == a }

    assertEquals("Grid · place kept", queueSubtitle(restored, current = false, seen = false, rating = null))
    assertEquals(listOf("m30", "m31", "m32", "m33"), restored.mosaic!!.thumbs.map { it.md5 })
  }

  @Test
  fun theMosaicBelongsToTheGridItWasReportedFor() {
    val tab = openA()
    tab.reportMosaic(a, QueueMosaic.of(posts(4), firstShown = 0)!!)

    tab.backStack.add(Route.Grid(listOf("artist_b")))

    assertNull(tab.mosaic)
    assertNull(tabs.saved().tabs.single { it.id == tab.id.value }.mosaic)
    tab.backStack.removeLast()
    assertEquals(4, tab.mosaic!!.posts)
  }

  @Test
  fun aGridThatHasntLoadedSavesNoMosaic() {
    openA()
    store.save(tabs.saved())

    assertFalse(file.read()!!.contains("mosaic"))
    assertFalse(file.read()!!.contains("posts"))
  }

  @Test
  fun aFileWithoutMosaicsLoadsAndSavesBackUnchanged() {
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
    assertTrue(controller.tabs.value.all { it.mosaic == null })
    existingStore.save(controller.saved())
    assertFalse(existing.read()!!.contains("mosaic"))
    assertEquals(before, existingStore.load())
  }

  @Test
  fun aV1FileMigratesWithNoMosaics() {
    val v1 = """{"activeIndex":0,"tabs":[{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}]}]}"""
    val v1Store = FileTabStateStore(InMemoryTextFile(v1))

    val migrated = TabsController(v1Store).saved()

    assertEquals(2, migrated.version)
    assertNull(migrated.tabs.single().mosaic)
    assertNull(migrated.tabs.single().posts)
  }

  @Test
  fun clearingTheStoreForgetsTheMosaics() {
    openA().reportMosaic(a, QueueMosaic.of(posts(4), firstShown = 0)!!)
    store.save(tabs.saved())

    store.clear()

    assertTrue(TabsController(store).tabs.value.all { it.mosaic == null })
  }
}
