package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.data.OriginalFiles
import dev.marzahn.sankaku.data.PostStateStore
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class PostSaverTest {
  private val now = Instant.parse("2026-10-29T12:00:00Z")
  private val clock = object : Clock {
    override fun now() = now
  }
  private val scope = CoroutineScope(SupervisorJob())
  private val bytes = ByteArray(100_000) { (it % 199).toByte() }
  private val post = PostDto(
    id = "p1",
    md5 = "0123456789abcdef0123456789abcdef",
    rating = "s",
    status = "active",
    fileExt = "mp4",
    fileType = "video/mp4",
    fileUrl = "https://s.sankakucomplex.com/data/01/23/x.mp4?e=${(now + 1.hours).epochSeconds}&m=a",
  )
  private val dir: File = Files.createTempDirectory("saved").toFile()
  private val opened = mutableListOf<File>()

  private inner class Rig(val status: HttpStatusCode = HttpStatusCode.OK, val gate: CompletableDeferred<Unit>? = null) {
    val requests = mutableListOf<HttpRequestData>()
    private val engine = MockEngine { request ->
      requests += request
      gate?.await()
      respond(if (status == HttpStatusCode.OK) bytes else ByteArray(0), status)
    }
    private val log = RequestLog(clock)
    private val limiter = RateLimiter(limits = emptyMap())
    private val connectivity = ConnectivityState()
    private val session = AuthSession(AuthApi(createAuthClient(engine, log, limiter, connectivity), clock), InMemoryTokenStore(), clock)
    private val client = createMainClient(engine, log, limiter, connectivity, session)
    private val store = PostStateStore()
    val messages = Messages()
    val saver = PostSaver(
      OriginalFiles(client, PostsRepository(PostsApi(client), store, session, scope), store, clock),
      DirectorySaveFolder(dir) { opened += it },
      messages,
      scope,
    )

    /** Saves [post] and returns the first [count] messages; the last one ends the save. */
    fun save(count: Int): List<Message> = runBlocking {
      withTimeout(5_000) {
        // Undispatched, so it's subscribed before the save emits: a message with no subscriber is dropped. Unconfined,
        // so each message is taken on the saver's thread as it's emitted: dispatched to this loop, a quick failure
        // could replace "Saving…" in the one-message buffer before it was read.
        val collected = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { messages.messages.take(count).toList() }
        saver.save(post)
        collected.await()
      }
    }
  }

  @AfterTest
  fun tearDown() {
    scope.cancel()
    dir.deleteRecursively()
  }

  @Test
  fun saveWritesTheOriginalAndOffersToOpenIt() {
    val rig = Rig()

    val messages = rig.save(2)

    assertEquals(listOf("Saving…", "Saved"), messages.map { it.text })
    val saved = File(dir, "0123456789abcdef0123456789abcdef.mp4")
    assertContentEquals(bytes, saved.readBytes())
    assertEquals(listOf(saved.name), dir.list()!!.toList())
    assertEquals("Open", messages.last().actionLabel)
    messages.last().onAction()
    assertEquals(listOf(saved), opened)
    assertFalse(rig.saver.saving.value.contains(post.id))
  }

  @Test
  fun aClaimedPostShowsItsSaveOnTheControlNotInMessages() = runBlocking {
    val rig = Rig()
    val release = rig.saver.claim(post.id)
    val shown = mutableListOf<Message>()
    val collecting = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { rig.messages.messages.collect { shown += it } }

    withTimeout(5_000) {
      rig.saver.save(post)
      rig.saver.fresh.first { post.id in it }
    }
    collecting.cancel()

    assertTrue(rig.saver.fresh.value.getValue(post.id) is SaveOutcome.Saved)
    assertEquals(setOf(post.id), rig.saver.saved.value.keys)
    // Neither "Saving…" nor "Saved": ⋮ shows both.
    assertEquals(emptyList(), shown)
    rig.saver.settle(post.id)
    assertTrue(rig.saver.fresh.value.isEmpty())
    release()
  }

  @Test
  fun aSavedStillUnshownWhenTheControlLeavesGoesToMessages() = runBlocking {
    val rig = Rig()
    val release = rig.saver.claim(post.id)
    withTimeout(5_000) {
      rig.saver.save(post)
      rig.saver.fresh.first { post.id in it }
    }
    val shown = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { rig.messages.messages.first() } }

    // The viewer closed (or ⋮ moved to another post) before the window ended.
    release()

    assertEquals("Saved" to "Open", shown.await().let { it.text to it.actionLabel })
    assertTrue(rig.saver.fresh.value.isEmpty())
  }

  @Test
  fun aClaimedPostsFailureStillSaysWhyAndOffersARetry() {
    val rig = Rig(HttpStatusCode.InternalServerError)
    rig.saver.claim(post.id)

    val messages = rig.save(1)

    assertEquals("Couldn't save the file" to true, messages.single().let { it.text to it.isError })
    assertEquals(SaveOutcome.Failed(noFile = false), rig.saver.fresh.value[post.id])
  }

  @Test
  fun aSaveThatEndsAfterTheControlLeftGoesToMessages() {
    val rig = Rig()
    rig.saver.claim(post.id)()

    val messages = rig.save(2)

    assertEquals(listOf("Saving…", "Saved"), messages.map { it.text })
    assertTrue(rig.saver.fresh.value.isEmpty())
  }

  @Test
  fun aFileSavedBeforeIsNotDownloadedAgain() {
    File(dir, "0123456789abcdef0123456789abcdef.mp4").writeBytes(bytes)
    val rig = Rig()

    val messages = rig.save(1)

    assertEquals("Saved", messages.single().text)
    assertTrue(rig.requests.isEmpty())
    assertFalse(rig.saver.saving.value.contains(post.id))
  }

  @Test
  fun aFailedDownloadLeavesNothingAndSaysSoWithoutTheUrl() {
    val rig = Rig(HttpStatusCode.InternalServerError)

    val messages = rig.save(2)

    assertEquals("Couldn't save the file", messages.last().text)
    assertTrue(dir.list()!!.isEmpty())
    assertFalse(rig.saver.saving.value.contains(post.id))
  }

  @Test
  fun aSecondSaveWhileTheFirstRunsIsIgnored() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val rig = Rig(gate = gate)

    rig.saver.save(post)
    rig.saver.save(post)
    withTimeout(5_000) { rig.saver.saving.first { post.id in it } }
    gate.complete(Unit)
    withTimeout(5_000) { rig.saver.saving.first { it.isEmpty() } }

    assertEquals(1, rig.requests.size)
  }

  @Test
  fun aFolderLeavesNoPartFileWhenTheWriteFails() = runBlocking {
    val folder = DirectorySaveFolder(dir) {}

    assertFailsWith<IllegalStateException> {
      folder.save("x.png", "image/png") { sink ->
        sink.write(Buffer().apply { write(bytes) }, bytes.size.toLong())
        error("network dropped")
      }
    }

    assertTrue(dir.list()!!.isEmpty())
    assertEquals(null, folder.find("x.png"))
  }
}
