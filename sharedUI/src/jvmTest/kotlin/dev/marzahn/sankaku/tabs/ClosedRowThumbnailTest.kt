package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SeenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * #250: a Recently closed row shows the thumbnail its item had on the queue page: a post's preview (by its route's
 * md5) or a source's mosaic, also for items restored at launch. G only leaves out R15 and R18 entries and counts them.
 * Recently closed itself is kept for the session only; a faded source's mosaic is saved, optional, so Delete now's
 * entry has it too. The md5s are synthetic.
 */
class ClosedRowThumbnailTest {
  private var now = Instant.fromEpochSeconds(1000)
  private val clock = object : Clock {
    override fun now() = now
  }

  private val mosaicJson = """"mosaic":[{"md5":"m0","rating":"s"},{"md5":"m1","rating":"q"},{"md5":"m2","rating":"s"},{"md5":"m3","rating":"s"}],"posts":60"""

  // Saved before a relaunch: a source with its mosaic, and a G post kept from it.
  private val saved = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
    """{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}],"kind":"source","touched":1000,"addedIn":"s1",$mosaicJson},""" +
    """{"id":"p","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]},""" +
    """{"type":"dev.marzahn.sankaku.Route.Post","id":"p1","md5":"k1","originTags":["artist_a"],"label":"Kept one","rating":"s"}],""" +
    """"block":"a","kind":"post","touched":1000,"addedIn":"s1","keptAt":1000}""" +
    """]}"""

  private fun relaunched(text: String = saved) = TabsController(FileTabStateStore(InMemoryTextFile(text)), clock = clock)

  private fun post(id: String, rating: String?) = Route.Post(id, "md5$id", listOf("a"), label = "Post $id", rating = rating)

  private fun rows(tabs: TabsController, gOnly: Boolean = false) = closedRows(tabs.closed.value, gOnly) { null }

  @Test
  fun aKeptPostClosedAfterARelaunchShowsItsPreview() {
    val tabs = relaunched()

    tabs.close(tabs.tabs.value.single { it.title == "Kept one" }.id)

    val row = rows(tabs).rows.single()
    assertEquals("k1", (row.shown.top as Route.Post).md5)
    assertNull(row.shown.mosaic)
    assertFalse(row.adult)
  }

  @Test
  fun aSourceClosedAfterARelaunchShowsItsSavedMosaic() {
    val tabs = relaunched()

    tabs.close(tabs.tabs.value.single { it.top == Route.Grid(listOf("artist_a")) }.id)

    val row = rows(tabs).rows.single()
    assertEquals(listOf("m0", "m1", "m2", "m3"), row.shown.mosaic!!.thumbs.map { it.md5 })
    assertEquals(listOf(false, true, false, false), row.shown.mosaic!!.thumbs.map { it.isAdult })
  }

  @Test
  fun aReopenedSourceKeepsItsMosaicAndSavesItAsBefore() {
    val file = InMemoryTextFile(saved)
    val store = FileTabStateStore(file)
    val tabs = TabsController(store, clock = clock)
    tabs.close(tabs.tabs.value.single { it.top == Route.Grid(listOf("artist_a")) }.id)

    tabs.reopenClosed()
    store.save(tabs.saved())

    assertEquals(60, tabs.tabs.value.single { it.top == Route.Grid(listOf("artist_a")) }.mosaic!!.posts)
    assertTrue(file.read()!!.contains(mosaicJson), file.read())
  }

  @Test
  fun gOnlyLeavesOutR15AndR18EntriesAndCountsThem() {
    val tabs = TabsController(clock = clock)
    tabs.open(post("g", "s"), background = true)
    tabs.open(post("r18", "e"), background = true)
    tabs.open(post("unrated", null), background = true)
    tabs.tabs.value.drop(1).forEach { tabs.close(it.id) }

    val (all, hiddenWithout) = rows(tabs)
    assertEquals(listOf("Post unrated", "Post r18", "Post g"), all.map { it.closed.title })
    assertEquals(listOf(true, true, false), all.map { it.adult })
    assertEquals(0, hiddenWithout)

    val (listed, hidden) = rows(tabs, gOnly = true)
    assertEquals(listOf("Post g"), listed.map { it.closed.title })
    assertEquals(2, hidden)
  }

