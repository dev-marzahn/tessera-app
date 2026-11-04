package dev.marzahn.sankaku.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.data.TextFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A just-in-time tip (#140): shown once per install, at the moment its gesture is useful. */
enum class Tip(val text: String) {
  /** On the cell the viewer closed into, the first time it closes back to a grid. */
  GridGestures("Long-press a post to keep it."),

  /** On the count pill (#178), the first time a second item appears. */
  TabList("Tap the count to see your whole queue."),

  /**
   * On the count pill (#344, replacing the top line's hint after every close), once a close's Undo has gone with an
   * item still closed: the hold is then the way back.
   */
  ReopenHold("Hold the queue count to reopen closed items any time."),
}

@Serializable
private data class SavedTips(val done: Set<String> = emptySet())

/**
 * Which [Tip]s are done, kept in [file] between runs, apart from the saved tabs, so "Clear saved tabs" leaves them.
 * A tip is done once it has appeared, or once the user did what it teaches before it came ([used]), so it never shows
 * then. Only one is [current] at a time. With [enabled] false (desktop, which has hover and keys) none ever shows.
 * Kept by name, so a tip removed in a later version is ignored rather than making the file unreadable.
 */
class Tips(private val file: TextFile, private val enabled: Boolean) {
  private var done: Set<String> =
    file.read()?.let { runCatching { json.decodeFromString(SavedTips.serializer(), it).done }.getOrNull() } ?: emptySet()

  /** The tip offered and not yet gone: waiting for its anchor to settle, or on screen. */
  var current by mutableStateOf<Tip?>(null)
    private set

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
  fun pending(tip: Tip): Boolean = enabled && tip.name !in done

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
    if (tip.name !in done) markDone(tip)
  }

  /** The user did what [tip] teaches (long-pressed a post, opened the tab list, reopened an item with the hold): it never shows after this. */
  fun used(tip: Tip) {
    if (tip.name !in done) markDone(tip)
  }

  /** [tip] is no longer [current]: it went (Got it, a tap elsewhere, back), or its anchor went before or while it showed. */
  fun dismissed(tip: Tip) {
    if (current == tip) current = null
  }

  private fun markDone(tip: Tip) {
    done = done + tip.name
    file.write(json.encodeToString(SavedTips.serializer(), SavedTips(done)))
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
