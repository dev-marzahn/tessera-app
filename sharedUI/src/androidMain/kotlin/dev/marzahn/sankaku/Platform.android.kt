package dev.marzahn.sankaku

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.marzahn.sankaku.auth.TinkTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.DiskTextFile
import dev.marzahn.sankaku.data.PostSizes
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.log.AppEnvironment
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.features.post.MediaStoreSaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.net.DefaultNetworkCallback
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import okio.Path.Companion.toOkioPath
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * A debuggable build takes [DebugFlags] from the launch intent's boolean extras `safeOnly`, `expireAccessToken` and
 * `expireSession`
 * (`adb shell am start … --ez safeOnly true`), the string extras `crash` and `openBook`, and `safeOnly` also from [SAFE_ONLY_PROPERTY]; a release build always
 * uses the defaults.
 */
fun launchDebugFlags(context: Context, intent: Intent): DebugFlags =
  if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
    DebugFlags()
  } else {
    DebugFlags(
      expireAccessToken = intent.getBooleanExtra("expireAccessToken", false),
      expireSession = intent.getBooleanExtra("expireSession", false),
      safeOnly = safeOnlyRequested(intent.getBooleanExtra("safeOnly", false), systemProperty(SAFE_ONLY_PROPERTY)),
      crash = DebugCrash.parse(intent.getStringExtra("crash")),
      openBook = intent.getStringExtra("openBook")?.trim()?.takeIf { it.isNotEmpty() },
    )
  }

/**
 * [name]'s value through `getprop` (apps have no public API for it), or null if it can't be read within a second, so
 * a stuck `getprop` can't hang app start. Debug builds only. Its output is one short line, well under a pipe's buffer,
 * so it can finish before it's read.
 */
private fun systemProperty(name: String): String? = runCatching {
  val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
  try {
    if (!process.waitFor(1, TimeUnit.SECONDS)) return@runCatching null
    process.inputStream.bufferedReader().use { it.readText() }
  } finally {
    process.destroy()
  }
}.getOrNull()

/** Where crash capture (#208) keeps the last crash's report, in the app's files folder. */
fun androidCrashReports(context: Context): CrashReports =
  CrashReports(DiskTextFile(java.io.File(context.filesDir, "crash.txt")))

/**
 * Uses the `Context` that `SankakuApplication` registers with `androidContext()`. [crashReports] is the one
 * `SankakuApplication` made at start, whose handler records crashes.
 */
fun androidPlatformModule(flags: DebugFlags, crashReports: CrashReports): Module = module {
  single<TokenStore> { TinkTokenStore.create(androidContext()) }
  single { TokenStorageStatus.Encrypted }
  single { flags }
  single { crashReports }
  single(CACHE_DIR) { androidContext().cacheDir.toOkioPath() }
  single<TextFile>(KNOWN_TAGS_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "tags.json")) }
  single<TextFile>(PLAYER_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "player.json")) }
  single<TextFile>(REOPEN_HINT_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "reopen-hint.json")) }
  single<TextFile>(PRIVACY_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "privacy.json")) }
  single<TextFile>(FIRST_LAUNCH_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "first-launch.json")) }
  single<TextFile>(ACCESSIBILITY_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "accessibility.json")) }
  single<TextFile>(QUEUE_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "queue.json")) }
  single<TextFile>(GRID_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "grid.json")) }
  single<TextFile>(UPDATES_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "updates.json")) }
  single<TextFile>(TIPS_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "tips.json")) }
  single<TextFile>(SEEN_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "seen.json")) }
  single<TextFile>(FEED_MARKS_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "feed-marks.json")) }
  single<TextFile>(READING_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "readings.json")) }
  single<TextFile>(OUTBOX_FILE) { DiskTextFile(java.io.File(androidContext().filesDir, "outbox.json")) }
  single<TabStateStore> {
    val files = androidContext().filesDir
    FileTabStateStore(
      DiskTextFile(java.io.File(files, "tabs.json")),
      backup = DiskTextFile(java.io.File(files, "tabs.v1.bak")),
      broken = DiskTextFile(java.io.File(files, "tabs.json.broken")),
    )
  }
  single<SaveFolder> { MediaStoreSaveFolder(androidContext()) }
  // Created with Koin, so the app hears the network come back from the start.
  single(createdAtStart = true) { DefaultNetworkCallback.register(androidContext(), get()) }
  // No PlatformContext definition: it's Coil's alias for Context here, which androidContext() already declares, and
  // one that calls androidContext() would resolve itself.
}

actual val platformHasTabs: Boolean = true

