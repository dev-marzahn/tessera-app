package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.QueuePageAtStart
import dev.marzahn.sankaku.queue.SeenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * #385: a Books search is a queue source like a grid's search (one per search), a kept book is a queue item of its own
 * ([QueueKind.KeptBook]) in its search's block, and both are saved and restored. The ADR 0004 rules hold for them as
 * for grids and posts.
 */
class BooksInQueueTest {
  private var now = Instant.parse("2026-11-06T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val file = InMemoryTextFile(null)
  private val seen = SeenStore(InMemoryTextFile(), clock)
  private val tabs = TabsController(FileTabStateStore(file), clock = clock, seen = seen)
  private val navigator = TabsNavigator(tabs)
  private val maid = Route.Books(listOf("maid"))

  private fun book(id: String, title: String = "Book $id") = Route.Book(id, title, cover = "c$id", rating = "s")

  private fun titles() = tabs.tabs.value.map { it.title }

  private fun tab(title: String) = tabs.tabs.value.single { it.title == title }

  @Test
  fun aBooksSearchIsASourceAndAKeptBookItsOwnKind() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1"))

    // Opened "now", in front of the current block, as a grid's search is.
    assertEquals(listOf("Books · Maid", "Book 1", "Browse"), titles())
    assertEquals(QueueKind.Source, tab("Books · Maid").kind)
    assertEquals(QueueKind.KeptBook, tab("Book 1").kind)
    // Under its search, as a kept post is under its grid.
    assertSame(tab("Books · Maid"), tabs.sourceOf(tab("Book 1")))
    assertNotNull(tab("Book 1").keptAt)
  }

  @Test
  fun keepingABookAgainSaysWhereItIsAndKeepsItAnew() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1"))
    val keptAt = tab("Book 1").keptAt

    now += 1.minutes
    val position = navigator.openInNewTab(book("1"))

