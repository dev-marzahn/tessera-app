package dev.marzahn.sankaku.di

import coil3.ImageLoader
import dev.marzahn.sankaku.CACHE_DIR
import dev.marzahn.sankaku.KNOWN_TAGS_FILE
import dev.marzahn.sankaku.PRIVACY_FILE
import dev.marzahn.sankaku.FIRST_LAUNCH_FILE
import dev.marzahn.sankaku.data.FirstLaunch
import dev.marzahn.sankaku.ACCESSIBILITY_FILE
import dev.marzahn.sankaku.data.AccessibilitySettings
import dev.marzahn.sankaku.QUEUE_FILE
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.SEEN_FILE
import dev.marzahn.sankaku.UPDATES_FILE
import dev.marzahn.sankaku.update.UpdateCheck
import dev.marzahn.sankaku.update.createGitHubClient
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.DebugFlags
import dev.marzahn.sankaku.platformEnvironment
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.UsersApi
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createEngine
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.shell.BackOverrides
import dev.marzahn.sankaku.shell.GridRefresh
import dev.marzahn.sankaku.data.TagsRepository
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

  single { PostStateStore() }
  single { PostsRepository(get(), get(), get(), get()) }
  single { TagsRepository(get(), get(KNOWN_TAGS_FILE)) }
  single { RecentSearches() }
  // The debug `safeOnly` launch flag is the G only switch held on for the run, so both lock ratings through one path.
  single { PrivacySettings(get(PRIVACY_FILE), get<DebugFlags>().safeOnly) }
  single { FirstLaunch(get(FIRST_LAUNCH_FILE)) }
  single { AccessibilitySettings(get(ACCESSIBILITY_FILE)) }
  single { QueueSettings(get(QUEUE_FILE)) }
  // At start, so it prunes at launch and hears every sign-out (the auth state is restored above).
  single(createdAtStart = true) { SeenStore(get(SEEN_FILE), get(), get()).also { it.clearOnSignOut(get(), get()) } }
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