actual fun platformOpenUrl(url: String): OpenUrlResult {
  val context = KoinPlatform.getKoin().get<Context>()
  return launchUrl(url, context::startActivity)
}

/** [platformOpenUrl] with the launcher passed in, so a test can make it throw. */
internal fun launchUrl(url: String, start: (Intent) -> Unit): OpenUrlResult = try {
  val intent = Intent(Intent.ACTION_VIEW, url.toUri())
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  start(intent)
  OpenUrlResult.Opened
} catch (_: ActivityNotFoundException) {
  // No browser installed, or a work profile without one.
  OpenUrlResult.Failed
} catch (_: SecurityException) {
  // The only app for it doesn't let others start it.
  OpenUrlResult.Failed
}

actual fun platformShareText(text: String) {
  val context = KoinPlatform.getKoin().get<Context>()
  val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
  context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(text, text))

actual fun processMemoryBytes(): Long? = null

actual fun Modifier.platformBackKeys(onBack: () -> Unit): Modifier = this

actual fun Modifier.platformGestureExclusion(): Modifier = systemGestureExclusion()

@Composable
actual fun GridScrollbar(state: LazyStaggeredGridState, postCount: Int, modifier: Modifier) = Unit

actual val gridScrollbarRoom: Dp = 0.dp

actual val platformIsTouch: Boolean = true

actual val platformPostSizes: PostSizes = PostSizes.Phone

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled = enabled, onBack = onBack)

actual val platformCellGap: Dp = 4.dp

actual val platformGridEdge: Dp = 6.dp

@Composable
actual fun PlatformSystemBars(hidden: Boolean, lightIcons: Boolean) {
  val window = LocalView.current.context.findActivity()?.window ?: return
  val requests = remember(window) { window.barRequests() }
  // The app's own icon colour, restored once no viewer asks: dark icons over a light theme. Read from the theme rather
  // than the window, which a viewer has changed.
  val appLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
  SystemBarRequest(requests, SystemBarsLook(hidden = hidden, lightIcons = lightIcons, appLight = appLight))
}

/** What one [PlatformSystemBars] asks of its window. */
private data class SystemBarsLook(val hidden: Boolean, val lightIcons: Boolean, val appLight: Boolean)

// One stack per window, so a viewer that leaves after the next one has asked doesn't undo it (#297). A stack reaches its
// window only weakly: a strong reference from the value would keep the key, and a recreated activity's window, forever.
private val windowBarRequests = WeakHashMap<Window, SystemBarRequests<SystemBarsLook>>()

private fun Window.barRequests(): SystemBarRequests<SystemBarsLook> = windowBarRequests.getOrPut(this) {
  val window = WeakReference(this)
  fun controller() = window.get()?.let { WindowCompat.getInsetsController(it, it.decorView) }
  val bars = WindowInsetsCompat.Type.systemBars()
  lateinit var requests: SystemBarRequests<SystemBarsLook>
  requests = SystemBarRequests(
    apply = { look ->
      controller()?.run {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (look.hidden) hide(bars) else show(bars)
        isAppearanceLightStatusBars = !look.lightIcons
      }
    },
    // After this frame's changes: a viewer that leaves before the next one asks, in the same frame, doesn't show the
    // bars and the app's icons for a moment.
    restore = { look ->
      window.get()?.decorView?.post {
        if (requests.isEmpty) {
          controller()?.run {
            show(bars)
            isAppearanceLightStatusBars = look.appLight
          }
        }
      }
    },
  )
  requests
}

@Composable
actual fun PlatformHideInRecents(hide: Boolean) {
  val activity = LocalView.current.context.findActivity() ?: return
  DisposableEffect(activity, hide) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      activity.setRecentsScreenshotEnabled(!hide)
    } else if (hide) {
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    onDispose {}
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

@Composable
actual fun PlatformPredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
  val progress by rememberUpdatedState(onProgress)
  val cancel by rememberUpdatedState(onCancel)
  val back by rememberUpdatedState(onBack)
  PredictiveBackHandler(enabled = enabled) { events ->
    try {
      events.collect { progress(it.progress) }
      back()
    } catch (e: CancellationException) {
      cancel()
      throw e
    }
  }
}

actual fun platformEnvironment(): AppEnvironment = AppEnvironment(
  version = APP_VERSION,
  platform = "Android",
  os = "Android ${Build.VERSION.RELEASE}",
  device = androidDevice(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
)

/** `Pixel 9` as `Google Pixel 9`; a model that already names its maker (`Nokia 8`) isn't prefixed twice. */
internal fun androidDevice(manufacturer: String, model: String): String = when {
  manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
  else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
}
