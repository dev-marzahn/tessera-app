package dev.marzahn.sankaku.log

import dev.marzahn.sankaku.APP_VERSION
import dev.marzahn.sankaku.data.TextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Crash capture (#208, decision 18 in #196). [record] writes an uncaught exception to [file]: the time, the [version],
 * then each exception's class name and stack frames, causes and suppressed ones included. **Never a message**: those
 * can hold URLs, queries and ids. Nothing is sent; the next launch offers the report through [pending], and [clear]
 * deletes it.
 *
 * One instance per process, made before anything else runs: [installUncaughtHandler] records into it, and the
 * notice reads it.
 */
class CrashReports(
  private val file: TextFile,
  private val clock: Clock = Clock.System,
  private val version: String = APP_VERSION,
) {
  /** The report the previous run left, as read at start. */
  private val previous = runCatching { file.read() }.getOrNull()?.takeIf { it.isNotBlank() }

  private val _pending = MutableStateFlow(previous)

  /** The report a previous run left, or null. A crash in this run waits for the next launch. */
  val pending: StateFlow<String?> = _pending.asStateFlow()

  /** Writes [error]'s report over any earlier one. Never throws: it runs inside the uncaught-exception handler. */
  fun record(error: Throwable) {
    runCatching { file.write(report(error)) }
  }

  /**
   * Report: the file goes, while the notice can stay to say the form didn't open. Only while it still holds the
   * report shown: one this run recorded since is kept for the next launch.
   */
  fun deleteFile() {
    runCatching { if (previous != null && file.read() == previous) file.delete() }
  }

  /** Dismiss, or Report once the form opened: the notice goes, and so does the file. */
  fun clear() {
    _pending.value = null
    deleteFile()
  }

  private fun report(error: Throwable): String = buildString {
    append("Crashed: ${Instant.fromEpochSeconds(clock.now().epochSeconds)}, Tessera $version\n")
    appendThrowable(error, prefix = "", seen = mutableListOf())
  }

  private fun StringBuilder.appendThrowable(error: Throwable, prefix: String, seen: MutableList<Throwable>) {
    if (seen.any { it === error }) {
      append("\t[CIRCULAR REFERENCE: ${error.typeName}]\n")
      return
    }
    seen += error
    append(prefix).append(error.typeName).append('\n')
    val frames = error.frames()
    frames.take(MAX_FRAMES).forEach { append("\tat ").append(it).append('\n') }
    if (frames.size > MAX_FRAMES) append("\t... ${frames.size - MAX_FRAMES} more frames\n")
    error.suppressedExceptions.forEach { appendThrowable(it, "Suppressed: ", seen) }
    error.cause?.let { appendThrowable(it, "Caused by: ", seen) }
  }

  companion object {
    /** Frames kept per exception; a stack overflow's thousand add nothing past the loop's first turns. */
    const val MAX_FRAMES = 100
  }
}

/** The exception's class name, never its message or `toString()`. */
internal expect val Throwable.typeName: String

/** The stack frames as `class.method(File.kt:12)`: class, method and file names and a line, nothing else. */
internal expect fun Throwable.frames(): List<String>

/**
 * Makes [this] the process's default uncaught-exception handler: it records the exception, then passes it to the
 * handler that was there before (Android's kills the process and shows "keeps stopping"), so the crash happens as it
 * would have. Call it first thing at start.
 *
 * [closesApp] runs on the failing thread and says whether this failure ends the app; only those are recorded, so the
 * notice's "closed unexpectedly" holds. Every uncaught exception does on Android; desktop survives a background
 * thread's.
 */
expect fun CrashReports.installUncaughtHandler(closesApp: () -> Boolean = { true })
