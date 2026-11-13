package dev.marzahn.sankaku.log

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.NOW
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Crash capture (#208): what reaches the crash file, and the next launch's notice reading and deleting it. */
class CrashReportsTest {
  private class PostLoadFailed(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    // Some exceptions put their message in toString too; the report never calls it.
    override fun toString(): String = "PostLoadFailed: $message"
  }

  /** A cause chain that loops back, which `initCause` can't make in common code. */
  private class Looped : RuntimeException("a") {
    var next: Throwable? = null
    override val cause: Throwable? get() = next
  }

  private val url = "https://sankakuapi.com/posts/PostIdQx7Lm?tags=searchedtagzz&access_token=AccessTokenYy9"

  private fun recorded(error: Throwable): String {
    val file = InMemoryTextFile()
    CrashReports(file, FixedClock, version = "1.2.3").record(error)
    return file.read()!!
  }

  @Test
  fun aMessageHoldingAUrlNeverReachesTheFile() {
    val cause = IllegalStateException("Couldn't load $url")
    val error = PostLoadFailed("Failed: $url", cause).apply { addSuppressed(IllegalArgumentException(url)) }

    val text = recorded(error)

    for (leak in listOf("https://", "sankakuapi", "PostIdQx7Lm", "searchedtagzz", "AccessTokenYy9", "Failed:", "Couldn't")) {
      assertFalse(leak in text, "the crash file holds '$leak'")
    }
  }

  @Test
  fun savesClassNamesAndFramesWithCausesTheVersionAndTheTime() {
    val text = recorded(PostLoadFailed("x", IllegalStateException("y", ArithmeticException("z"))))
    val lines = text.lines()

    assertEquals("Crashed: $NOW, Tessera 1.2.3", lines[0])
    assertTrue(lines[1].endsWith("PostLoadFailed"), lines[1])
    assertTrue(lines.any { it.startsWith("Caused by: ") && it.endsWith("IllegalStateException") }, text)
    assertTrue(lines.any { it.startsWith("Caused by: ") && it.endsWith("ArithmeticException") }, text)
    // Frames name this test, where all three were thrown.
    assertTrue(lines.any { it.startsWith("\tat ") && "CrashReportsTest" in it }, text)
  }

  @Test
  fun suppressedExceptionsAreKeptWithoutTheirMessages() {
    val error = RuntimeException("a").apply { addSuppressed(IllegalArgumentException("b")) }

    val text = recorded(error)

    assertTrue(text.lines().any { it.startsWith("Suppressed: ") && it.endsWith("IllegalArgumentException") }, text)
  }

  @Test
  fun aCauseCycleIsWrittenOnce() {
    val first = Looped()
    val second = IllegalStateException("b", first)
    first.next = second

    val text = recorded(first)

    assertEquals(1, text.lines().count { it.startsWith("Caused by: ") }, text)
    assertTrue("CIRCULAR" in text, text)
  }

  @Test
  fun aDeepStackIsCut() {
    fun recurse(depth: Int): Nothing = if (depth == 0) throw IllegalStateException("deep") else recurse(depth - 1)
    val error = runCatching { recurse(600) }.exceptionOrNull()!!

    val text = recorded(error)

    assertTrue(text.lines().count { it.startsWith("\tat ") } <= CrashReports.MAX_FRAMES, text.lines().size.toString())
    assertTrue(text.lines().any { it.startsWith("\t... ") && it.endsWith(" more frames") }, text.takeLast(200))
  }

  @Test
  fun theNextLaunchSeesTheCrashAndEitherAnswerDeletesIt() {
    val file = InMemoryTextFile()
    CrashReports(file, FixedClock, version = "1.2.3").record(IllegalStateException("x"))

    val next = CrashReports(file, FixedClock, version = "1.2.3")
    assertTrue(next.pending.value!!.startsWith("Crashed: "))

    next.clear()
    assertNull(next.pending.value)
    assertNull(file.read())
    assertNull(CrashReports(file, FixedClock, version = "1.2.3").pending.value)
  }

  @Test
  fun aCrashRecordedSinceTheNoticeShowedOutlivesDismissAndReport() {
    val file = InMemoryTextFile()
    CrashReports(file, FixedClock, version = "1.2.2").record(IllegalStateException("x"))
    val next = CrashReports(file, FixedClock, version = "1.2.3")
    next.record(ArithmeticException("y"))

    next.deleteFile()
    next.clear()

    assertNull(next.pending.value)
    assertTrue(file.read()!!.contains("ArithmeticException"))
    assertTrue(CrashReports(file, FixedClock).pending.value!!.contains("Tessera 1.2.3"))
  }

  @Test
  fun aCrashThisRunWaitsForTheNextLaunch() {
    val reports = CrashReports(InMemoryTextFile(), FixedClock, version = "1.2.3")

    reports.record(IllegalStateException("x"))

    assertNull(reports.pending.value)
  }

  @Test
  fun aFileThatCantBeWrittenDoesntThrowOutOfTheHandler() {
    val broken = object : TextFile {
      override fun read(): String? = null
      override fun write(text: String) = throw IllegalStateException("disk full")
      override fun delete() = throw IllegalStateException("disk full")
    }
    val reports = CrashReports(broken, FixedClock, version = "1.2.3")

    reports.record(RuntimeException("x"))
    reports.clear()
  }
}
