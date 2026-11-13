package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.ReadingDto
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [inner], counting its [deletes]. */
private class DeleteCounting(private val inner: TextFile) : TextFile by inner {
  @Volatile var deletes = 0
    private set

  override fun delete() {
    deletes++
    inner.delete()
  }
}

class ReadingProgressTest {
  // Answers each PUT with the next of these, then with success.
  private val answers = ArrayDeque<HttpStatusCode>()

  // When set, a PUT waits for it before it's answered; [held] says one is waiting.
  private var gate: CompletableDeferred<Unit>? = null

  @Volatile private var held = false

  // While set, a PUT finds no connection.
  @Volatile private var unreachable = false

  private fun harness(signedIn: Boolean) = Harness(InMemoryTokenStore(if (signedIn) storedTokens() else null)) { request ->
    gate?.takeIf { request.method == HttpMethod.Put }?.let {
      held = true
      it.await()
    }
    if (unreachable && request.method == HttpMethod.Put) throw IOException("unreachable")
    val status = answers.removeFirstOrNull() ?: HttpStatusCode.OK
    json("""{ "success": ${status == HttpStatusCode.OK} }""", status)
  }

  private fun HttpRequestData.sequence() =
    Json.parseToJsonElement((body as TextContent).text).jsonObject["sequence"]!!.jsonPrimitive.content.toInt()

  private fun HttpRequestData.bookId() = url.encodedPath.removePrefix("/pools/").removeSuffix("/reading")

  /** Waits (on real time: the engine answers on its own threads) until [done]. */
  private suspend fun until(done: () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(5_000) { while (!done()) delay(10) }
  }

  /** The PUTs, once [count] have come and a little longer, so one more would be counted too. */
  private suspend fun Harness.puts(count: Int): List<HttpRequestData> {
    until { requests.size >= count }
    withContext(Dispatchers.Default) { delay(50) }
    return requests.toList()
  }

  private fun progress(h: Harness, file: TextFile, scope: CoroutineScope) = ReadingProgress(BooksApi(h.mainClient), h.session, h.connectivity, file, scope)

  private fun Harness.puts() = requests.filter { it.method == HttpMethod.Put }.map { it.bookId() to it.sequence() }

  @Test
  fun signedInAPlaceSavedOfflineIsKeptAcrossARestartAndSentOnceOnline() = runTest {
    unreachable = true
    val h = harness(signedIn = true)
    h.session.restore()
    val file = InMemoryTextFile()
    progress(h, file, backgroundScope).save("b1", BookPlace(sequence = 79, page = 80, pages = 120))
    until { h.puts().size == 1 && file.read()?.contains("79") == true }

    // The process dies; a later launch, still offline, shows the unsent place over the server's older one and tries it.
    val server = BookDto(id = "b1", postCount = 120, reading = ReadingDto(currentPage = 12, sequence = 11))
    val h2 = harness(signedIn = true)
    h2.session.restore()
    val later = progress(h2, file, backgroundScope)
    assertEquals(79, later.savedOf(server)?.sequence)
    until { h2.puts().size == 1 }

    // Online again: sent once, and no longer kept as unsent.
    unreachable = false
    h2.connectivity.requestRetry()
    until { h2.puts().size == 2 }
    assertEquals(listOf("b1" to 79, "b1" to 79), h2.puts())
    until { file.read()?.contains("79") != true }
    h2.connectivity.requestRetry()
    withContext(Dispatchers.Default) { delay(100) }
    assertEquals(2, h2.puts().size, "a sent place isn't sent again")
    // A launch after that sends nothing.
    val h3 = harness(signedIn = true)
    h3.session.restore()
    progress(h3, file, backgroundScope)
    withContext(Dispatchers.Default) { delay(100) }
    assertTrue(h3.puts().isEmpty())
  }

