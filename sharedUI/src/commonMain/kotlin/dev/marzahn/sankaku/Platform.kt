package dev.marzahn.sankaku

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.unit.Dp
import dev.marzahn.sankaku.data.PostSizes
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.log.AppEnvironment
import org.koin.core.qualifier.named

/**
 * Desktop reads these from the `sankaku.sample`, `sankaku.debug.expireAccessToken`, `sankaku.debug.expireSession`,
 * `sankaku.debug.safeOnly` and `sankaku.debug.crash` system properties; a debuggable Android build reads
 * `expireAccessToken`, `expireSession`, `crash` and `safeOnly` from the launch intent, and `safeOnly` from [SAFE_ONLY_PROPERTY] too (`launchDebugFlags`). [safeOnly] is
 * the sidebar's G only switch held on for the run (`PrivacySettings.gOnly`, never saved): every grid search is sent
 * with `rating:s`, for test runs and screenshots. [expireSession] signs out at launch as a rejected refresh does, showing "Session expired".
 */
data class DebugFlags(
  val sample: Boolean = false,
  val expireAccessToken: Boolean = false,
  val safeOnly: Boolean = false,
  val expireSession: Boolean = false,
  /** Crash 3 s after the shell shows, to check crash capture (#208). */
  val crash: DebugCrash? = null,
  /**
   * A book to open in the reader at launch (#381), to try it without the Books list: desktop's `sankaku.debug.openBook`
   * property, a debuggable Android build's `openBook` string extra.
   */
  val openBook: String? = null,
)

/** Where [DebugFlags.crash] throws: from composition on the UI thread, or from a background thread. */
enum class DebugCrash {
  Ui,
  Thread,
  ;

  companion object {
    /** `ui` or `thread`, in any case; anything else is no crash. */
    fun parse(value: String?): DebugCrash? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
  }
}

/**
 * The Android system property (`adb shell setprop debug.tessera.safeOnly true`) that turns on [DebugFlags.safeOnly]
 * in a debuggable build. Unlike the launch extra it's set before installing, so it also holds for the launch Android
 * 17 makes by itself right after `adb install -r` replaces the app on screen, which carries no extras (#97).
 */
const val SAFE_ONLY_PROPERTY = "debug.tessera.safeOnly"

/**
 * Whether a debuggable Android run is G only: its launch [extra] or [SAFE_ONLY_PROPERTY]'s [property] value asks.
 * The value counts as on only when it's `true` (any case), as desktop's `sankaku.debug.safeOnly` does.
 */
fun safeOnlyRequested(extra: Boolean, property: String?): Boolean = extra || property?.trim().toBoolean()

/** The platform cache folder (an `okio.Path`); the image disk cache lives in its `images` folder. */
val CACHE_DIR = named("cacheDir")

/** The `TextFile` that keeps the tags the app has learnt (`TagsRepository`) between runs. */
val KNOWN_TAGS_FILE = named("knownTagsFile")

/** The `TextFile` that keeps the video player's volume and mute (`PlayerPreferences`) between runs. */
val PLAYER_FILE = named("playerFile")

/** The `TextFile` that keeps the accessibility switches (`AccessibilitySettings`): Show queue buttons. */
val ACCESSIBILITY_FILE = named("accessibilityFile")

/** The `TextFile` that keeps the queue's settings (`QueueSettings`): Show For you in the queue. */
val QUEUE_FILE = named("queueFile")

/** The `TextFile` that keeps the grid's settings (`GridSettings`): Post size. `grid.json`. */
val GRID_FILE = named("gridFile")

/**
 * The `TextFile` that counted, for the old top-line hint, the holds on the count pill that reopened an item: read once
 * into the tips when they're first needed, then deleted (`migrateReopenHint`, #344).
 */
val REOPEN_HINT_FILE = named("reopenHintFile")

/** The `TextFile` that keeps the privacy switches (`PrivacySettings`): Hide in Recents and G only. */
val PRIVACY_FILE = named("privacyFile")

/**
 * The `TextFile` that keeps the update check (`UpdateCheck`, #209): its switch, when GitHub last answered and the
 * newest release it named. `updates.json`.
 */
val UPDATES_FILE = named("updatesFile")

/** The `TextFile` that keeps the posts the user has seen (`SeenStore`): `seen.json`, apart from `tabs.json`. */
val SEEN_FILE = named("seenFile")

/**
 * The `TextFile` that keeps the feeds' marks (`FeedMarksStore`, #314): `feed-marks.json`. Optional: without it (tests)
 * the marks last the run.
 */
val FEED_MARKS_FILE = named("feedMarksFile")

/** The `TextFile` that keeps where each book was left while signed out (`ReadingProgress`, #384): `readings.json`. */
val READING_FILE = named("readingFile")

/** The `TextFile` that keeps the favourites, votes and follows waiting to be sent (`Outbox`, #505): `outbox.json`. */
val OUTBOX_FILE = named("outboxFile")

/** The `TextFile` that keeps which one-time tips (`Tips`) are done. Apart from the saved tabs, so clearing them leaves it. */
val TIPS_FILE = named("tipsFile")

/** The `TextFile` that keeps whether the first-launch screen was accepted (`FirstLaunch`): `first-launch.json`. */
val FIRST_LAUNCH_FILE = named("firstLaunchFile")

