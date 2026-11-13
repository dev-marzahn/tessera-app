package dev.marzahn.sankaku.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A snackbar message, optionally with one action. [isError] says why something failed: on touch the top line shows it
 * with an error icon. [short] keeps a message with an action to the Short duration (a message with one is Long
 * otherwise): "Cleared 118 · Undo" says what happened and can be undone later anyway.
 */
data class Message(
  val text: String,
  val actionLabel: String? = null,
  val isError: Boolean = false,
  val short: Boolean = false,
  val onAction: () -> Unit = {},
)

/**
 * Snackbar messages from anywhere in the app; `App` shows them one at a time, the newest replacing the last: at the
 * bottom on desktop, on the top line on touch (`.scratch/android-feedback/`, C), where only words go that no control
 * on screen can show.
 */
class Messages {
  private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val messages: SharedFlow<Message> = _messages.asSharedFlow()

  fun show(message: Message) {
    _messages.tryEmit(message)
  }

  fun show(text: String) = show(Message(text))

  /** A failure's reason, such as "Couldn't favourite: no connection". */
  fun showError(text: String) = show(Message(text, isError = true))
}
