package dev.marzahn.sankaku

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import dev.marzahn.sankaku.ui.components.Shortcuts
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import com.github.javakeyring.Keyring
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.KeyringTokenStore
import dev.marzahn.sankaku.auth.TokenStorageStatus
import dev.marzahn.sankaku.auth.TokenStore
import dev.marzahn.sankaku.data.AppDataMigration
import dev.marzahn.sankaku.data.DesktopFolders
import dev.marzahn.sankaku.data.DiskTextFile
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PostSizes
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.features.post.DirectorySaveFolder
import dev.marzahn.sankaku.features.post.SaveFolder
import dev.marzahn.sankaku.log.AppEnvironment
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.net.InterfaceWatcher
import dev.marzahn.sankaku.tabs.FileTabStateStore
import dev.marzahn.sankaku.tabs.TabStateStore
import org.koin.core.module.Module
import okio.Path.Companion.toOkioPath
import org.koin.dsl.module
import java.awt.Desktop
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

private val folders = DesktopFolders(File(System.getProperty("user.home")))
/**
 * `-Dsankaku.debug.dataDir=<dir>` moves it, so a second instance (another agent's, with its own control port) can run
 * beside the first: the single-instance lock lives here.
 */
private val dataDirOverride = System.getProperty("sankaku.debug.dataDir")?.let(::File)
val APP_DATA_DIR = dataDirOverride ?: folders.appData

/**
 * Moves the app data from before the rename to Tessera into [APP_DATA_DIR]. Called first thing in `main`, before the
 * single-instance lock or any store opens a file there. With `sankaku.debug.dataDir` it moves only from
 * `-Dsankaku.debug.oldDataDir=<dir>`, so a test run never takes the real app data.
 */
fun migrateAppData() {
  val old = if (dataDirOverride == null) {
    folders.oldAppData
  } else {
    System.getProperty("sankaku.debug.oldDataDir")?.let(::File) ?: return
  }
  AppDataMigration.run(old, APP_DATA_DIR)
}

/** Where crash capture (#208) keeps the last crash's report, in [APP_DATA_DIR]. */
fun desktopCrashReports(): CrashReports = CrashReports(DiskTextFile(File(APP_DATA_DIR, "crash.txt")))

/**
 * The keychain to keep tokens in, probed once with [create]; null keeps them in memory, and Settings warns about it.
 * Null too, without probing, for a run with its own data folder ([ownDataDir], `sankaku.debug.dataDir`, an agent's):
 * the keychain holds the owner's tokens, and macOS asks the owner to allow every read and write by an unpackaged `java`.
 */
internal fun tokenKeyring(ownDataDir: Boolean, create: () -> Keyring): Keyring? =
  if (ownDataDir) null else runCatching(create).getOrNull()

