package dev.marzahn.sankaku.di

import coil3.ImageLoader
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.data.FirstLaunch
import dev.marzahn.sankaku.features.firstlaunch.QueueIntro
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.GRID_FILE
import dev.marzahn.sankaku.data.GridSettings
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.OUTBOX_FILE
import dev.marzahn.sankaku.READING_FILE
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.update.createGitHubClient
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.FEED_MARKS_FILE
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.queue.FeedMarksStore
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.platformEnvironment
import dev.marzahn.sankaku.platformPostSizes
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.BlacklistApi
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.UsersApi
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createEngine
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.data.Outbox
import dev.marzahn.sankaku.data.OutboxSender
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.TagFollows
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.shell.BackOverrides
import dev.marzahn.sankaku.shell.GridRefresh
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.tag.TagPages
import dev.marzahn.sankaku.images.createImageLoader
import dev.marzahn.sankaku.log.Diagnostics
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.Path
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.TimeSource

val AUTH_CLIENT = named("authClient")
val MAIN_CLIENT = named("mainClient")
val GITHUB_CLIENT = named("githubClient")

/** Singles in construction order: nothing depends on something built after it. Feature modules arrive per milestone. */
val sharedModule = module {
  single<Clock> { Clock.System }
  single<TimeSource.WithComparableMarks> { TimeSource.Monotonic }
  single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
  single { ConnectivityState() }
  single { RequestLog(get()) }
  single { Diagnostics(get(), platformEnvironment()) }
  single { RateLimiter(get(), RateLimiter.DEFAULT_LIMITS, get()) }
  single { createEngine() }

  single<HttpClient>(AUTH_CLIENT) { createAuthClient(get(), get(), get(), get()) }
  single { AuthApi(get(AUTH_CLIENT), get()) }
  // restore() runs during startKoin, off the UI thread, so the auth state is settled before App() composes.
  single(createdAtStart = true) {
    AuthSession(get(), get(), get(), get<DebugFlags>().expireAccessToken, get<DebugFlags>().expireSession)
      .also { runBlocking { it.restore() } }
  }
  single<HttpClient>(MAIN_CLIENT) { createMainClient(get(), get(), get(), get(), get()) }

  single { PostsApi(get(MAIN_CLIENT)) }
  single { UsersApi(get(MAIN_CLIENT)) }
  single { TagsApi(get(MAIN_CLIENT)) }
  single { BooksApi(get(MAIN_CLIENT)) }
  single { BlacklistApi(get(MAIN_CLIENT)) }
  // Here rather than in the Books module: Clear everything clears it too (#384).
  single { ReadingProgress(get(), get(), get(), get(READING_FILE), get()) }

  single { PostStateStore() }
  // What was favourited, voted or followed while Sankaku couldn't be reached, for the signed-in account (#505).
  single { Outbox(get(OUTBOX_FILE)) { (get<AuthSession>().state.value as? AuthState.SignedIn)?.user?.id } }
  single { PostsRepository(get(), get(), get(), get(), get(), get()) }
  single { TagFollows(get(), get()) }
  // At start, so what waited before a restart is sent at launch.
  single(createdAtStart = true) { OutboxSender(get(), get(), get(), get(), get(), get()).also { it.start() } }
  single { TagsRepository(get(), get(KNOWN_TAGS_FILE)) }
  // A tag's page (#486), over the window: opened from the book sheet and the tag menu.
  single { TagPages(getOrNull()) }
  single { RecentSearches() }
  // The debug `safeOnly` launch flag is the G only switch held on for the run, so both lock ratings through one path.
  single { PrivacySettings(get(PRIVACY_FILE), get<DebugFlags>().safeOnly) }
  single { FirstLaunch(get(FIRST_LAUNCH_FILE)) }
  // The "Your queue" page (#559): after the first-launch screen, and from Settings → Queue.
  single { QueueIntro() }
  single { AccessibilitySettings(get(ACCESSIBILITY_FILE)) }
  single { QueueSettings(get(QUEUE_FILE)) }
  single { GridSettings(get(GRID_FILE), platformPostSizes) }
  // At start, so it prunes at launch and hears every sign-out (the auth state is restored above).
  single(createdAtStart = true) { SeenStore(get(SEEN_FILE), get(), get()).also { it.clearOnSignOut(get(), get()) } }
  // At start too, so it hears every sign-out.
  single(createdAtStart = true) {
    FeedMarksStore(getOrNull(FEED_MARKS_FILE) ?: InMemoryTextFile(), get(), get()).also { it.clearOnSignOut(get(), get()) }
  }
  // Its own client on the shared engine: none of Sankaku's plugins, headers, tokens or fingerprint (#209). It checks
  // once the first-launch screen is accepted, so nothing goes out before it (#210).
  single<HttpClient>(GITHUB_CLIENT) { createGitHubClient(get()) }
  single(createdAtStart = true) {
    UpdateCheck(get(UPDATES_FILE), get(GITHUB_CLIENT), get()).also { it.start(get(), get<FirstLaunch>().accepted) }
  }
  single { BackOverrides() }
  single { GridRefresh() }

  single<ImageLoader> {
    createImageLoader(get(), get(MAIN_CLIENT), get(), get(), get(), get<Path>(CACHE_DIR) / "images")
  }
}
