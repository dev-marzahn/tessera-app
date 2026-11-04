package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages

/**
 * Clear viewed (#183, spec: Tabs → Queue → Session): removes the kept posts seen since they were kept and ends the
 * session ([TabsController.clearViewed]). The queue page's header and the desktop's strip menu and Cmd/Ctrl-Shift-E all
 * go through here.
 *
 * Undo is the top line's "Cleared N · Undo" (Short), and for good the entry in Recently closed ("N posts · cleared"),
 * which needs no timer. It never posts a system notification.
 */
class ClearViewed(
  private val controller: TabsController,
  private val seen: SeenStore,
  private val messages: Messages,
) {
  /** What Clear viewed would do now. A snapshot read on the tabs, so a composable reading it follows a change. */
  data class Status(
    /** How many kept posts it would clear. */
    val count: Int,
    /** Every kept post has been seen since it was kept: the header suggests Clear viewed (primary, not tonal). */
    val suggested: Boolean,
  )

  fun status() = Status(controller.viewedKeptPosts(seen).size, controller.everyKeptPostViewed(seen))

  /**
   * Clears the viewed kept posts and says so on the top line; returns how many went. With none, nothing happens and
   * nothing is said: the shortcut repeats while held, and a second press mustn't replace the "Cleared" line (and its
   * Undo) with a word about nothing.
   */
  operator fun invoke(): Int {
    val cleared = controller.clearViewed(seen) ?: return 0
    messages.show(
      // The snackbar draws the action beside the text: "Cleared 118 · Undo".
      Message("Cleared ${cleared.size}", actionLabel = "Undo", short = true) {
        controller.reopen(cleared)
      },
    )
    return cleared.size
  }
}

/**
 * The accessible name of Clear viewed's button, as the spec words it: "Clear viewed, 118 kept posts". On a phone
 * ([touch]) one is "1 kept post" (#249); desktop's words stay as they were.
 */
internal fun clearViewedLabel(count: Int, touch: Boolean = platformIsTouch) =
  "Clear viewed, $count ${if (touch && count == 1) "kept post" else "kept posts"}"
