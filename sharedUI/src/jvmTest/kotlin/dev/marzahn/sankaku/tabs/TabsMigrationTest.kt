package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SessionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `tabs.json` v1 → v2 (#174): the owner's session of about 170 tabs must come through whole. The fixture is synthetic:
 * made-up tags and ids, never a real file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabsMigrationTest {
  private val now = Instant.parse("2026-11-01T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }

  private fun route(route: Route) = Json.encodeToString(Route.serializer(), route)

  /**
   * A v1 file as the app wrote it before #174, with [count] tabs: grids on searches (some with a place, every place
   * shape), feeds, and posts opened into a grid's block, some kept over their grid (two routes).
   */
  private fun v1File(count: Int, active: Int): String {
    val tabs = (0 until count).map { i ->
      val id = "t$i"
      when (i % 5) {
        0 -> """{"id":"$id","routes":[${route(Route.Grid(listOf("tag_$i")))}],"place":{"post":"p$i","cursor":"c$i","page":${i % 7 + 2},"time":${1_700_000_000 + i}}}"""
        1 -> """{"id":"$id","routes":[${route(Route.Grid(listOf("tag_${i - 1}"), at = "p$i"))},${route(Route.Post("p$i", "m$i", listOf("tag_${i - 1}"), "Post $i"))}],"block":"t${i - 1}"}"""
        2 -> """{"id":"$id","routes":[${route(Route.Post("q$i", null, listOf("tag_${i - 2}")))}],"block":"t${i - 2}"}"""
        3 -> """{"id":"$id","routes":[${route(Route.Grid(listOf("feed:following")))}]}"""
        else -> """{"id":"$id","routes":[${route(Route.Grid(emptyList()))},${route(Route.Grid(listOf("order:popularity", "tag_$i")))}],"place":{"post":"p$i"}}"""
      }
    }
    return """{"activeIndex":$active,"tabs":[${tabs.joinToString(",")}]}"""
  }

  private val Route.kindName get() = queueKind(this)

  @Test
  fun a170TabV1FileMigratesWholeAndWritesItsBackup() {
    val v1 = v1File(170, active = 123)
    val file = InMemoryTextFile(v1)
    val backup = InMemoryTextFile()
    val store = FileTabStateStore(file, backup, clock = clock)
    val before = Json.decodeFromString(SavedTabs.serializer(), v1)

    val controller = TabsController(store, clock = clock)
    val migrated = controller.saved()

    // Order, active tab, routes, blocks and places, exactly as in the v1 file, except that a post kept over a copy of
    // its grid is a lone post from #181. The feeds stay where they stood (#327: nothing reorders at load).
    val queued = before.tabs
    assertEquals(170, controller.tabs.value.size)
    assertEquals(queued.indexOfFirst { it.id == before.tabs[before.activeIndex].id }, migrated.activeIndex)
    assertEquals(controller.tabs.value[migrated.activeIndex].id, controller.active.value)
    assertEquals(queued.map { it.id }, migrated.tabs.map { it.id })
    assertEquals(
      queued.map { tab -> tab.routes.takeIf { it.last() !is Route.Post } ?: listOf(tab.routes.last()) },
      migrated.tabs.map { it.routes },
    )
    assertEquals(queued.map { it.block }, migrated.tabs.map { it.block })
    assertEquals(queued.map { it.place }, migrated.tabs.map { it.place })
    assertTrue(migrated.tabs.count { it.block != null } > 60)
    assertTrue(migrated.tabs.count { it.place?.cursor != null } > 30)
    // The queue's facts: kind from the top route, touched now, one earlier session, posts kept now.
    assertEquals(queued.map { it.routes.last().kindName }, migrated.tabs.map { it.kind })
    assertEquals(setOf(QueueKind.Source, QueueKind.KeptPost, QueueKind.Feed), migrated.tabs.mapNotNull { it.kind }.toSet())
    assertTrue(migrated.tabs.all { it.touched == now.epochSeconds })
    assertTrue(migrated.tabs.all { it.addedIn == SessionId.EARLIER.value })
    assertEquals(migrated.tabs.map { if (it.kind == QueueKind.KeptPost) now.epochSeconds else null }, migrated.tabs.map { it.keptAt })
    assertEquals(SavedTabs.VERSION, migrated.version)
    // Nothing is written until the first save, and then the v1 text goes to the backup first.
    assertEquals(v1, file.read())
    assertNull(backup.read())

    store.save(migrated)

    assertEquals(v1, backup.read())
    assertNotEquals(v1, file.read())
    // It loads again as v2, unchanged, and the backup isn't written again.
    val reloaded = FileTabStateStore(file, backup, clock = clock).load()
    assertEquals(migrated, reloaded)
    val again = TabsController(FakeStore(reloaded), clock = clock).saved()
    assertEquals(migrated.copy(session = again.session), again)
    store.save(migrated)
    assertEquals(v1, backup.read())
  }

  @Test
  fun aV2FileLeavesOutWhatIsNull() {
    val file = InMemoryTextFile(v1File(5, active = 0))
    val store = FileTabStateStore(file, clock = clock)
    store.save(TabsController(store, clock = clock).saved())

    val root = Json.parseToJsonElement(file.read()!!).jsonObject
    val tabs = root.getValue("tabs").jsonArray.map { it.jsonObject }

    assertEquals("2", root["version"].toString())
    assertTrue("session" in root)
    // A grid with no block keeps no block or kept-at; a feed has no place. The feed (t3) stays where it stood (#327).
    assertEquals(setOf("id", "routes", "place", "kind", "touched", "addedIn"), tabs[0].keys)
    assertEquals(setOf("id", "routes", "kind", "touched", "addedIn"), tabs[3].keys)
    assertEquals(setOf("id", "routes", "block", "kind", "touched", "addedIn", "keptAt"), tabs[2].keys)
  }

  @Test
  fun anUnreadableV2FileIsMovedAsideAndTheAppStartsWithOneBrowseTab() {
    val text = """{"version":2,"activeIndex":0,"tabs":[{"id":"x","routes":[{"type":"Gone"}]}]}"""
    val file = InMemoryTextFile(text)
    val broken = InMemoryTextFile()

    val controller = TabsController(FileTabStateStore(file, broken = broken, clock = clock), clock = clock)

    assertEquals(listOf(listOf<Route>(Route.Grid(emptyList()))), controller.tabs.value.map { it.backStack.toList() })
    assertEquals(text, broken.read())
  }

  @Test
  fun aBrokenFileThatCannotBeMovedAsideIsNeverOverwritten() {
    val text = "{not json"
    val file = InMemoryTextFile(text)
    val broken = object : TextFile {
      override fun read(): String? = null

      override fun write(text: String) = throw java.io.IOException("disk full")

      override fun delete() = Unit
    }

    val controller = TabsController(FileTabStateStore(file, broken = broken, clock = clock), clock = clock)
    // The first change's save fails too, rather than write over it.
    assertFailsWith<java.io.IOException> { FileTabStateStore(file, broken = broken).also { it.load() }.save(controller.saved()) }

    assertEquals(1, controller.tabs.value.size)
    assertEquals(text, file.read())
  }

  @Test
  fun aBackupThatFailsOnceIsMadeByTheNextSaveAndTheSaverKeepsGoing() = runTest {
    val v1 = v1File(5, active = 0)
    val file = InMemoryTextFile(v1)
    var fail = true
    val backup = object : TextFile {
      var text: String? = null

      override fun read() = text

      override fun write(text: String) {
        if (fail) throw java.io.IOException("disk full")
        this.text = text
      }

      override fun delete() = Unit
    }
    val controller = TabsController(FileTabStateStore(file, backup, clock = clock), backgroundScope, clock)
    runCurrent()

    controller.open(Route.Grid(listOf("first")), background = true)
    Snapshot.sendApplyNotifications()
    advanceTimeBy(TabsController.SAVE_DELAY + 100.milliseconds)
    // The copy failed, so the v1 file wasn't written over.
    assertEquals(v1, file.read())
    assertNull(backup.text)

    fail = false
    controller.open(Route.Grid(listOf("second")), background = true)
    Snapshot.sendApplyNotifications()
    advanceTimeBy(TabsController.SAVE_DELAY + 100.milliseconds)

    assertEquals(v1, backup.text)
    assertEquals(7, FileTabStateStore(file).load()!!.tabs.size)
  }

  @Test
  fun aCorruptOrNewerFileIsMovedAsideToo() {
    listOf("{not json", """{"version":3,"activeIndex":0,"tabs":[]}""", """{"activeIndex":"x"}""").forEach { text ->
      val broken = InMemoryTextFile()

      assertNull(FileTabStateStore(InMemoryTextFile(text), broken = broken).load(), text)
      assertEquals(text, broken.read(), text)
    }
  }

  @Test
  fun anEmptyFileIsNothingSavedAndNothingIsMoved() {
    val broken = InMemoryTextFile()

    assertNull(FileTabStateStore(InMemoryTextFile("{}"), broken = broken).load())
    assertNull(broken.read())
  }

  @Test
  fun clearingAMigratedFileStillKeepsItsBackup() {
    val v1 = v1File(3, active = 0)
    val backup = InMemoryTextFile()
    val store = FileTabStateStore(InMemoryTextFile(v1), backup, clock = clock)
    store.load()

    store.clear()

    assertEquals(v1, backup.read())
  }

  @Test
  fun clearEverythingsBackupWipeDeletesTheCopies() {
    val backup = InMemoryTextFile("v1")
    val broken = InMemoryTextFile("broken")

    FileTabStateStore(InMemoryTextFile(), backup, broken).clearBackups()

    assertNull(backup.read())
    assertNull(broken.read())
  }

  @Test
  fun aLaterTabStillGoesBeforeTheMigratedOnes() {
    val controller = TabsController(FileTabStateStore(InMemoryTextFile(v1File(10, active = 4)), clock = clock), clock = clock)

    controller.open(Route.Grid(listOf("new_search")), background = true)
    controller.open(Route.Grid(listOf("newer_search")), background = true)

    // As before #174: this session's tabs lead, in the order opened, and the earlier session follows.
    assertEquals(listOf("New Search", "Newer Search", "Tag 0"), controller.tabs.value.take(3).map { it.title })
    assertEquals(controller.sessions.current, controller.tabs.value[0].addedIn)
    assertEquals(SessionId.EARLIER, controller.tabs.value[2].addedIn)
  }

  @Test
  fun theSessionIdIsSaved() {
    val controller = TabsController(clock = clock)

    assertEquals(controller.sessions.current.value, controller.saved().session)
    assertEquals(controller.sessions.current.value, controller.saved().tabs.single().addedIn)
  }

  @Test
  fun aPlaceSurvivesTheRoundTrip() {
    val place = GridPlace("p1", "c1", 3, 1_700_000_000)
    val file = InMemoryTextFile()
    val store = FileTabStateStore(file)
    store.save(SavedTabs(0, listOf(SavedTab("a", listOf(Route.Grid(listOf("x"))), place = place))))

    assertEquals(place, store.load()!!.tabs.single().place)
  }

  private class FakeStore(val saved: SavedTabs?) : TabStateStore {
    override fun load() = saved

    override fun save(tabs: SavedTabs) = Unit

    override fun clear() = Unit
  }
}
