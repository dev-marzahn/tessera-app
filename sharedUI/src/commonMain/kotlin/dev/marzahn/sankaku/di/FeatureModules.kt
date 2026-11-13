package dev.marzahn.sankaku.di

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.NavDisplay
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.PLAYER_FILE
import dev.marzahn.sankaku.REOPEN_HINT_FILE
import dev.marzahn.sankaku.TIPS_FILE
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.Tips
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabsNavigator
import dev.marzahn.sankaku.data.OriginalFiles
import dev.marzahn.sankaku.data.BookFavourites
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.features.books.BookActions
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.features.books.BookListScreen
import dev.marzahn.sankaku.features.books.BookListViewModel
import dev.marzahn.sankaku.features.books.BookOpener
import dev.marzahn.sankaku.features.books.BookSeriesScreen
import dev.marzahn.sankaku.features.books.BookSeriesViewModel
import dev.marzahn.sankaku.features.books.ContinueReadingViewModel
import dev.marzahn.sankaku.features.books.BookReader
import dev.marzahn.sankaku.features.books.BookReaderViewModel
import dev.marzahn.sankaku.features.books.KeptBookScreen
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.auth.SignInViewModel
import dev.marzahn.sankaku.features.settings.ClearCache
import dev.marzahn.sankaku.features.settings.ClearEverything
import dev.marzahn.sankaku.features.settings.ClearSavedTabs
import dev.marzahn.sankaku.features.post.actedOn
import dev.marzahn.sankaku.features.settings.SettingsController
import dev.marzahn.sankaku.shell.ShortcutSheetController
import dev.marzahn.sankaku.features.grid.GridCursorRequest
import dev.marzahn.sankaku.features.grid.GridScreen
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.features.grid.GridViewModel
import dev.marzahn.sankaku.features.grid.ViewerPreferences
import dev.marzahn.sankaku.features.post.PlayerPreferences
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.data.FollowedTagList
import dev.marzahn.sankaku.data.OutboxSender
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.post.PostSaver
import dev.marzahn.sankaku.features.post.PostScreen
import dev.marzahn.sankaku.features.post.PostViewModel
import dev.marzahn.sankaku.features.post.TagBlacklisting
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.tabs.NewTab
import dev.marzahn.sankaku.tabs.ClearViewed
import dev.marzahn.sankaku.tabs.ClosedTabUndo
import dev.marzahn.sankaku.tabs.CachedTabPosts
import dev.marzahn.sankaku.tabs.migrateReopenHint
import dev.marzahn.sankaku.tabs.TabPosts
import dev.marzahn.sankaku.tabs.QueueStart
import dev.marzahn.sankaku.tabs.UpNextState
import dev.marzahn.sankaku.tabs.pinnedFeeds
import dev.marzahn.sankaku.ui.Messages
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** App-wide UI state that outlives any screen, and Settings, which is a desktop tab (#205). */
@OptIn(KoinExperimentalAPI::class)
val shellModule = module {
  // Created during startKoin, outside any composition. Created first by App() (as on Android), its restored back
  // stacks were filled in the composition's snapshot, and the save's snapshotFlow, reading outside it, saved every
  // tab with no routes. Its feed items (#229) are there from the start: the auth state is restored by now.
  single(createdAtStart = true) {
    TabsController(get(), get(), seen = getOrNull(), knownTags = getOrNull<TagsRepository>()?.known, fades = platformIsTouch).also { it.pinFeeds(pinnedFeeds(getOrNull(), getOrNull())) }
  }
  single<Navigator> { TabsNavigator(get(), get()) }
  single { NewTab(get()) }
  single { Messages() }
  single { Tips(get(TIPS_FILE), touch = platformIsTouch).also { migrateReopenHint(get(REOPEN_HINT_FILE), it) } }
  single { ClosedTabUndo(get(), get()) }
  single { ClearViewed(get(), get(), get()) }
  single<TabPosts> { CachedTabPosts(get(), get()) }
  single { UpNextState(get(), get(), get()) }
  single { QueueStart(get(), get(), get(), get()) }
  single { SettingsController() }
  single { ShortcutSheetController() }
  single { ClearEverything(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
  single { ClearCache(get()) }
  single { ClearSavedTabs(get(), get()) }
}

val authModule = module {
  single { SignInController() }
  viewModel { SignInViewModel(get(), get()) }
}

@OptIn(KoinExperimentalAPI::class)
val gridModule = module {
  single { ViewerPreferences() }
  single { GridCursorRequest() }
  viewModel { p ->
    val followed = get<FollowedTagList>()
    GridViewModel(p[0], p.values.getOrNull(1) as? GridPlace, get(), get(), get(), get(), get(), get(), get(), get(), get(), followedTagIds = followed::idsOrNull)
  }
  navigation<Route.Grid> { route -> GridScreen(route) }
}

@OptIn(KoinExperimentalAPI::class)
val postModule = module {
  single { FollowedTagList(get()) }
  single { TagBlacklisting(get(), get(), get()) }
  single { PlayerPreferences(get(PLAYER_FILE)) }
  single { PostActions(get(), get(), get(), get(), get(), get(), actedOn(get(), get()), get<OutboxSender>().refusals) }
  single { OriginalFiles(get(MAIN_CLIENT), get(), get(), get()) }
  single { PostSaver(get(), get(), get(), get()) }
  viewModel { p -> PostViewModel(p.get(), get(), get(), get()) }
  // Going back from a post cuts straight to the grid: it shows the same card at once and closes it into the post's
  // cell, so a crossfade would only show the card twice.
  navigation<Route.Post>(metadata = NavDisplay.popTransitionSpec { EnterTransition.None togetherWith ExitTransition.None }) { route ->
    PostScreen(route)
  }
}

/**
 * Books (#380, #381, #382, #383): the list's repository, the list and a series' books as routes, the reader a book's row
 * opens with its list as results ([BookReader.open]) and the books' ♡ ([BookActions]). `BooksApi` is in the shared
 * module with the other APIs.
 */
@OptIn(KoinExperimentalAPI::class)
val booksModule = module {
  single { BooksRepository(get(), get(), get(), signedIn = get<AuthSession>().isSignedIn, favourites = get()) }
  single { BookFavourites(get(), get(), get()) }
  single { BookActions(get(), get(), get(), get(), get()) }
  single { BookReader(get<ReadingProgress>()::save) }
  // A post's "From the book" (#386) opens its book on its own.
  single<BookOpener> { BookOpener { book -> get<BookReader>().open(book.id, book.title) } }
  viewModel { p -> BookReaderViewModel(p.get(), get(), get()) }
  viewModel { p -> BookListViewModel(p.get(), get(), get(), get()) }
  viewModel { p -> BookSeriesViewModel(p.get(), get(), get(), get()) }
  viewModel { ContinueReadingViewModel(get(), get(), get(), get()) }
  navigation<Route.Books> { route -> BookListScreen(route) }
  navigation<Route.BookSeries> { route -> BookSeriesScreen(route) }
  // A kept book (#385): the reader as its tab's content.
  navigation<Route.Book> { route -> KeptBookScreen(route) }
}