  @Test
  fun anotherAccountsUnsentPlaceIsNeitherShownNorSentAtLaunch() = runTest {
    val file = InMemoryTextFile("""{ "version": 1, "unsent": [{ "i": "b1", "s": 4, "p": 5, "n": 10, "a": "someone-else" }] }""")
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, file, backgroundScope)
    until { progress.savedOf(BookDto(id = "b1")) == null }
    withContext(Dispatchers.Default) { delay(100) }
    assertTrue(h.puts().isEmpty(), "sent for another account: ${h.puts()}")
    until { file.read() == null }
  }

  @Test
  fun aPlaceSentAtOnceIsNotKeptAsUnsent() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    // Counts the deletes: the file is deleted only once it would hold nothing, here once the place was sent.
    val file = DeleteCounting(InMemoryTextFile())
    progress(h, file, backgroundScope).save("b1", BookPlace(sequence = 7, page = 8, pages = 20))
    h.puts(1)
    until { file.deletes > 0 }
    val h2 = harness(signedIn = true)
    h2.session.restore()
    val later = progress(h2, file, backgroundScope)
    withContext(Dispatchers.Default) { delay(100) }
    assertTrue(h2.puts().isEmpty(), "sent again: ${h2.puts()}")
    assertNull(later.savedOf(BookDto(id = "b1", postCount = 20)))
  }

  @Test
  fun anUnsentPlaceIsNotSentForAnotherAccount() = runTest {
    unreachable = true
    val h = harness(signedIn = true)
    h.session.restore()
    val file = InMemoryTextFile()
    val progress = progress(h, file, backgroundScope)
    progress.save("b1", BookPlace(sequence = 4, page = 5, pages = 10))
    until { h.puts().size == 1 }
    h.session.signOut()
    until { progress.savedOf(BookDto(id = "b1"), signedIn = true) == null }
    unreachable = false
    h.connectivity.requestRetry()
    withContext(Dispatchers.Default) { delay(100) }
    assertEquals(1, h.puts().size)
    until { file.read()?.contains("\"s\":4") != true }
  }

  @Test
  fun signedInASaveIsSentOnceAndShownAtOnce() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    val file = InMemoryTextFile()
    val progress = progress(h, file, backgroundScope)
    val book = BookDto(id = "b1", postCount = 30)

    progress.save("b1", BookPlace(sequence = 11, page = 12, pages = 28))

    val sent = h.puts(1)
    assertEquals(1, sent.size)
    assertEquals(HttpMethod.Put, sent.single().method)
    assertEquals("/pools/b1/reading", sent.single().url.encodedPath)
    assertEquals(11, sent.single().sequence())
    // Shown before the list is loaded again, over the server's older place; not a place kept on the device.
    assertEquals(SavedReading(sequence = 11, page = 12, pages = 28, finished = false), progress.savedOf(book.copy(reading = ReadingDto(currentPage = 3, sequence = 2))))
    assertNull(progress.savedOf(book, signedIn = false))
  }

  @Test
  fun landedCountsOnlyThePlacesSankakuTook() = runTest {
    // #503: the shelf asks again once Sankaku has a place; a refused or unreachable send doesn't change its order.
    answers += HttpStatusCode.InternalServerError
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, InMemoryTextFile(), backgroundScope)
    progress.save("b1", BookPlace(4, 5, 10))
    h.puts(1)
    assertEquals(0, progress.landed.value)
    progress.save("b2", BookPlace(1, 2, 10))
    h.puts(2)
    until { progress.landed.value == 1 }
    unreachable = true
    progress.save("b3", BookPlace(1, 2, 10))
    withContext(Dispatchers.Default) { delay(300) }
    assertEquals(1, progress.landed.value)
  }

  @Test
  fun sendsGoOutInTheOrderSaved() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, InMemoryTextFile(), backgroundScope)
    (1..5).forEach { progress.save("b$it", BookPlace(it, it + 1, 10)) }
    assertEquals((1..5).map { "b$it" to it }, h.puts(5).map { it.bookId() to it.sequence() })
  }

  @Test
  fun aFailedSendIsNeverSentAgain() = runTest {
    answers += HttpStatusCode.InternalServerError
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, InMemoryTextFile(), backgroundScope)
    progress.save("b1", BookPlace(4, 5, 10))
    h.puts(1)
    // Shown this run although it didn't reach Sankaku.
    assertEquals(4, progress.savedOf(BookDto(id = "b1"))?.sequence)

    // No automatic retry (REVIEW.md): the next save sends only itself.
    progress.save("b2", BookPlace(1, 2, 10))
    assertEquals(listOf("b1" to 4, "b2" to 1), h.puts(2).map { it.bookId() to it.sequence() })
  }

  @Test
  fun aSaveQueuedBeforeASignOutIsNotSentAfterIt() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, InMemoryTextFile(), backgroundScope)
    // The first send is held at the server while two more queue behind it.
    gate = CompletableDeferred()
    progress.save("b1", BookPlace(1, 2, 10))
    until { held }
    progress.save("b2", BookPlace(2, 3, 10))
    progress.save("b3", BookPlace(3, 4, 10))
    h.session.signOut()
    gate!!.complete(Unit)
    assertEquals(listOf("b1"), h.puts(1).filter { it.method == HttpMethod.Put }.map { it.bookId() })
  }

  @Test
  fun signedOutItStaysOnTheDeviceAndClearEverythingClearsIt() = runTest {
    val h = harness(signedIn = false)
    h.session.restore()
    val file = InMemoryTextFile()
    val progress = progress(h, file, backgroundScope)
    val revision = progress.revision.value

    progress.save("b1", BookPlace(sequence = 5, page = 6, pages = 6))

    assertTrue(h.requests.isEmpty(), "nothing is sent")
    assertTrue(progress.revision.value > revision)
    val book = BookDto(id = "b1", postCount = 6)
    assertEquals(SavedReading(5, 6, 6, finished = true), progress.savedOf(book))
    // Written in the background, and kept across launches.
    until { file.read() != null }
    assertEquals(SavedReading(5, 6, 6, finished = true), progress(h, file, backgroundScope).savedOf(book))

    progress.clear()
    assertNull(progress.savedOf(book))
    assertNull(file.read())
    assertNull(progress(h, file, backgroundScope).savedOf(book))
  }

  @Test
  fun signedInTheServersPlaceIsReadFromTheBook() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, InMemoryTextFile(), backgroundScope)
    assertNull(progress.savedOf(BookDto(id = "b1", postCount = 34)))
    val started = progress.savedOf(BookDto(id = "b1", postCount = 34, reading = ReadingDto(currentPage = 12, sequence = 11)))
    assertEquals(SavedReading(11, 12, 34, finished = false), started)
    assertEquals(12f / 34, started!!.fraction)
    assertTrue(progress.savedOf(BookDto(id = "b1", postCount = 34, reading = ReadingDto(currentPage = 34, sequence = 33)))!!.finished)
    assertTrue(progress.savedOf(BookDto(id = "b1", postCount = 34, reading = ReadingDto(currentPage = 34, sequence = 33, percent = 100.0)))!!.finished)
    // Signed out, the server's place isn't the device's.
    assertNull(progress.savedOf(BookDto(id = "b1", postCount = 34, reading = ReadingDto(currentPage = 12)), signedIn = false))
  }

  @Test
  fun aShortBookFinishedHereStaysReadAfterARestartWhileTheServerHasThatPage() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    val file = InMemoryTextFile()
    // 20 of its 34 pages came back; the reader reached the 20th.
    progress(h, file, backgroundScope).save("b1", BookPlace(sequence = 19, page = 20, pages = 20))
    // Sent, and so no longer kept as unsent (#491): the restart below reads only the book finished here.
    until { file.read()?.let { "\"unsent\"" !in it } == true }

    val later = progress(h, file, backgroundScope)
    val atThatPage = BookDto(id = "b1", postCount = 34, reading = ReadingDto(currentPage = 20, sequence = 19, percent = 59.0))
    assertTrue(later.savedOf(atThatPage)!!.finished)
    // Moved back since, on the website: not read.
    assertFalse(later.savedOf(atThatPage.copy(reading = ReadingDto(currentPage = 3, sequence = 2)))!!.finished)
  }

  @Test
  fun signingOutForgetsWhatThisAccountSavedHere() = runTest {
    val h = harness(signedIn = true)
    h.session.restore()
    val progress = progress(h, InMemoryTextFile(), backgroundScope)
    progress.save("b1", BookPlace(9, 10, 10))
    h.puts(1)
    val book = BookDto(id = "b1", postCount = 34, reading = ReadingDto(currentPage = 10, sequence = 9))
    assertTrue(progress.savedOf(book)!!.finished)
    h.session.signOut()
    until { progress.savedOf(BookDto(id = "b1"), signedIn = true) == null }
    assertFalse(progress.savedOf(book, signedIn = true)!!.finished)
    assertNull(progress.savedOf(book, signedIn = false))
  }
}
