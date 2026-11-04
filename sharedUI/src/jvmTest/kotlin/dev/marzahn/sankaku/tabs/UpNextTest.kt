package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/** An [UpNextState] for a test that doesn't look at it: no G only, messages nobody reads. */
internal fun noUpNext(tabs: TabsController) = UpNextState(tabs, PrivacySettings(InMemoryTextFile()), Messages())

/**
 * #216, #230: what Up next offers past the last source, End of the queue past the last item, and what answering does.
 * Every time here is the injected clock's, so no test waits: the days between are `now += n.days`.
 */
class UpNextTest {
  private val start = Instant.parse("2026-11-01T12:00:00Z")
  private var now = start
  private val clock = object : Clock {
    override fun now() = now
  }
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(clock = clock, seen = seen)
  private val privacy = PrivacySettings(InMemoryTextFile())
  private val messages = Messages()
  private val upNext = UpNextState(tabs, privacy, messages)

  private fun grid(vararg tags: String) = Route.Grid(tags.toList())

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun source(title: String) = tabs.tabs.value.single { it.title == title }

  /** The cards shown one after the other as each is skipped, by title, until the card goes or reaches the end. */
  private fun skipThrough(): List<String> {
    val shown = mutableListOf<String>()
    upNext.show()
    while (true) {
      val card = upNext.card as? UpNextCard.Offer ?: break
      shown += card.title
      upNext.skip()
    }
    return shown
  }

  /**
   * "oldest" (touched day 0), "middle" (day 1) and "newest" (day 2) fade 3 days after; "last" is shown on day 6, so it
   * doesn't. The sweep runs on day 6.
   */
  private fun threeFaded() {
    tabs.open(grid("oldest"), background = true)
    now = start + 1.days
    tabs.open(grid("middle"), background = true)
    now = start + 2.days
    tabs.open(grid("newest"), background = true)
    now = start + 6.days
    // Browse was looked at today, so it doesn't fade.
    tabs.activate(tabs.tabs.value.first().id)
    tabs.open(grid("last"), background = false)
    tabs.sweep()
    assertEquals(listOf("Oldest", "Middle", "Newest"), tabs.faded.value.map { it.title })
    assertEquals(listOf("Browse", "Last"), titles())
  }

  @Test
  fun signedOutWithNothingFadedASwipePastTheLastItemShowsTheEndNeverAnItemOfTheQueue() {
    // Finding 2 of #228: two items, and Clear viewed started a new session, so before #230 item 1 was offered.
    tabs.open(grid("pipoyami", "order:popularity"), background = false)
    tabs.open(grid("scenery"), background = false)
    now = start + 1.days
    tabs.sessions.startNew()
    tabs.activate(tabs.tabs.value.last().id)

    assertEquals(Ahead.End, upNext.ahead())
    assertFalse(upNext.forward())

    assertEquals(UpNextCard.End, upNext.card)
    assertEquals(tabs.tabs.value.last().id, tabs.activeTab.id)
    // On the End card there is nothing further.
    assertEquals(Ahead.Nothing, upNext.ahead())
  }

  @Test
  fun anItemInTheQueueIsNeverOfferedWhateverItsSession() {
    tabs.open(grid("a"), background = true)
    tabs.open(grid("b"), background = false)
    tabs.sessions.startNew()
    now = start + 2.days

    assertFalse(upNext.available())
    assertFalse(upNext.show())
    assertNull(upNext.card)
  }

  @Test
  fun theFadedSourcesAreOfferedTheOneThatFadedFirstFirst() {
    threeFaded()

    assertEquals(listOf("Oldest", "Middle", "Newest"), skipThrough())
    // Past the last offer, with no feeds, End of the queue.
    assertEquals(UpNextCard.End, upNext.card)
    assertEquals("Last", tabs.activeTab.title)
  }

  @Test
  fun aFadedCardAsksToPickItUpAndSaysWhatItHolds() {
    threeFaded()
    val oldest = tabs.faded.value.first()

    upNext.show()

    val card = assertIs<UpNextCard.Pickup>(upNext.card)
    assertEquals(oldest.id, card.id)
    assertEquals("Oldest", card.title)
    // Faded on day 3, it's day 6: deleted on day 33.
    assertEquals("Deleted in 27 days", card.detail)
    // Its page shows its own search.
    assertEquals(grid("oldest"), card.search)
  }

  @Test
  fun openRestoresTheFadedSourceWithItsPlaceAndShowsIt() {
    tabs.open(grid("gone"), background = true)
    val gone = source("Gone")
    gone.reportPlace(gone.top as Route.Grid, GridPlace("42"))
    tabs.activate(gone.id)
    tabs.openInBlock(listOf(Route.Post("p1", "m1", listOf("gone"), label = "Rosehip", rating = "s")))
    tabs.activate(tabs.tabs.value.first().id)
    now = start + 4.days
    tabs.sweep()
    assertEquals(listOf("Gone"), tabs.faded.value.map { it.title })

    upNext.show()
    upNext.open()

    assertNull(upNext.card)
    assertTrue(tabs.faded.value.isEmpty())
    assertEquals("Gone", tabs.activeTab.title)
    assertEquals(GridPlace("42"), tabs.activeTab.place)
    assertEquals(listOf("Browse", "Gone", "Rosehip"), titles())
  }

