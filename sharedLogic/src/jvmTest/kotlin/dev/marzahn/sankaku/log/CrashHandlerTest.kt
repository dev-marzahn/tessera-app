package dev.marzahn.sankaku.log

import dev.marzahn.sankaku.FixedClock
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** #208: the installed handler records a real uncaught exception, then hands it to the handler that was there. */
class CrashHandlerTest {
  private val original = Thread.getDefaultUncaughtExceptionHandler()

  @AfterTest
  fun restore() = Thread.setDefaultUncaughtExceptionHandler(original)

  @Test
  fun recordsThenPassesToThePreviousHandler() {
    val file = InMemoryTextFile()
    var seenByPrevious: Throwable? = null
    var fileWhenPassed: String? = null
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
      seenByPrevious = error
      fileWhenPassed = file.read()
    }
    CrashReports(file, FixedClock, version = "1.2.3").installUncaughtHandler()

    val error = IllegalStateException("https://sankakuapi.com/posts/123?tags=x")
    thread { throw error }.join()

    assertSame(error, seenByPrevious)
    // Written before the previous handler ran: Android's kills the process.
    val text = assertNotNull(fileWhenPassed)
    assertTrue(text.lines()[1] == "java.lang.IllegalStateException", text)
    assertFalse("sankakuapi" in text, text)
  }

  @Test
  fun aFailureThatLeavesTheAppRunningIsPassedOnButNotRecorded() {
    val file = InMemoryTextFile()
    var passed = 0
    Thread.setDefaultUncaughtExceptionHandler { _, _ -> passed++ }
    CrashReports(file, FixedClock, version = "1.2.3").installUncaughtHandler(closesApp = { false })

    thread { throw IllegalStateException() }.join()

    assertEquals(1, passed)
    assertNull(file.read())
  }

  @Test
  fun aFailingFileStillPassesTheCrashOn() {
    var passed = 0
    Thread.setDefaultUncaughtExceptionHandler { _, _ -> passed++ }
    val broken = object : dev.marzahn.sankaku.data.TextFile {
      override fun read(): String? = null
      override fun write(text: String) = throw OutOfMemoryError()
      override fun delete() = Unit
    }
    CrashReports(broken, FixedClock, version = "1.2.3").installUncaughtHandler()

    thread { throw IllegalStateException() }.join()

    assertEquals(1, passed)
  }
}
