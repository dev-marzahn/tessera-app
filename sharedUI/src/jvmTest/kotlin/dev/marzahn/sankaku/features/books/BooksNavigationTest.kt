package dev.marzahn.sankaku.features.books

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.shell.opensSearch
import dev.marzahn.sankaku.shell.searchRoute
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.booksTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #380: how the Books screens open, go back, search and save. */
class BooksNavigationTest {
  private val tabs = TabsController()
  private val navigator = TabsNavigator(tabs)

  @Test
  fun booksOpensInFrontOfTheItemAndBackShowsItAgain() {
    val browse = tabs.activeTab
    navigator.goTo(Route.Books())

    assertEquals(Route.Books(), tabs.activeTab.top)
    // A queue source like a grid's search (#385).
    assertEquals(QueueKind.Source, tabs.activeTab.kind)
    assertEquals(listOf(Route.Books(), Route.Grid(emptyList())), tabs.tabs.value.map { it.top })

    navigator.goBack()
    assertEquals(browse, tabs.activeTab)
  }

  @Test
  fun aSeriesAndAnotherBooksSearchStackInTheBooksTab() {
    navigator.goTo(Route.Books())
    val books = tabs.activeTab
    val series = Route.BookSeries("s1", "A series")
    navigator.goTo(series)
    navigator.goTo(Route.Books(listOf("maid")))

    assertEquals(books, tabs.activeTab)
    assertEquals(listOf(Route.Books(), series, Route.Books(listOf("maid"))), books.backStack.toList())
    navigator.goBack()
    navigator.goBack()
    assertEquals(Route.Books(), tabs.activeTab.top)
  }

  @Test
  fun aBooksTabIsSavedAsATabRoute() {
    navigator.goTo(Route.Books())
    navigator.goTo(Route.BookSeries("s1", "A series"))

    // #385: Books is a tab route, saved with its back stack and shown again at launch.
    val saved = tabs.saved()
    assertEquals(listOf(listOf(Route.Books(), Route.BookSeries("s1", "A series")), listOf(Route.Grid(emptyList()))), saved.tabs.map { it.routes })
    assertEquals(0, saved.activeIndex)
  }

  @Test
  fun aSearchOverBooksStaysABooksSearch() {
    val books = Route.Books(listOf("order:date", "english_language"))
    assertEquals(
      Route.Books(listOf("order:date", "maid", "english_language")),
      searchRoute(listOf("order:date", "english_language", "maid"), books),
    )
    assertEquals(Route.Grid(listOf("maid")), searchRoute(listOf("maid"), Route.Grid(emptyList())))
    // A series' own search opens the Books list it came from.
    assertTrue(opensSearch(listOf("maid"), listOf("maid"), Route.BookSeries("s1", "x", listOf("maid"))))
  }

  @Test
  fun titlesNameTheSearchedTags() {
    assertEquals("Books", booksTitle(emptyList()))
    assertEquals("Books", booksTitle(listOf("order:date", "english_language")))
    assertEquals("Books · Maid", booksTitle(listOf("maid", "english_language")))
  }
}