  @Test
  fun skippingTouchesNothingSoTheSourceStaysFadedAndIsDeletedOnTime() {
    threeFaded()

    upNext.show()
    upNext.skip()

    assertEquals(listOf("Oldest", "Middle", "Newest"), tabs.faded.value.map { it.title })
    now = start + 33.days
    tabs.sweep()
    // "oldest" is deleted on day 33 (Browse, untouched since day 6, has faded meanwhile).
    assertEquals(listOf("Middle", "Newest"), tabs.faded.value.map { it.title }.filter { it != "Browse" })
  }

  // Signed in: Following (and For you) are added at the end (#229, #327).
  private fun withFeeds() {
    tabs.pinFeeds(listOf(Feed.Following, Feed.ForYou))
    assertEquals(listOf("Following", "For you"), titles().takeLast(2))
  }

  @Test
  fun upNextStandsPastTheLastItemEvenWhenItIsAFeed() {
    threeFaded()
    withFeeds()
    tabs.activate(source("Last").id)

    // #327: no card before the feeds; they're items like any other.
    assertEquals(Ahead.Item, upNext.ahead())
    assertTrue(upNext.forward())
    assertEquals("Following", tabs.activeTab.title)
    assertTrue(upNext.forward())
    assertEquals("For you", tabs.activeTab.title)

    // Past the last item, Up next, then End of the queue.
    assertEquals(Ahead.UpNext, upNext.ahead())
    assertFalse(upNext.forward())
    assertEquals(listOf("Oldest", "Middle", "Newest"), skipThrough())
    assertEquals(UpNextCard.End, upNext.card)
  }

  @Test
  fun aLaterSearchAfterTheFeedsIsReachedBeforeUpNext() {
    threeFaded()
    withFeeds()
    tabs.activate(source("For you").id)
    // "Later" goes to the end of the queue, after the feeds (#327).
    tabs.open(grid("later"), background = true)
    assertEquals(listOf("Following", "For you", "Later"), titles().takeLast(3))

    assertEquals(Ahead.Item, upNext.ahead())
    assertTrue(upNext.forward())
    assertEquals("Later", tabs.activeTab.title)
    assertEquals(Ahead.UpNext, upNext.ahead())
    upNext.forward()
    assertIs<UpNextCard.Pickup>(upNext.card)
    // On the card, moving on goes to End of the queue: nothing follows.
    assertEquals(Ahead.End, upNext.ahead())
    upNext.forward()
    assertEquals(UpNextCard.End, upNext.card)
  }

  @Test
  fun signedInWithNothingFadedTheLastSourceMovesStraightToFollowing() {
    tabs.open(grid("a"), background = false)
    withFeeds()
    tabs.activate(source("A").id)

    assertEquals(Ahead.Item, upNext.ahead())
    assertTrue(upNext.forward())
    assertEquals("Following", tabs.activeTab.title)
  }

  @Test
  fun backToTheStartShowsItemOneAndBrowseOpensABrowseGrid() {
    tabs.open(grid("a"), background = false)
    tabs.open(grid("b"), background = false)
    upNext.forward()
    assertEquals(UpNextCard.End, upNext.card)

    upNext.backToStart()

    assertNull(upNext.card)
    assertEquals(tabs.tabs.value.first().id, tabs.activeTab.id)

    tabs.activate(source("B").id)
    upNext.forward()
    upNext.browse()
    assertNull(upNext.card)
    assertEquals(grid(), tabs.activeTab.top)
  }

  @Test
  fun backToTheStartPassesOverWhatGOnlyHides() {
    tabs.open(grid("rating:e", "lace"), background = true)
    tabs.open(grid("b"), background = false)
    tabs.moveInQueue(source("Lace").id, down = false)
    assertEquals(listOf("Lace", "Browse", "B"), titles())
    privacy.setGOnly(true)

    upNext.forward()
    upNext.backToStart()

    assertEquals("Browse", tabs.activeTab.title)
  }

  // "gone" (touched day 0, with a kept post when [kept]) and "later" (day 2) fade; it is day 26 now, 23 days after
  // "gone" faded: in its last week. "later" isn't, so it's a pickup after the Last chance.
  private fun withAFadedSourceInItsLastWeek(kept: Boolean = false) {
    tabs.open(grid("gone"), background = true)
    if (kept) {
      tabs.activate(source("Gone").id)
      tabs.openInBlock(listOf(Route.Post("p1", "m1", listOf("gone"), label = "Rosehip", rating = "s")))
      tabs.activate(tabs.tabs.value.first().id)
    }
    now = start + 2.days
    tabs.open(grid("later"), background = true)
    now = start + 6.days
    tabs.sweep()
    assertEquals(listOf("Gone", "Later"), tabs.faded.value.map { it.title })
    now = start + 3.days + 23.days
  }

