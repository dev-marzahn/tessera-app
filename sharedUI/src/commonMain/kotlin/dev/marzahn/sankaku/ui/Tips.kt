package dev.marzahn.sankaku.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.data.TextFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A just-in-time tip (#140): shown once per install, at the moment its gesture is useful, on a phone or ([touch]
 * false) on desktop only.
 */
enum class Tip(val text: String, val touch: Boolean = true) {
  /** On the cell the viewer closed into, the first time it closes back to a grid. */
  GridGestures("Long-press a post to keep it."),

  /**
   * Desktop's [GridGestures] (#504): on the cell the viewer closed into, the first time it closes back to a grid, the
   * keep-and-work-through loop.
   */
  KeepLoop("Middle-click or E keeps a post for later · X closes it and moves on.", touch = false),

  /**
   * On the count pill (#501), the first time there's a next item to swipe to, a queue restored at launch too; before
   * [TabList]. Done once shown, or once the user swipes forward.
   */
  QueueSwipe("Swipe sideways for the next item in your queue."),

  /** On the count pill (#178), the first time a second item appears. */
  TabList("Tap the count to see your whole queue."),

  /**
   * On the count pill (#344, replacing the top line's hint after every close), once a close's Undo has gone with an
   * item still closed: the hold is then the way back.
   */
  ReopenHold("Hold the queue count to reopen closed items any time."),

  /**
   * Over the phone viewer's picture (#527), the first time the sheet's ♡ favourites a post there
   * ([offerLongPressTip]). Done once shown, or once the user long-presses the picture.
   */
  LongPressFavourite("Long-press the picture to favourite."),

  /**
   * On the count pill (#559), the first time a search opened in front of an item shows (a tag tapped on a post, Search
   * only this: a grid Back closes and returns from, `TabsController.openerOf`), before [QueueSwipe]: what the tag tap
   * did, where Back goes, and that finishing it goes there too. Shown with the search's name ([openedInFrontText]) and
   * Show queue.
   */
  OpenedInFront("Searches you open from a post open in front of where you were. Back takes you there, and so does finishing them."),

  /**
   * On the count pill (#559), the first time the item shown is a source with kept posts ("Grass · 1 kept"): where they
   * went. Shown with the source's name ([keptWaitsText]).
   */
  KeptWaits("Kept posts wait right after their search. Swipe sideways to go through them."),

  /**
   * On the top line (#559), the first time moving on past a block lands on the item its search was opened from (Way
   * back): that this is where the user left off. Shown with the block's name ([wayBackText]).
   */
  WayBack("Back where you opened that search. It stays in your queue."),
}

/** [Tip.OpenedInFront] for the search [title] shown. */
fun openedInFrontText(title: String): String =
  "$title opened in front of where you were. Back takes you there, and so does finishing $title."

/** [Tip.KeptWaits] for the source [source], whose kept items are books ([books]) or posts. */
fun keptWaitsText(source: String, books: Boolean = false): String =
  "Kept ${if (books) "books" else "posts"} wait right after $source. Swipe sideways to go through them."

/** [Tip.WayBack] for the block [source] just left. */
fun wayBackText(source: String): String = "Back where you opened $source. $source stays in your queue."

/**
 * The sheet's ♡ was tapped in the phone viewer on a post that [wasFavourited] or not, and the toggle [ran] (false:
 * it opened sign-in): a favourite made there is the moment for [Tip.LongPressFavourite] (#527). An unfavourite isn't.
 * True if the tip is now current.
 */
fun Tips.offerLongPressTip(wasFavourited: Boolean, ran: Boolean): Boolean =
  !wasFavourited && ran && offer(Tip.LongPressFavourite)

@Serializable
private data class SavedTips(val done: Set<String> = emptySet())

/**
 * Which [Tip]s are done, kept in [file] between runs, apart from the saved tabs, so "Clear saved tabs" leaves them.
 * A tip is done once it has appeared, or once the user did what it teaches before it came ([used]), so it never shows
 * then. Only one is [current] at a time. Only the tips of this platform ([touch]: a phone, else desktop) ever show.
 * Kept by name, so a tip removed in a later version is ignored rather than making the file unreadable.
 */
class Tips(private val file: TextFile, private val touch: Boolean) {
  private var done: Set<String> =
    file.read()?.let { runCatching { json.decodeFromString(SavedTips.serializer(), it).done }.getOrNull() } ?: emptySet()

  /** The tip offered and not yet gone: waiting for its anchor to settle, or on screen. */
  var current by mutableStateOf<Tip?>(null)
    private set

  /** The tip on screen now, from [appeared] until it's [dismissed]. */
  private var onScreen: Tip? = null

  /**
   * A tip has just left the screen and no gesture has ended since ([gestureEnded]). The count pill's tips, which come
   * by themselves rather than on an action, wait for it to end: a tip takes the first touch anywhere to dismiss
   * itself, so one following at once took the gesture its predecessor taught, the first swipe after Got it (#538).
   * A tip offered on an action (the keep tip as the viewer closes) doesn't wait: that action was the user's.
   */
  var resting by mutableStateOf(false)
    private set

  /**
   * A page covers the window (a tag's page, #522): no tip shows ([TipAnchor]), as its popup would come over the page.
   * One on screen goes with it.
   */
  var covered by mutableStateOf(false)

  /** The tab count last [counted], null before the first. */
  private var tabCount: Int? = null

  /**
   * Whether a second tab has appeared since launch, so [Tip.TabList] is due. Tabs restored at launch don't count:
   * no tip at launch.
   */
  var secondTabAppeared by mutableStateOf(false)
    private set

  /** The number of tabs, now: the first call is the count at launch, and a later rise from one to two is the moment. */
  fun counted(tabs: Int) {
    val before = tabCount
    tabCount = tabs
    if (before != null && before < 2 && tabs >= 2) secondTabAppeared = true
  }

  /** Whether [tip] would still show. */
  fun pending(tip: Tip): Boolean = tip.touch == touch && tip.name !in done

  /** Makes [tip] [current] if it's [pending] and no other tip is; true if it now is. Its anchor shows it. */
  fun offer(tip: Tip): Boolean {
    if (!pending(tip) || current != null) return false
    current = tip
    return true
  }

  /**
   * [tip] is on screen: it's seen from now on, so one cut short (the app closed while it showed) doesn't come back.
   * A tip that was [offer]ed but went before it showed (its anchor scrolled away, the viewer reopened) can still come.
   */
  fun appeared(tip: Tip) {
    onScreen = tip
    if (tip.name !in done) markDone(tip)
  }

  /** A touch or click anywhere in the window has ended: a tip that left the screen before it holds no other up now. */
  fun gestureEnded() {
    resting = false
  }

  /** The user did what [tip] teaches (long-pressed a post, opened the tab list, reopened an item with the hold): it never shows after this. */
  fun used(tip: Tip) {
    if (tip.name !in done) markDone(tip)
  }

  /** [tip] is no longer [current]: it went (Got it, a tap elsewhere, back), or its anchor went before or while it showed. */
  fun dismissed(tip: Tip) {
    if (current == tip) current = null
    if (onScreen == tip) {
      onScreen = null
      resting = true
    }
  }

  private fun markDone(tip: Tip) {
    done = done + tip.name
    file.write(json.encodeToString(SavedTips.serializer(), SavedTips(done)))
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