    assertEquals(2, position)
    assertEquals(listOf("Books · Maid", "Book 1", "Browse"), titles())
    assertTrue(tab("Book 1").keptAt!! > keptAt!!)
  }

  @Test
  fun oneSourcePerBooksSearchApartFromTheGridOnTheSameTags() {
    navigator.goTo(Route.Grid(listOf("maid")))
    tabs.activeTab.markTouched(Route.Grid(listOf("maid")))
    navigator.goTo(maid)
    navigator.goTo(Route.Grid(listOf("artist_x")))

    // The same Books search, by another order, shows the open one; the grid on "maid" is a source of its own.
    navigator.goTo(Route.Books(listOf("order:date", "maid")))

    // "maid" replaced the untouched Browse in place.
    assertEquals(listOf("Artist X", "Books · Maid", "Maid"), titles())
    assertSame(tab("Books · Maid"), tabs.activeTab)
    assertEquals(1, tabs.tabs.value.count { it.top is Route.Books })
  }

  @Test
  fun plainBooksIsOneItemToo() {
    navigator.openInNewTab(Route.Books())
    val again = navigator.openInNewTab(Route.Books())

    assertEquals(2, again)
    assertEquals(listOf("Browse", "Books"), titles())
  }

  @Test
  fun aBooksTabAndItsKeptBooksAreSavedAndRestored() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1", "Volume one"))
    val saved = tabs.saved()

    assertEquals(listOf(QueueKind.Source, QueueKind.KeptBook, QueueKind.Source), saved.tabs.map { it.kind })
    FileTabStateStore(file).save(saved)
    val restored = TabsController(FileTabStateStore(file), clock = clock)

    assertEquals(listOf("Books · Maid", "Volume one", "Browse"), restored.tabs.value.map { it.title })
    val kept = restored.tabs.value[1]
    assertEquals(Route.Book("1", "Volume one", "c1", "s"), kept.top)
    assertEquals(saved.tabs[1].keptAt, kept.keptAt?.epochSeconds)
    assertSame(restored.tabs.value[0], restored.sourceOf(kept))
  }

  @Test
  fun aKeptBookSeenSinceItWasKeptIsSeenFromItsSearchAndClearViewedClearsIt() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1"))
    navigator.openInNewTab(book("2"))
    assertFalse(tabs.seenFromBooks(tab("Books · Maid"), "1", seen))

    now += 1.minutes
    seen.record(bookSeenId("1"), null)

    assertTrue(tabs.seenFromBooks(tab("Books · Maid"), "1", seen))
    assertFalse(tabs.seenFromBooks(tab("Books · Maid"), "2", seen))
    // A post with the book's id is another thing.
    assertFalse(seen.isSeen("1"))
    assertTrue(tabs.keptBookSeen("1", seen))
    assertEquals(listOf(tab("Book 1").id), tabs.viewedKeptPosts(seen))

    tabs.clearViewed(seen)

    assertEquals(listOf("Books · Maid", "Book 2", "Browse"), titles())
  }

  @Test
  fun closingABooksSourceLeavesItsKeptBooksAndTheSameSearchTakesThemBack() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1"))
    navigator.goTo(Route.Grid(listOf("maid")))

    val closed = tabs.close(tab("Books · Maid").id)

    assertEquals(1, closed?.keptStay)
    assertEquals(listOf(BOOKS_SEARCH, "maid"), tab("Book 1").closedSource)
    assertEquals("from Books · Maid (closed)", closedBlockTitle(tab("Book 1").closedSource!!))
    // The grid on the same tags takes back nothing: it searches posts.
    assertTrue(tabs.closedBlock(listOf("maid")).isEmpty())

    navigator.openInNewTab(Route.Books(listOf("maid", "order:date")))

    val source = tabs.tabs.value.single { it.top is Route.Books }
    assertNull(tab("Book 1").closedSource)
    assertSame(source, tabs.sourceOf(tab("Book 1")))
    assertEquals(tabs.tabs.value.indexOf(source) + 1, tabs.tabs.value.indexOf(tab("Book 1")))
  }

  @Test
  fun aBooksSourceFadesWithItsUnseenKeptBooksAndItsSearchRestoresIt() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1"))
    navigator.goTo(Route.Grid(listOf("artist_x")))

    now += 4.days
    tabs.sweep()

    // Browse faded too; Artist X is shown, so it never fades.
    assertEquals(listOf("Artist X"), titles())
    val faded = tabs.faded.value.single { it.title == "Books · Maid" }
    assertEquals(1, faded.keptCount)
    assertFalse(faded.adult)

    navigator.goTo(maid)

    assertEquals(listOf("Browse"), tabs.faded.value.map { it.title })
    assertEquals(listOf("Artist X", "Books · Maid", "Book 1"), titles())
    assertSame(tab("Books · Maid"), tabs.activeTab)
  }

  @Test
  fun aKeptBookOpenedNowIsALoneItemAfterTheCurrentOne() {
    navigator.goTo(maid)
    navigator.goTo(book("1"))

    assertEquals(listOf("Books · Maid", "Book 1", "Browse"), titles())
    assertSame(tab("Book 1"), tabs.activeTab)
    assertTrue(tab("Book 1").chromeCovered)

    // Back on it goes to the item before, as on a lone post.
    navigator.goBack()

    assertSame(tab("Books · Maid"), tabs.activeTab)
  }

  @Test
  fun aNewBooksSearchFromASourceWithKeptBooksOpensInFrontOfIt() {
    navigator.goTo(maid)
    navigator.openInNewTab(book("1"))

    navigator.goTo(Route.Books(listOf("nurse")))

    assertEquals(listOf("Books · Nurse", "Books · Maid", "Book 1", "Browse"), titles())
    assertSame(tab("Books · Maid"), tabs.sourceOf(tab("Book 1")))
  }

  // #479: a series opened in front of the Books list it was tapped in (books were kept from it) is alone in its tab, so
  // Back closes it and shows the list again; it closed the app on a phone.
  @Test
  fun backOnASeriesOpenedInFrontOfItsBooksListShowsTheListAgain() {
    navigator.goTo(maid)
    val list = tabs.activeTab
    navigator.openInNewTab(book("1"))
    val route = Route.BookSeries("s1", "A series", listOf("maid"))
    navigator.goTo(route)
    val series = tabs.activeTab

    assertEquals(listOf<Any>(route), series.backStack.toList())
    // What the phone's queue Back acts on: with no opener the system Back closes the app.
    assertSame(list, tabs.openerOf(series))

    navigator.goBack()

    assertSame(list, tabs.activeTab)
    assertEquals(listOf("Books · Maid", "Book 1", "Browse"), titles())
  }

  // #479: a series opened in the background (a long-press on its row) has nothing under it and no item to close back
  // to, so Back on it acts at the queue's level as on a lone post: the previous item, or the queue page; the tab's own
  // Back closed the app. Over its list, or as a Books search alone, the tab's own Back stays.
  @Test
  fun aSeriesAloneInItsTabGoesBackLikeALonePost() {
    navigator.goTo(maid)
    val list = tabs.activeTab
    navigator.openInNewTab(Route.BookSeries("s1", "A series", listOf("maid")))
    val series = tab("A series")

    assertNull(tabs.openerOf(series))
    assertTrue(series.backsLikeALonePost)
    assertFalse(list.backsLikeALonePost)
    // The phone's queue Back on it: the previous item, or on the first item the queue page; never the tab's own Back.
    assertEquals(QueueBack.Previous, queueBack(cardUp = false, lonePost = series.backsLikeALonePost, previous = true))
    assertEquals(QueueBack.QueuePage, queueBack(cardUp = false, lonePost = series.backsLikeALonePost, previous = false))

    // Back (desktop, and the top bar's ←) goes to the previous item too.
    tabs.activate(series.id)
    val before = tabs.tabs.value[tabs.tabs.value.indexOf(series) - 1]
    navigator.goBack()

    assertSame(before, tabs.activeTab)

    // A series over its list is popped by the tab's own Back.
    tabs.activate(list.id)
    navigator.goTo(Route.BookSeries("s2", "Another series", listOf("maid")))

    assertFalse(list.backsLikeALonePost)
  }

  // #479: from a kept book's sheet the series opens in front of the book, and Back goes back to the book.
  @Test
  fun backOnASeriesOpenedFromAKeptBookShowsTheBookAgain() {
    navigator.goTo(maid)
    navigator.goTo(book("1"))
    val kept = tabs.activeTab
    navigator.goTo(Route.BookSeries("s1", "A series"))
    val series = tabs.activeTab

    assertSame(kept, tabs.openerOf(series))

    navigator.goBack()

    assertSame(kept, tabs.activeTab)
  }

  @Test
  fun theSameBooksSearchOnItsOwnSourceRunsInItEvenWithKeptBooks() {
    navigator.goTo(Route.Books(listOf("maid", "nurse")))
    navigator.openInNewTab(book("1"))

    navigator.goTo(Route.Books(listOf("nurse", "maid", "order:date")))

    assertEquals(1, tabs.tabs.value.count { it.top is Route.Books })
    assertEquals(Route.Books(listOf("nurse", "maid", "order:date")), tabs.activeTab.top)
    assertSame(tabs.activeTab, tabs.sourceOf(tab("Book 1")))
  }

  @Test
  fun aSeriesOnTopOfABooksTabStillHoldsItsSearch() {
    navigator.goTo(maid)
    val books = tabs.activeTab
    navigator.goTo(Route.BookSeries("s1", "A series", listOf("maid")))
    navigator.goTo(Route.Grid(listOf("artist_x")))

    navigator.goTo(maid)

    assertSame(books, tabs.activeTab)
    assertEquals(1, tabs.tabs.value.count { it.backStack.any { r -> r is Route.Books } })

    // Searched again from the series, it goes back down to the search under it, not a copy over it.
    navigator.goTo(maid)

    assertEquals(listOf<Any>(maid), books.backStack.toList())
  }

  @Test
  fun aKeptBookIsWhatOpensTheQueuePageAtAStart() {
    assertFalse(QueuePageAtStart.opens(listOf(QueueKind.Source)))
    assertTrue(QueuePageAtStart.opens(listOf(QueueKind.Source, QueueKind.KeptBook)))
  }

  @Test
  fun aKeptBookIsNamedAndDescribedAsABook() {
    navigator.goTo(maid)
    navigator.openInNewTab(Route.Book("1"))
    val kept = tabs.tabs.value.single { it.top is Route.Book }

    assertEquals(BOOK_TITLE, kept.title)
    assertEquals("Kept book · from Books · Maid", kindLabel(kept, tabs.sourceOf(kept)))
    assertEquals("Kept book from Books · Maid", queueSubtitle(kept, current = false, seen = false, rating = null, from = "Books · Maid"))
    // A book whose rating isn't known is blurred and hidden as a post's is.
    assertTrue(isAdult(kept, null))
    assertFalse(isAdult(tab("Books · Maid"), null))
  }
}