  @Test
  fun aLastChanceOutranksTheOtherFadedSourcesAndIsOfferedOnce() {
    withAFadedSourceInItsLastWeek(kept = true)

    upNext.show()

    val card = assertIs<UpNextCard.LastChance>(upNext.card)
    assertEquals("Gone", card.title)
    assertEquals("Deleted in 7 days · 1 kept post", card.detail)
    // Shown means offered: the queue's seam no longer lists it, and it is deleted on day 30 all the same.
    assertNull(tabs.lastChance())
    upNext.skip()
    // Not offered again as a pickup.
    assertEquals("Later", assertIs<UpNextCard.Pickup>(upNext.card).title)
    upNext.skip()
    assertEquals(UpNextCard.End, upNext.card)
  }

  @Test
  fun keepBringsTheSourceBackAndCarriesOnToTheNextFadedSource() {
    withAFadedSourceInItsLastWeek(kept = true)
    upNext.show()

    upNext.keep()

    assertEquals(listOf("Later"), tabs.faded.value.map { it.title })
    // As `keep` does for 3a: back in the queue with its kept post, not shown.
    assertEquals(listOf("Browse", "Gone", "Rosehip"), titles())
    assertEquals("Browse", tabs.activeTab.title)
    assertEquals("Later", assertIs<UpNextCard.Pickup>(upNext.card).title)
  }

  @Test
  fun keepOnTheLastOfferDoesNotShowTheKeptSource() {
    tabs.open(grid("gone"), background = true)
    now = start + 4.days
    tabs.sweep()
    now = start + 26.days
    upNext.show()
    assertIs<UpNextCard.LastChance>(upNext.card)

    upNext.keep()

    // Back in the queue, after the item shown, which stays: the card goes back to it.
    assertNull(upNext.card)
    assertEquals("Browse", tabs.activeTab.title)
    assertEquals(listOf("Browse", "Gone"), titles())
  }

  @Test
  fun letGoDeletesNowAndCarriesOnWithOneEntryToUndoIt() {
    withAFadedSourceInItsLastWeek()
    upNext.show()

    upNext.letGo()

    assertEquals(listOf("Later"), tabs.faded.value.map { it.title })
    val entry = tabs.closed.value.single()
    assertEquals("Gone", entry.title)
    assertEquals("Later", assertIs<UpNextCard.Pickup>(upNext.card).title)
    // Undo (the top line's) is `reopen`: it brings the source back.
    tabs.reopen(entry)
    assertTrue("Gone" in titles())
  }

  @Test
  fun aLastChanceDismissedUnansweredIsDeletedOnDay30AllTheSame() {
    withAFadedSourceInItsLastWeek()
    upNext.show()
    assertIs<UpNextCard.LastChance>(upNext.card)

    upNext.dismiss()
    upNext.show()

    // Offered once: now it's a pickup like the others, the one that faded first first.
    assertEquals("Gone", assertIs<UpNextCard.Pickup>(upNext.card).title)
    now = start + 3.days + 30.days
    tabs.sweep()
    assertEquals(listOf("Later"), tabs.faded.value.map { it.title })
  }

  @Test
  fun gOnlyLeavesAdultFadedSourcesOutAndALastChanceOnOneWaits() {
    tabs.open(grid("rating:e", "lace"), background = true)
    tabs.open(grid("oldest"), background = true)
    now = start + 4.days
    tabs.sweep()
    now = start + 3.days + 23.days
    privacy.setGOnly(true)

    // "lace" is in its last week, but hidden; "oldest" is offered.
    assertEquals(listOf("Oldest"), skipThrough())
    assertFalse(tabs.faded.value.first { it.title == "Lace" }.offered)

    privacy.setGOnly(false)
    upNext.dismiss()
    // The rating filter isn't part of the title (#233).
    assertEquals("Lace", assertIs<UpNextCard.LastChance>(run { upNext.show(); upNext.card }).title)
  }

  @Test
  fun theCardAndItsSkipsAreForgottenOnceItIsDismissed() {
    threeFaded()
    upNext.show()
    upNext.skip()
    assertEquals("Middle", (upNext.card as UpNextCard.Offer).title)

    upNext.dismiss()
    upNext.show()

    assertEquals("Oldest", (upNext.card as UpNextCard.Offer).title)
  }

  @Test
  fun aStartTakesTheCardsAwaySoNeitherIsEverTheFirstThingShown() {
    val time = TestTimeSource()
    val queueStart = QueueStart(tabs, privacy, time, upNext)
    threeFaded()

    // A launch: nothing was shown, and no card is there.
    queueStart.launched()
    assertNull(upNext.card)

    // Moving past the end shows it; a quick return keeps it, a return after more than 5 minutes takes it away.
    upNext.show()
    assertNotNull(upNext.card)
    queueStart.left()
    time += 4.minutes
    queueStart.returned()
    assertNotNull(upNext.card)
    queueStart.left()
    time += 6.minutes
    queueStart.returned()
    assertNull(upNext.card)

    // The same for End of the queue.
    upNext.dismiss()
    upNext.show()
    repeat(3) { upNext.skip() }
    assertEquals(UpNextCard.End, upNext.card)
    queueStart.left()
    time += 6.minutes
    queueStart.returned()
    assertNull(upNext.card)
  }
}
