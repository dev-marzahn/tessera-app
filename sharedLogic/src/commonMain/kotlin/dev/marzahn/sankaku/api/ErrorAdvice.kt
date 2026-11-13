package dev.marzahn.sankaku.api

/** What a failed load or action offers next (#520). */
enum class ErrorAction { Retry, EditSearch, SignIn }

/**
 * What the app tells the user about a failure: one plain sentence that says what to do, the buttons for it, and for a
 * 429 the seconds Retry stays disabled. The one place an [ApiError] becomes words (#520); exception messages stay for
 * logs and `:cli`.
 */
data class ErrorAdvice(val sentence: String, val actions: List<ErrorAction>, val retryAfterSeconds: Long? = null)

/**
 * [signedIn]: signed out, a 404 is how Sankaku hides what only accounts may see (a kept post after a restart, #520),
 * so it asks to sign in, as the grids' "Sign in to view" cells do.
 */
fun Throwable.advice(signedIn: Boolean = true): ErrorAdvice {
  val retry = listOf(ErrorAction.Retry)
  return when (this) {
    is ApiError.Http if status == 404 && !signedIn ->
      ErrorAdvice("Sankaku shows this only to signed-in accounts. Sign in to view it.", listOf(ErrorAction.SignIn))
    is ApiError.Network ->
      ErrorAdvice(if (timedOut) "Sankaku took too long to answer. Try again." else "Can't reach Sankaku. Check your connection and try again.", retry)
    is ApiError.Unauthorized -> ErrorAdvice("Your session has ended. Sign in again.", listOf(ErrorAction.SignIn, ErrorAction.Retry))
    // The countdown is on the Retry button, so the sentence doesn't repeat a number that would go stale.
    is ApiError.RateLimited -> ErrorAdvice("Too many requests at once. Try again in a moment.", retry, retryAfterSeconds)
    is ApiError.Rejected -> ErrorAdvice("Sankaku is turning this app away right now. Try again later.", retry)
    is ApiError.SearchTimeout ->
      ErrorAdvice("This search took Sankaku too long. Try fewer or narrower tags.", listOf(ErrorAction.EditSearch, ErrorAction.Retry))
    // Retrying the same search meets the same limit, so only Edit search.
    is ApiError.TagLimit -> ErrorAdvice(
      limit?.let { "Your search limit is $it tags. Remove a tag first." }
        ?: "Your search has more tags than Sankaku allows. Remove a tag first.",
      listOf(ErrorAction.EditSearch),
    )
    is ApiError.Http -> ErrorAdvice(
      when {
        status >= 500 -> "Sankaku is having trouble. Try again in a moment."
        status == 404 -> "Sankaku couldn't find it. It may have been deleted."
        status in 400..499 -> "Sankaku didn't accept that. Try again later."
        // A 2xx the app couldn't use (`success: false`, a missing field), or no response at all.
        else -> UNREADABLE
      },
      retry,
    )
    is ApiError.Decoding -> ErrorAdvice(UNREADABLE, retry)
    else -> ErrorAdvice("Something went wrong. Try again.", retry)
  }
}

/** A failed action's message: what failed, then the advice. `couldNot("favourite")` → "Couldn't favourite. …". */
fun Throwable.couldNot(action: String): String = "Couldn't $action. ${advice().sentence}"

private const val UNREADABLE = "Sankaku sent an answer the app can't read. Try again; if it keeps happening, report a bug."
