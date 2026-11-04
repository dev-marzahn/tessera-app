package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.queue.SourceKey
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * #215, stage 3a: sources untouched for 3 days fade with their unseen kept posts, a faded source is deleted 30 days after
 * it faded, and opening one brings it back. Every time here is the injected clock's: nothing waits.
 */
class FadingTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock, seen = seen)
  private val navigator = TabsNavigator(tabs)

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private fun post(id: String) = Route.Post(id, "md5$id", listOf("a"), label = "Post $id")

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun fadedTitles() = tabs.faded.value.map { it.title }

  private fun source(title: String) = tabs.tabs.value.single { it.title == title }

  // Browse (the shown item), then the source "a" with the kept posts p1 and p2 in its block, the source "b" and the feed
  // Following. All of it is touched at [start].
  private fun withKept() {
    tabs.open(grid("a"), background = false)
    tabs.openInBlock(listOf(post("p1")))
    tabs.openInBlock(listOf(post("p2")))
    tabs.open(grid("b"), background = true)
    tabs.open(grid("feed:following"), background = true)
    tabs.activate(tabs.tabs.value.first().id)
    assertEquals(listOf("Browse", "A", "Post p1", "Post p2", "B", "Following"), titles())
  }

  @Test
  fun aSourceFadesAfterThreeDaysUntouchedAndNotBefore() {
    tabs.open(grid("a"), background = true)

    now = start + 3.days - 1.minutes
    tabs.sweep()
    assertEquals(listOf("Browse", "A"), titles())
    assertTrue(tabs.faded.value.isEmpty())

    now = start + 3.days
    tabs.sweep()
    assertEquals(listOf("Browse"), titles())
    assertEquals(listOf("A"), fadedTitles())
  }

  @Test
  fun theItemShownNeverFadesAndItsThreeDaysStartAgain() {
    tabs.open(grid("a"), background = false)

    now = start + 20.days
    tabs.sweep()

    assertEquals("A", tabs.activeTab.title)
    assertFalse("A" in fadedTitles())
    // It was shown, so the time since runs from now: leaving it does not fade it at once.
    tabs.open(grid("z"), background = false)
    now += 2.days
    tabs.sweep()
    assertFalse("A" in fadedTitles())
    now += 1.days
    tabs.sweep()
    assertTrue("A" in fadedTitles())
    assertEquals("Z", tabs.activeTab.title)
  }

  @Test
  fun theSourceOfTheShownKeptPostDoesNotFadeEither() {
    withKept()
    tabs.activate(tabs.tabs.value.single { it.top == post("p1") }.id)

    now = start + 10.days
    tabs.sweep()

    // "a" and its kept posts stay with the one shown; Browse and "b", untouched, fade.
    assertEquals(listOf("A", "Post p1", "Post p2", "Following"), titles())
    assertEquals(listOf("Browse", "B"), fadedTitles())
  }

  @Test
  fun feedsAndKeptPostsOnTheirOwnNeverFade() {
    tabs.open(grid("feed:following"), background = true)
    tabs.open(post("loose"), background = true)

    now = start + 60.days
    tabs.sweep()

    assertEquals(listOf("Browse", "Following", "Post loose"), titles())
    assertTrue(tabs.faded.value.isEmpty())
  }

  @Test
  fun unseenKeptPostsFadeWithTheirSourceAsOneBlockAndSeenOnesStay() {
    withKept()
    now += 1.minutes
    seen.record("p1", "md5p1")

    now = start + 3.days + 1.hours
    tabs.sweep()

    assertEquals(listOf("A", "B"), fadedTitles())
    val a = tabs.faded.value.first()
    assertEquals(1, a.keptCount)
    assertEquals(listOf("A", "Post p2"), a.members.map { tabTitle(it.routes.last()) })
    // The seen kept post stays in the queue, on its own.
    assertEquals(listOf("Browse", "Post p1", "Following"), titles())
  }

  @Test
  fun theSeenKeptPostsItLeavesAreAClosedBlockThatRestoringTheSourceHeadsAgain() {
    withKept()
    now += 1.minutes
    seen.record("p1", "md5p1")
    now = start + 3.days + 1.hours
    tabs.sweep()
    val left = tabs.tabs.value.single { it.top == post("p1") }
    assertEquals(listOf("a"), left.closedSource)

    val restored = tabs.restore(tabs.faded.value.first { it.title == "A" }.id)!!

    // In front of the post it left behind, as a closed source's namesake goes (#184), with its own kept post first.
    assertEquals(listOf("Browse", "A", "Post p2", "Post p1", "Following"), titles())
    assertEquals(restored.id, left.block)
    assertNull(left.closedSource)
  }

  @Test
  fun aFadedSourceIsDeletedThirtyDaysAfterItFadedWithItsKeptPosts() {
    withKept()
    now = start + 3.days
    tabs.sweep()
    val a = tabs.faded.value.first { it.title == "A" }
    assertEquals(start + 3.days, a.fadedAt)
    assertEquals(start + 33.days, a.deletesAt)
    assertEquals(2, a.keptCount)

    now = start + 33.days - 1.minutes
    tabs.sweep()
    assertEquals(listOf("A", "B"), fadedTitles())

    now = start + 33.days
    tabs.sweep()
    assertTrue(tabs.faded.value.isEmpty())
  }

  @Test
  fun deletionNeverTouchesKeptPostsOutsideTheFadedBlockNorFeedsNorOtherSources() {
    withKept()
    now += 1.minutes
    seen.record("p1", "md5p1")
    // "b" and the feed are touched every day, so only "a" and its unseen kept post fade, and are deleted.
    for (day in 1..40) {
      now = start + day.days
      tabs.touch(source("B").id)
      tabs.touch(source("Following").id)
      tabs.sweep()
    }

    assertTrue(tabs.faded.value.isEmpty())
    assertEquals(listOf("Browse", "Post p1", "B", "Following"), titles())
  }

  @Test
  fun theCountdownReadsDeletedInNDays() {
    tabs.open(grid("a"), background = true)
    now = start + 3.days
    tabs.sweep()
    val a = tabs.faded.value.single()

    assertEquals("Deleted in 30 days", a.deletedIn(now))
    assertEquals("Deleted in 30 days", fadedSubtitle(a, now))
    now += 1.days
    assertEquals("Deleted in 29 days", fadedSubtitle(a, now))
    now = start + 32.days + 12.hours
    assertEquals("Last chance · Deleted in 1 day", fadedSubtitle(a, now))
  }

  @Test
  fun aSourceFoundPastItsDeletionDateIsDeletedWithoutEverBeingListed() {
    tabs.open(grid("a"), background = true)

    now = start + 40.days
    tabs.sweep()

    assertEquals(listOf("Browse"), titles())
    assertTrue(tabs.faded.value.isEmpty())
  }

  @Test
  fun reopeningAFadedSourceFromItsRowPutsItBackAtTheEndOfTheSessionWithItsPlaceAndBlock() {
    withKept()
    val a = source("A")
    a.reportPlace(a.top as Route.Grid, GridPlace("p9", time = null))
    now = start + 3.days
    tabs.sweep()
    val faded = tabs.faded.value.first { it.title == "A" }
    // Something opened since goes first: the restored block follows it.
    tabs.open(grid("c"), background = true)

    now = start + 5.days
    val restored = tabs.restore(faded.id, show = true)!!

    // C and A went "later", to the end of the session, which is after Following (#327).
    assertEquals(listOf("Browse", "Following", "C", "A", "Post p1", "Post p2"), titles())
    assertEquals(restored.id, tabs.active.value)
    assertEquals(GridPlace("p9", time = null), restored.place)
    assertEquals(listOf(restored.id, restored.id), tabs.tabs.value.filter { it.kind == QueueKind.KeptPost }.map { it.block })
    assertEquals(start + 5.days, restored.touched)
    assertEquals(listOf("B"), fadedTitles())
  }

  @Test
  fun reopeningRestartsTheThreeDaysAndTheThirty() {
    tabs.open(grid("a"), background = true)
    now = start + 3.days
    tabs.sweep()
    val faded = tabs.faded.value.single()
    now = start + 20.days
    val restored = tabs.restore(faded.id)!!

    now = start + 22.days
    tabs.sweep()
    assertTrue(tabs.faded.value.isEmpty())
    assertNotNull(tabs.tabs.value.firstOrNull { it.id == restored.id })

    now = start + 23.days
    tabs.sweep()
    val again = tabs.faded.value.single()
    // The new fade, 3 days after the reopening; the old one's 30 days are gone.
    assertEquals(start + 23.days, again.fadedAt)
    assertEquals(start + 53.days, again.deletesAt)
  }

  @Test
  fun aSearchThatMatchesAFadedSourceRestoresItWithItsPlaceThroughItsKey() {
    tabs.open(grid("artist_x"), background = true)
    val tab = source("Artist X")
    tab.reportPlace(tab.top as Route.Grid, GridPlace("p7", time = null))
    now = start + 4.days
    tabs.sweep()
    assertEquals(listOf("Artist X"), fadedTitles())
    val key = SourceKey.of(listOf("artist_x"))!!
    assertNull(tabs.sourceFor(key))
    assertNotNull(tabs.fadedFor(key))

    // The same tag in another order is the same source.
    navigator.goTo(grid("order:popularity", "artist_x"))

    assertTrue(tabs.faded.value.isEmpty())
    val restored = tabs.sourceFor(key)!!
    assertEquals(restored.id, tabs.active.value)
    assertEquals(GridPlace("p7", time = null), restored.place)
    assertEquals(2, tabs.tabs.value.size)
  }

  @Test
  fun restoringASourceWhoseSearchIsOpenAgainAddsNoSecondSourceAndJoinsItsBlock() {
    withKept()
    tabs.close(source("A").id)
    val closed = tabs.closed.value.last()
    navigator.goTo(grid("a"))
    // The new "a" fades, with the unseen kept post it was given.
    tabs.openInBlock(listOf(post("p3")))
    tabs.activate(tabs.tabs.value.first { it.title == "Browse" }.id)
    now = start + 3.days
    tabs.sweep()
    val faded = tabs.faded.value.first { it.title == "A" }
    tabs.reopen(closed)
    assertEquals(1, tabs.tabs.value.count { it.title == "A" })

    val open = tabs.restore(faded.id)!!

    assertEquals(1, tabs.tabs.value.count { it.title == "A" })
    assertEquals(open.id, tabs.tabs.value.single { it.top == post("p3") }.block)
    assertTrue(tabs.faded.value.none { it.title == "A" })
  }

  @Test
  fun aMatchingSearchOpenedLaterRestoresItToo() {
    tabs.open(grid("artist_x"), background = true)
    now = start + 4.days
    tabs.sweep()

    val restored = tabs.open(grid("artist_x"), background = true)

    assertNotNull(restored)
    assertTrue(tabs.faded.value.isEmpty())
    assertFalse(tabs.active.value == restored.id)
    assertEquals(listOf("Browse", "Artist X"), titles())
  }

  @Test
  fun deleteNowDeletesAtOnceAndUndoBringsTheBlockBack() {
    withKept()
    now = start + 3.days
    tabs.sweep()
    val a = tabs.faded.value.first { it.title == "A" }

    val closed = tabs.deleteFaded(a.id)!!

    assertEquals(listOf("B"), fadedTitles())
    assertEquals(3, closed.size)
    assertNull(tabs.deleteFaded(a.id))

    tabs.reopen(closed)
    // At the end of the queue, after Following: a feed isn't kept last (#327).
    assertEquals(listOf("Browse", "Following", "A", "Post p1", "Post p2"), titles())
    val back = source("A")
    assertTrue(tabs.tabs.value.filter { it.kind == QueueKind.KeptPost }.all { it.block == back.id })
  }

  @Test
  fun lastChanceIsOfferedOnceInTheLastWeekAndKeepAndLetGoAnswerIt() {
    tabs.open(grid("a"), background = true)
    tabs.open(grid("b"), background = true)
    now = start + 4.days
    tabs.sweep()
    assertNull(tabs.lastChance())

    // The last week: 23 days after they faded.
    now = start + 3.days + 23.days
    val chance = tabs.lastChance()!!
    assertEquals("A", chance.title)
    assertEquals("Last chance · Deleted in 7 days", fadedSubtitle(chance, now))

    tabs.offered(chance.id)
    assertEquals("B", tabs.lastChance()?.title)
    tabs.offered(tabs.lastChance()!!.id)
    // Offered once: nothing is offered twice, and the countdown goes on.
    assertNull(tabs.lastChance())
    assertEquals(listOf("A", "B"), fadedTitles())

    // Keep brings "a" back as if opened; Let go deletes "b" now.
    val kept = tabs.keep(chance.id)!!
    assertEquals(now, kept.touched)
    assertNotNull(tabs.letGo(tabs.faded.value.single().id))
    assertTrue(tabs.faded.value.isEmpty())
    assertEquals(listOf("Browse", "A"), titles())
  }

  @Test
  fun noAnswerMeansDeletedOnDayThirty() {
    tabs.open(grid("a"), background = true)
    now = start + 3.days
    tabs.sweep()
    now = start + 3.days + 25.days
    assertNotNull(tabs.lastChance())

    // Never offered, never answered.
    now = start + 33.days
    tabs.sweep()
    assertTrue(tabs.faded.value.isEmpty())
    assertNull(tabs.lastChance())
  }

  @Test
  fun fadedSourcesAreNotQueueItemsForSteppingOrCounting() {
    tabs.open(grid("a"), background = true)
    now = start + 4.days
    tabs.sweep()

    assertFalse(tabs.step(forward = true, wrap = false))
    assertEquals(1, tabs.tabs.value.size)
  }

  @Test
  fun theFadedSourcesAndTheirDatesSurviveARestart() {
    val file = InMemoryTextFile()
    val store = FileTabStateStore(file)
    val first = TabsController(store, clock = clock, seen = seen)
    first.open(grid("a"), background = true)
    first.open(grid("b"), background = true)
    val a = first.tabs.value.single { it.title == "A" }
    a.reportPlace(a.top as Route.Grid, GridPlace("p3", time = null))
    now = start + 4.days
    first.sweep()
    first.offered(first.faded.value.first().id)
    store.save(first.saved())

    val second = TabsController(FileTabStateStore(file), clock = clock, seen = seen)

    assertEquals(listOf("A", "B"), second.faded.value.map { it.title })
    val restored = second.faded.value.first()
    assertEquals(start + 3.days, restored.fadedAt)
    assertEquals(GridPlace("p3", time = null), restored.place)
    assertTrue(restored.offered)
    assertFalse(second.faded.value.last().offered)
  }

  @Test
  fun launchingWorksOutFadingAndDeletion() {
    val file = InMemoryTextFile()
    val store = FileTabStateStore(file)
    val first = TabsController(store, clock = clock, seen = seen)
    first.open(grid("old"), background = true)
    now = start + 2.days
    first.open(grid("newer"), background = true)
    store.save(first.saved())

    // The app was closed for 4 days: "old" is 4 days untouched, "newer" 2.
    now = start + 4.days
    val second = TabsController(FileTabStateStore(file), clock = clock, seen = seen)
    assertEquals(listOf("Old"), second.faded.value.map { it.title })
    assertEquals(listOf("Browse", "Newer"), second.tabs.value.map { it.title })

    // And closed for 40 days: both are past their 33.
    store.save(second.saved())
    now = start + 40.days
    val third = TabsController(FileTabStateStore(file), clock = clock, seen = seen)
    assertTrue(third.faded.value.isEmpty())
    assertEquals(listOf("Browse"), third.tabs.value.map { it.title })
  }

  @Test
  fun theFirstLaunchAfterTheV2MigrationStampsEverythingTouchedSoNothingFadesAtOnce() {
    fun route(route: Route) = Json.encodeToString(Route.serializer(), route)
    val file = InMemoryTextFile()
    file.write(
      """{"activeIndex":0,"tabs":[{"id":"t0","routes":[${route(grid("old_tag"))}]},{"id":"t1","routes":[${route(grid("older_tag"))}]}]}""",
    )

    val launched = TabsController(FileTabStateStore(file, clock = clock), clock = clock, seen = seen)

    assertTrue(launched.faded.value.isEmpty())
    assertEquals(2, launched.tabs.value.size)
    assertTrue(launched.tabs.value.all { it.touched == start })
  }

  @Test
  fun clearEverythingForgetsTheFadedSources() {
    tabs.open(grid("a"), background = true)
    now = start + 4.days
    tabs.sweep()

    tabs.forgetFaded()

    assertTrue(tabs.faded.value.isEmpty())
  }
}
