package dev.marzahn.sankaku

import app.cash.turbine.test
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A link no app could open says so, without the link (#358). */
class OpenUrlMessageTest {
  @Test
  fun aFailedLinkShowsTheMessageWithoutTheUrl() = runTest {
    val messages = Messages()
    messages.messages.test {
      messages.openUrl("https://www.sankakucomplex.com/posts/abc?tags=x") { OpenUrlResult.Failed }
      val shown = awaitItem()
      assertEquals(Message(NoAppForLink, isError = true), shown)
      assertFalse("sankakucomplex" in shown.text || "abc" in shown.text)
    }
  }

  @Test
  fun anOpenedLinkShowsNothing() = runTest {
    val messages = Messages()
    messages.messages.test {
      messages.openUrl("https://example.com/") { OpenUrlResult.Opened }
      expectNoEvents()
    }
  }
}
