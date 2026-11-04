package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.StartOn
import dev.marzahn.sankaku.queue.QueuePageAtStart
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Touch: where a start lands (#177), and whether the queue has been used since the app started (#264). A **start** is a
 * cold launch ([launched]) or a return after more than [AWAY] in the background ([left], then [returned]); it's what
 * "Start on" acts on. It's not a session (Glossary → Session), which only Clear viewed ends.
 *
 * - **Start on: Queue** (the default) opens the queue page ([pageOpen]) when the queue holds more than the current
 *   item: a kept post, or a second source ([QueuePageAtStart], #231). Otherwise it stays on the current item. **Browse** shows
 *   a Browse grid opened "now" ([TabsController.showBrowse]). Either way nothing in the queue moves or closes.
 * - **Interacted:** the first use of the queue in this app process ([interact]): a touch or scroll on the queue page,
 *   closing it, a tap on the pill, another item shown ([shown]) or the Up next card. The queue page blurs R15 and R18
 *   thumbnails until then ([interacted]), and never again in this process: a return after [AWAY] doesn't blur them
 *   again. Memory only, never saved.
 *
 * A start also takes away the Up next card ([UpNextState]), so it is never the first thing shown.
 *
 * A single for the process, so turning the phone doesn't count as a start.
 */
class QueueStart(
  private val tabs: TabsController,
  private val privacy: PrivacySettings,
  private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  private val upNext: UpNextState? = null,
) {
  /** Whether the queue page is open. Snapshot state. */
  var pageOpen by mutableStateOf(false)
    private set

  /** Whether the queue has been used since the app started ([interact]). Snapshot state. */
  var interacted by mutableStateOf(false)
    private set

  // The item the last start landed on: showing it is not a use of the queue, showing any other is.
  private var landedOn: TabId? = null
  private var launched = false
  private var leftAt: ComparableTimeMark? = null

  /** The app's first frame: the first start. Later calls (a recreated activity) do nothing. */
  fun launched() {
    if (launched) return
    launched = true
    start()
  }

  /** The app went to the background. */
  fun left() {
    leftAt = time.markNow()
  }

  /** The app came back to the foreground: a new start if it was away for more than [AWAY]. */
  fun returned() {
    val left = leftAt ?: return
    leftAt = null
    if (left.elapsedNow() > AWAY) start()
  }

  /** The queue item [id] is on screen: a use of the queue unless it's the item the last start landed on. */
  fun shown(id: TabId) {
    if (id != landedOn) interact()
  }

  /** The queue was used: its thumbnails stay unblurred until the app process ends. */
  fun interact() {
    interacted = true
  }

  fun openPage() {
    pageOpen = true
  }

  fun closePage() {
    pageOpen = false
  }

  private fun start() {
    // The Up next card (#216) is never the first thing after a start: the queue page, the current item or Browse is.
    upNext?.dismiss()
    when (privacy.startOn.value) {
      StartOn.Queue -> pageOpen = QueuePageAtStart.opens(tabs.tabs.value.map { it.kind })
      StartOn.Browse -> {
        pageOpen = false
        tabs.showBrowse()
      }
    }
    landedOn = tabs.active.value
  }

  companion object {
    /** How long in the background makes a return a new start. */
    val AWAY = 5.minutes
  }
}