/** [crashReports] is the one `main` made first thing, whose handler records crashes. */
fun desktopPlatformModule(crashReports: CrashReports): Module = module {
  val keyring = tokenKeyring(ownDataDir = dataDirOverride != null) { Keyring.create() }
  single<TokenStore> { keyring?.let(::KeyringTokenStore) ?: InMemoryTokenStore() }
  single { if (keyring != null) TokenStorageStatus.Keychain else TokenStorageStatus.InMemory }
  single {
    DebugFlags(
      sample = System.getProperty("sankaku.sample").toBoolean(),
      expireAccessToken = System.getProperty("sankaku.debug.expireAccessToken").toBoolean(),
      expireSession = System.getProperty("sankaku.debug.expireSession").toBoolean(),
      safeOnly = System.getProperty("sankaku.debug.safeOnly").toBoolean(),
      // Only outside the packaged app (jpackage sets `jpackage.app-path`), so a release can't crash on purpose.
      crash = if (System.getProperty("jpackage.app-path") == null) DebugCrash.parse(System.getProperty("sankaku.debug.crash")) else null,
      // Unpackaged runs only, as crash.
      openBook = if (System.getProperty("jpackage.app-path") == null) System.getProperty("sankaku.debug.openBook")?.trim()?.takeIf { it.isNotEmpty() } else null,
    )
  }
  single { crashReports }
  single(CACHE_DIR) { folders.cache.toOkioPath() }
  single<TextFile>(KNOWN_TAGS_FILE) { DiskTextFile(File(APP_DATA_DIR, "tags.json")) }
  single<TextFile>(PLAYER_FILE) { DiskTextFile(File(APP_DATA_DIR, "player.json")) }
  single<TextFile>(REOPEN_HINT_FILE) { DiskTextFile(File(APP_DATA_DIR, "reopen-hint.json")) }
  single<TextFile>(PRIVACY_FILE) { DiskTextFile(File(APP_DATA_DIR, "privacy.json")) }
  single<TextFile>(FIRST_LAUNCH_FILE) { DiskTextFile(File(APP_DATA_DIR, "first-launch.json")) }
  // Desktop has no queue buttons to show (its tab strip has them all), so there is nothing to keep.
  single<TextFile>(ACCESSIBILITY_FILE) { InMemoryTextFile() }
  single<TextFile>(QUEUE_FILE) { DiskTextFile(File(APP_DATA_DIR, "queue.json")) }
  single<TextFile>(GRID_FILE) { DiskTextFile(File(APP_DATA_DIR, "grid.json")) }
  single<TextFile>(UPDATES_FILE) { DiskTextFile(File(APP_DATA_DIR, "updates.json")) }
  // Desktop's one tip, the keep loop's (#504), shows once per install.
  single<TextFile>(TIPS_FILE) { DiskTextFile(File(APP_DATA_DIR, "tips.json")) }
  single<TextFile>(SEEN_FILE) { DiskTextFile(File(APP_DATA_DIR, "seen.json")) }
  single<TextFile>(FEED_MARKS_FILE) { DiskTextFile(File(APP_DATA_DIR, "feed-marks.json")) }
  single<TextFile>(READING_FILE) { DiskTextFile(File(APP_DATA_DIR, "readings.json")) }
  single<TextFile>(OUTBOX_FILE) { DiskTextFile(File(APP_DATA_DIR, "outbox.json")) }
  single<TabStateStore> {
    FileTabStateStore(
      DiskTextFile(File(APP_DATA_DIR, "tabs.json")),
      backup = DiskTextFile(File(APP_DATA_DIR, "tabs.v1.bak")),
      broken = DiskTextFile(File(APP_DATA_DIR, "tabs.json.broken")),
    )
  }
  single<SaveFolder> { DirectorySaveFolder(folders.downloads) }
  single<PlatformContext> { PlatformContext.INSTANCE }
  // The network coming back while the app is idle (#542): Android's callback has no desktop counterpart.
  single(createdAtStart = true) { InterfaceWatcher(get(), get()).also { it.start() } }
}

actual val platformHasTabs: Boolean = true

actual fun platformOpenUrl(url: String): OpenUrlResult = launchUrl(url) { Desktop.getDesktop().browse(it) }

/** [platformOpenUrl] with the launcher passed in, so a test can make it throw. */
internal fun launchUrl(url: String, browse: (URI) -> Unit): OpenUrlResult = try {
  browse(URI(url))
  OpenUrlResult.Opened
} catch (_: UnsupportedOperationException) {
  // No BROWSE (some Linux desktops); HeadlessException is one too.
  OpenUrlResult.Failed
} catch (_: IOException) {
  // No default browser, or it failed to launch.
  OpenUrlResult.Failed
} catch (_: SecurityException) {
  OpenUrlResult.Failed
} catch (_: URISyntaxException) {
  OpenUrlResult.Failed
}

actual fun platformShareText(text: String) = Unit

actual fun processMemoryBytes(): Long? = MacMemory.physFootprint()

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(text: String): ClipEntry = ClipEntry(StringSelection(text))

actual fun Modifier.platformGestureExclusion(): Modifier = this

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.platformBackKeys(onBack: () -> Unit): Modifier = this
  .onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Shortcuts.Back.key) {
      onBack()
      true
    } else {
      false
    }
  }
  .onPointerEvent(PointerEventType.Press) { event ->
    if (event.button == PointerButton.Back) onBack()
  }


actual val platformIsTouch: Boolean = false

actual val platformPostSizes: PostSizes = PostSizes.Desktop

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual val platformCellGap: Dp = 12.dp

actual val platformGridEdge: Dp = 12.dp

@Composable
actual fun PlatformSystemBars(hidden: Boolean, lightIcons: Boolean) = Unit

@Composable
actual fun PlatformHideInRecents(hide: Boolean) = Unit

@Composable
actual fun PlatformPredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) = Unit

actual fun platformEnvironment(): AppEnvironment {
  val osName = System.getProperty("os.name").orEmpty()
  val mac = osName.startsWith("Mac", ignoreCase = true)
  val platform = if (mac) "macOS" else osName
  return AppEnvironment(
    version = APP_VERSION,
    platform = platform,
    os = "$platform ${System.getProperty("os.version").orEmpty()}".trim(),
    device = (if (mac) MacModel.describe() else null) ?: System.getProperty("os.arch").orEmpty(),
  )
}