/**
 * Both platforms have tabs (`docs/adr/0001-tabs-replace-later.md`): desktop in the tab strip, touch ([platformIsTouch])
 * on the queue page, with the count pill at the bottom (#178).
 */
expect val platformHasTabs: Boolean

/** Whether [platformOpenUrl] handed the link to an app. */
enum class OpenUrlResult { Opened, Failed }

/**
 * Opens [url] in the app the system picks for it. Never throws: [OpenUrlResult.Failed] when no app can open it (no
 * browser on Android, no `BROWSE` on a Linux desktop, or a failed launch), so the caller can say so (#358).
 */
expect fun platformOpenUrl(url: String): OpenUrlResult

/** What the user sees when [platformOpenUrl] fails. No URL: it may hold a post id or a query. */
const val NoAppForLink = "No app can open this link"

/** Opens [url] with [open], or shows [NoAppForLink] as an error if nothing could. */
fun Messages.openUrl(url: String, open: (String) -> OpenUrlResult = ::platformOpenUrl) {
  if (open(url) == OpenUrlResult.Failed) showError(NoAppForLink)
}

/** Android: the system share sheet with [text]. Desktop has no share sheet, so nothing offers Share there. */
expect fun platformShareText(text: String)

/** [text] as plain text for `LocalClipboard`. */
expect fun plainTextClip(text: String): ClipEntry

/**
 * The whole app's memory as Activity Monitor's Memory column counts it (the process's physical footprint, native
 * image memory and graphics included), or null where it can't be read. Android returns null.
 */
expect fun processMemoryBytes(): Long?

/**
 * Desktop: Esc and the mouse back button call [onBack]. Esc arrives after focused children (the viewer, dialogs,
 * text fields) had their chance. Android leaves back to the system.
 */
expect fun Modifier.platformBackKeys(onBack: () -> Unit): Modifier

/**
 * Android: the system leaves touches here to the app, though they start in its back gesture's edge (a scrubber's thumb
 * at the screen's right end). Desktop has no such edge.
 */
expect fun Modifier.platformGestureExclusion(): Modifier

/** Desktop: a draggable scrollbar for the grid, over its [postCount] loaded posts. Android has no scrollbar. */
@Composable
expect fun GridScrollbar(state: LazyStaggeredGridState, postCount: Int, modifier: Modifier = Modifier)

/** The room [GridScrollbar] takes at the grid's end edge; none on Android. */
expect val gridScrollbarRoom: Dp

/**
 * Android: system and predictive back go back, so the top bar has no Back button, and a grid refreshes when pulled
 * down, so it has no Refresh button either. Desktop shows both. With no middle click, a long-press opens in a
 * background tab (`openOnClick`), and the tabs are worked through with sideways swipes and the count pill at the bottom
 * instead of the strip.
 */
expect val platformIsTouch: Boolean

/**
 * The sizes Settings → Grid → Post size offers, and its default: desktop's `PostSizes.Desktop`, Android's
 * `PostSizes.Phone`. Read the grid's narrowest column through `gridMinCellWidth`.
 */
expect val platformPostSizes: PostSizes

/**
 * Android: while [enabled], system back (and predictive back) calls [onBack] instead of going back in the tab. The
 * newest enabled one wins, so one inside a screen outranks the tab's own back. Desktop: nothing (Esc and the top
 * bar's Back go through `BackOverrides`).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * The gap between grid cells: 12 dp on desktop, room for the keyboard cursor's ring with 5 dp to spare (#459); 4 dp on
 * Android (as the approved Android mockups draw it), which has no cursor.
 */
expect val platformCellGap: Dp

/** The room between the grid's cells and the window's sides: the gap on desktop (#459), 6 dp on Android (the mockups). */
expect val platformGridEdge: Dp

/**
 * Android: while composed with [hidden], the status and navigation bars are hidden (a swipe from an edge shows them
 * for a moment); [lightIcons] draws the status bar's icons light, for a dark screen under them. With several composed
 * (a swipe between lone posts), the one composed last wins; once the last leaves, the app's own look comes back
 * ([SystemBarRequests]). Desktop: nothing.
 */
@Composable
expect fun PlatformSystemBars(hidden: Boolean, lightIcons: Boolean)

/**
 * Android: while composed with [hide], the app switcher (Recents) shows a blank preview of the app instead of its last
 * screen. Android 13+ turns off the Recents screenshot only; Android 12 has just `FLAG_SECURE`, which blocks screenshots
 * and recordings too. Desktop has no Recents: nothing.
 */
@Composable
expect fun PlatformHideInRecents(hide: Boolean)

/**
 * Android: while [enabled], system back is predictive: [onProgress] follows the gesture from 0 to 1, then [onBack]
 * runs when it's committed or [onCancel] when it's let go. A plain back press calls [onBack] alone. It outranks the
 * back handlers composed before it, as [PlatformBackHandler] does. Desktop: nothing.
 */
@Composable
expect fun PlatformPredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit)

/**
 * Where this run is, for Copy log and Report a bug (#207): [APP_VERSION], the platform as the bug form names it
 * (`Android`, `macOS`), the OS and its version, and the device model. Android: `Android 16` and the maker and model.
 * Desktop: `macOS 26.1` and the Mac's model and chip (`Mac15,12 (Apple M3)`).
 */
expect fun platformEnvironment(): AppEnvironment