  @Test
  fun aRatingKnownOnlyFromTheLoadedPostCounts() {
    val tabs = TabsController(clock = clock)
    tabs.open(post("p", rating = null), background = true)
    tabs.close(tabs.tabs.value.last().id)

    val (listed, hidden) = closedRows(tabs.closed.value, gOnly = true) { TabPost(favourited = false, tags = emptyList(), rating = "s") }

    assertEquals(1, listed.size)
    assertFalse(listed.single().adult)
    assertEquals(0, hidden)
  }

  @Test
  fun aClearedEntryShowsItsFirstPostAndUnderGOnlyItsFirstGPost() {
    val seen = SeenStore(InMemoryTextFile(), clock)
    val tabs = TabsController(clock = clock)
    tabs.open(Route.Grid(listOf("a")), background = false)
    tabs.openInBlock(listOf(Route.Grid(listOf("a")), post("p1", "e")))
    tabs.openInBlock(listOf(Route.Grid(listOf("a")), post("p2", "s")))
    now += 1.minutes
    seen.record("p1", "md5p1")
    seen.record("p2", "md5p2")
    tabs.clearViewed(seen)!!

    assertEquals("md5p1", (rows(tabs).rows.single().shown.top as Route.Post).md5)
    val (listed, hidden) = rows(tabs, gOnly = true)
    assertEquals("md5p2", (listed.single().shown.top as Route.Post).md5)
    assertEquals(0, hidden)
  }

  @Test
  fun aBatchOfOnlyR18ItemsIsLeftOutUnderGOnly() {
    val tabs = TabsController(clock = clock)
    tabs.open(post("x", "e"), background = true)
    tabs.open(post("y", "q"), background = true)
    tabs.closeAll(tabs.tabs.value.drop(1).map { it.id })

    val (listed, hidden) = rows(tabs, gOnly = true)

    assertTrue(listed.isEmpty())
    assertEquals(1, hidden)
  }

  @Test
  fun aFadedSourceSavesItsMosaicAndAFileWithoutOneLoadsUnchanged() {
    val faded = SavedFaded(
      at = 1000,
      tabs = listOf(
        SavedTab(
          id = "a", routes = listOf(Route.Grid(listOf("artist_a"))), kind = QueueKind.Source, touched = 1000, addedIn = "s1",
          mosaic = listOf(QueueThumb("m0", "s")), posts = 60,
        ),
      ),
    )
    val source = FadedSource.of(faded)!!
    assertEquals(listOf("m0"), source.members.single().mosaic!!.thumbs.map { it.md5 })
    assertEquals(faded.tabs.single().mosaic, source.saved().tabs.single().mosaic)
    assertEquals(60, source.saved().tabs.single().posts)

    val without = faded.copy(tabs = listOf(faded.tabs.single().copy(mosaic = null, posts = null)))
    val plain = FadedSource.of(without)!!
    assertNull(plain.members.single().mosaic)
    assertNull(plain.saved().tabs.single().mosaic)
    assertNull(plain.saved().tabs.single().posts)
  }

  @Test
  fun aFileWithAFadedSourceAndNoMosaicLoadsAndSavesBackUnchanged() {
    val text = """{"version":2,"session":"s1","activeIndex":0,"tabs":[""" +
      """{"id":"b","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":[]}],"kind":"source","touched":1000,"addedIn":"s1"}],""" +
      """"faded":[{"at":1000,"tabs":[{"id":"a","routes":[{"type":"dev.marzahn.sankaku.Route.Grid","tags":["artist_a"]}],""" +
      """"kind":"source","touched":900,"addedIn":"s1"}]}]}"""
    val file = InMemoryTextFile(text)
    val store = FileTabStateStore(file)
    val before = store.load()!!
    val tabs = TabsController(store, clock = clock)

    assertEquals(before, tabs.saved())
    store.save(tabs.saved())
    assertFalse(file.read()!!.contains("mosaic"))
    assertEquals(before, store.load())
  }
}
