package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.ErrorAction.EditSearch
import dev.marzahn.sankaku.api.ErrorAction.Retry
import dev.marzahn.sankaku.api.ErrorAction.SignIn
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorAdviceTest {
  private val cases: List<Triple<Throwable, String, List<ErrorAction>>> = listOf(
    Triple(ApiError.Network(IOException("refused")), "Can't reach Sankaku. Check your connection and try again.", listOf(Retry)),
    Triple(
      ApiError.Network(HttpRequestTimeoutException("u", 30_000)),
      "Sankaku took too long to answer. Try again.",
      listOf(Retry),
    ),
    Triple(ApiError.Unauthorized(), "Your session has ended. Sign in again.", listOf(SignIn, Retry)),
    Triple(ApiError.RateLimited(12), "Too many requests at once. Try again in a moment.", listOf(Retry)),
    Triple(
      ApiError.Rejected(403),
      "Sankaku is turning this app away right now. Try again later.",
      listOf(Retry),
    ),
    Triple(
      ApiError.SearchTimeout(),
      "This search took Sankaku too long. Try fewer or narrower tags.",
      listOf(EditSearch, Retry),
    ),
    Triple(ApiError.TagLimit(9), "Your search limit is 9 tags. Remove a tag first.", listOf(EditSearch)),
    Triple(ApiError.TagLimit(null), "Your search has more tags than Sankaku allows. Remove a tag first.", listOf(EditSearch)),
    Triple(ApiError.Http(502, "code=\"x\""), "Sankaku is having trouble. Try again in a moment.", listOf(Retry)),
    Triple(ApiError.Http(404, "post not found"), "Sankaku couldn't find it. It may have been deleted.", listOf(Retry)),
    Triple(ApiError.Http(400, "code=\"bad\""), "Sankaku didn't accept that. Try again later.", listOf(Retry)),
    Triple(ApiError.Http(200, "success=false"), UNREADABLE, listOf(Retry)),
    Triple(ApiError.Decoding(IllegalStateException("x")), UNREADABLE, listOf(Retry)),
    Triple(IllegalStateException("boom"), "Something went wrong. Try again.", listOf(Retry)),
  )

  @Test
  fun eachErrorHasOnePlainSentenceAndItsNextSteps() {
    for ((error, sentence, actions) in cases) {
      val advice = error.advice()
      assertEquals(sentence, advice.sentence, error::class.simpleName)
      assertEquals(actions, advice.actions, error::class.simpleName)
    }
  }

  @Test
  fun noAdviceShowsInternalText() {
    for ((error, _, _) in cases) {
      val text = error.advice().sentence
      for (internal in listOf("HTTP", "code=", "Unexpected response", "success=", "rejected this client")) {
        assertFalse(internal in text, "${error::class.simpleName}: $text")
      }
      assertTrue(text.endsWith("."), text)
    }
  }

  /** Signed out, Sankaku answers a post only accounts may see with a 404: like the grids' "Sign in to view" (#520). */
  @Test
  fun aNotFoundSignedOutAsksToSignIn() {
    val advice = ApiError.Http(404, "code=\"snackbar__server-error_not-found\"").advice(signedIn = false)
    assertEquals("Sankaku shows this only to signed-in accounts. Sign in to view it.", advice.sentence)
    assertEquals(listOf(SignIn), advice.actions)
    assertEquals(listOf(Retry), ApiError.Http(503, "").advice(signedIn = false).actions)
  }

  @Test
  fun onlyARateLimitCountsDown() {
    assertEquals(12, ApiError.RateLimited(12).advice().retryAfterSeconds)
    assertEquals(null, ApiError.Http(503, "").advice().retryAfterSeconds)
  }

  @Test
  fun aFailedActionLeadsWithWhatFailed() {
    assertEquals(
      "Couldn't favourite. Sankaku is having trouble. Try again in a moment.",
      ApiError.Http(500, "").couldNot("favourite"),
    )
  }

  private companion object {
    const val UNREADABLE = "Sankaku sent an answer the app can't read. Try again; if it keeps happening, report a bug."
  }
}
