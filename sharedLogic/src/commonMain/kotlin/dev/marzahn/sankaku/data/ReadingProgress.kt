package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.net.ConnectivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the reader left a book: [sequence] is the page's API `sequence` (0-based; what `PUT /pools/{id}/reading`
 * takes), [page] its place among the [pages] the reader had, from 1. They differ when a book returns fewer pages than
 * it has (`docs/api.md` → Books).
 */
data class BookPlace(val sequence: Int, val page: Int, val pages: Int) {
  /** At the reader's last page: the book is read. */
  val finished: Boolean get() = page >= pages
}

/**
 * A book's saved place, as a cover and the reader show it: [sequence] to open at, [page] of [pages] (from 1) and
 * whether it's [finished] ("Read").
 */
data class SavedReading(val sequence: Int, val page: Int, val pages: Int, val finished: Boolean) {
  /** How far through the book, 0 to 1. */
  val fraction: Float get() = if (finished) 1f else (page.toFloat() / pages.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/**
 * Reading progress (#384, #377 decision 12): where the account stopped in each book, synced with Sankaku.
 *
 * - **Signed in**, a [save] is sent with `PUT /pools/{id}/reading` ([BooksApi.saveReading]) in [scope] by one
 *   sender, in the order saved, so a background save can't land after the close that followed it. One saved by an
 *   account that has since signed out, or another, isn't sent at all. What was saved this run is held in memory over
 *   the server's `reading`, so a row shows it before its list loads again.
 * - A place not yet sent is kept in [file], with the account that saved it, until Sankaku has it (#491). One that
 *   couldn't reach Sankaku ([ApiError.Network]) is sent again, once, when the network comes back
 *   ([ConnectivityState.retryRequests]), and at the next launch by that account, as #491 asks; it shows over the
 *   server's `reading` meanwhile, across a restart. The API gives `reading` no time, so the unsent place wins. One
 *   Sankaku answered with an error isn't sent again (REVIEW.md: no automatic retry). Past [LIMIT] unsent books, the
 *   least recently saved is dropped unsent.
 * - The books the account finished here are kept in [file] too, by the page they were finished at: a book that returns
 *   fewer pages than it counts never reaches the server's 100 %, and stays "Read" while the server still has that
 *   page. These and the unsent places are forgotten when the account changes (a sign-out, or another account signing
 *   in).
 * - **Signed out**, the place is kept on this device only, in [file] (`readings.json`), at most [LIMIT] books, the
 *   most recently saved kept. Nothing is sent.
 * - [file] is written in [scope], never on the caller's thread. [clear] (Clear everything) forgets everything here;
 *   what the account saved on Sankaku stays there.
 *
 * Nothing here is logged: it holds book ids (AGENTS.md).
 */
class ReadingProgress(
  private val api: BooksApi,
  private val session: AuthSession,
  private val connectivity: ConnectivityState,
  private val file: TextFile,
  private val scope: CoroutineScope,
) {
  private val saved = load()

  // Signed out: by book id, the least recently saved first.
  private val device = MutableStateFlow(saved.books.associate { it.id to it.place() })

  // Signed in: the books finished here, by id, at the sequence they were finished at; the least recently first.
  private val finished = MutableStateFlow(saved.read.associate { it.id to it.sequence })

  /** A place Sankaku doesn't have yet, saved by [account] (#491). */
  private data class Unsent(val place: BookPlace, val account: String)

  // Signed in: the places Sankaku doesn't have yet, by book id, the least recently saved first.
  private val unsent = MutableStateFlow(saved.unsent.associate { it.id to Unsent(it.place(), it.account) })

  // The unsent books whose last send couldn't reach Sankaku, to send again when the network is back.
  private val unreachable = MutableStateFlow<Set<String>>(emptySet())

  // Signed in: what was saved this run, or is still unsent from an earlier one, by book id.
  private val sent = MutableStateFlow(unsent.value.mapValues { it.value.place })

  private val _revision = MutableStateFlow(0)

  /** Goes up whenever a saved place changes, so a screen showing progress can recompose. Only a counter. */
  val revision: StateFlow<Int> = _revision.asStateFlow()

  private val _landed = MutableStateFlow(0)

  /**
   * Goes up whenever Sankaku took a place, so a list of the books being read (#503, its order the most recently saved
   * first) can be asked for again. Only a counter.
   */
  val landed: StateFlow<Int> = _landed.asStateFlow()

  /** A place to send, for the [account] signed in when it was saved. */
  private class Send(val bookId: String, val place: BookPlace, val account: String)

  private val outbox = Channel<Send>(Channel.UNLIMITED)
  private val writes = Channel<Unit>(Channel.CONFLATED)

  // Between a write of the file and [clear]'s delete, so an older write can't bring it back.
  private val fileLock = Mutex()

  init {
    scope.launch {
      for (send in outbox) {
        // Saved by an account that has since signed out, or another: not this one's to send.
        if (account() != send.account) continue
        val couldNotReach = try {
          api.saveReading(send.bookId, send.place.sequence)
          _landed.update { it + 1 }
          false
        } catch (e: ApiError) {
          // Unreachable: kept, and sent again at the next launch or when the network is back. Any other answer isn't
          // sent again; the place stays shown this run, and the reader's next trigger sends its newer one.
          e is ApiError.Network
        }
        // Only while it's still the book's unsent place: a newer one saved meanwhile stays unsent, and is sent itself.
        val mine = Unsent(send.place, send.account)
        if (couldNotReach) {
          if (unsent.value[send.bookId] == mine) unreachable.update { it + send.bookId }
        } else if (unsent.getAndUpdate { if (it[send.bookId] == mine) it - send.bookId else it }[send.bookId] == mine) {
          writes.trySend(Unit)
        }
      }
    }
    // The network may be back: the places that couldn't reach Sankaku go again, once each.
    scope.launch {
      connectivity.retryRequests.drop(1).collect {
        val ids = unreachable.getAndUpdate { emptySet() }
        sendUnsent { bookId, _ -> bookId in ids }
      }
    }
    scope.launch {
      for (write in writes) fileLock.withLock { write() }
    }
    // Another account, or none: what this one saved isn't theirs. A launch's restore isn't a change: signed in, it sends
    // what an earlier run couldn't.
    scope.launch {
      var last: String? = null
      var known = false
      session.state.collect { state ->
        if (state is AuthState.Restoring) return@collect
        val now = (state as? AuthState.SignedIn)?.user?.id
        if (known && last != now) {
          sent.value = emptyMap()
          finished.value = emptyMap()
          unsent.value = emptyMap()
          unreachable.value = emptySet()
          changed()
          writes.trySend(Unit)
        }
        // The earlier run's, if this account's: one saved since is queued already.
        if (!known) {
          // Another account's, or none's (the session ended while the app was closed): not shown, never sent.
          val foreign = unsent.value.filterValues { it.account != now }.keys
          if (foreign.isNotEmpty()) {
            unsent.update { it - foreign }
            sent.update { it - foreign }
            changed()
            writes.trySend(Unit)
          }
          val earlier = saved.unsent.associate { it.id to Unsent(it.place(), it.account) }
          sendUnsent { bookId, entry -> earlier[bookId] == entry }
        }
        last = now
        known = true
      }
    }
  }

  // Queues the unsent places [which] picks that the account signed in now saved; another account's are never sent.
  private fun sendUnsent(which: (bookId: String, entry: Unsent) -> Boolean) {
    val account = account() ?: return
    unsent.value.forEach { (bookId, entry) ->
      if (entry.account == account && which(bookId, entry)) outbox.trySend(Send(bookId, entry.place, account))
    }
  }

  // Who is signed in now, by user id (never logged); null signed out.
  private fun account(): String? = (session.state.value as? AuthState.SignedIn)?.user?.id

  /**
   * Where [book] was left, or null if it wasn't opened (or only looked at). Signed in, what was saved this run, else
   * the server's `reading` on [book]; signed out, this device's.
   */
  fun savedOf(book: BookDto, signedIn: Boolean = session.isSignedIn.value): SavedReading? {
    val place = if (signedIn) sent.value[book.id] else device.value[book.id]
    if (place != null) return SavedReading(place.sequence, place.page.coerceIn(1, place.pages.coerceAtLeast(1)), place.pages.coerceAtLeast(1), place.finished)
    if (!signedIn) return null
    val reading = book.reading ?: return null
    val current = reading.currentPage ?: reading.sequence?.plus(1) ?: return null
    val sequence = (reading.sequence ?: (current - 1)).coerceAtLeast(0)
    val pages = book.postCount.coerceAtLeast(current).coerceAtLeast(1)
    val read = current >= pages || (reading.percent ?: 0.0) >= 100.0 || finished.value[book.id] == sequence
    return SavedReading(sequence, current.coerceIn(1, pages), pages, read)
  }

  /** Saves [place] as where [bookId] was left: sent to Sankaku signed in, kept on this device signed out. */
  fun save(bookId: String, place: BookPlace) {
    val account = account()
    if (account != null) {
      sent.update { it + (bookId to place) }
      unsent.update { it.latest(bookId, Unsent(place, account)) }
      // Queued below: not one to send again when the network is back unless this send fails to connect too.
      unreachable.update { it - bookId }
      finished.update { read -> read.latest(bookId, place.sequence.takeIf { place.finished }) }
      outbox.trySend(Send(bookId, place, account))
    } else {
      device.update { it.latest(bookId, place) }
    }
    changed()
    writes.trySend(Unit)
  }

  /** Forgets every place kept on this device and those saved this run, and deletes [file]: Clear everything. */
  suspend fun clear() {
    fileLock.withLock {
      device.value = emptyMap()
      finished.value = emptyMap()
      unsent.value = emptyMap()
      unreachable.value = emptySet()
      sent.value = emptyMap()
      runCatching { file.delete() }
    }
    changed()
  }

  private fun changed() = _revision.update { it + 1 }

  private fun load(): ReadingsFile =
    file.read()?.let { text -> runCatching { json.decodeFromString(ReadingsFile.serializer(), text) }.getOrNull() } ?: ReadingsFile(VERSION)

  private fun write() {
    val books = device.value.map { (id, p) -> ReadingEntry.of(id, p) }
    val read = finished.value.map { (id, sequence) -> ReadEntry(id, sequence) }
    val unsent = unsent.value.map { (id, u) -> UnsentEntry(id, u.place.sequence, u.place.page, u.place.pages, u.account) }
    // A full disk or a locked file loses only this save.
    runCatching {
      if (books.isEmpty() && read.isEmpty() && unsent.isEmpty()) {
        file.delete()
      } else {
        file.write(json.encodeToString(ReadingsFile.serializer(), ReadingsFile(VERSION, books, read, unsent)))
      }
    }
  }

  // [value] (none if null) as [bookId]'s, now the most recently saved, keeping the [LIMIT] most recent.
  private fun <V : Any> Map<String, V>.latest(bookId: String, value: V?): Map<String, V> {
    val map = LinkedHashMap(this).apply { remove(bookId); if (value != null) put(bookId, value) }
    val excess = map.size - LIMIT
    if (excess > 0) map.keys.take(excess).forEach(map::remove)
    return map
  }

  companion object {
    /** How many books each list in [ReadingProgress]'s file keeps at most: the most recently saved. */
    const val LIMIT = 1_000

    private const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }
  }
}

/**
 * `readings.json`: the signed-out places ([books]), the books the signed-in account finished here ([read]) and its
 * places Sankaku doesn't have yet ([unsent]), each the least recently saved first.
 */
@Serializable
private data class ReadingsFile(
  val version: Int,
  val books: List<ReadingEntry> = emptyList(),
  val read: List<ReadEntry> = emptyList(),
  val unsent: List<UnsentEntry> = emptyList(),
)

/** A signed-in place Sankaku doesn't have yet, with the [account] (user id) that saved it, so no other sends it. */
@Serializable
private data class UnsentEntry(
  @SerialName("i") val id: String,
  @SerialName("s") val sequence: Int,
  @SerialName("p") val page: Int,
  @SerialName("n") val pages: Int,
  @SerialName("a") val account: String,
) {
  fun place() = BookPlace(sequence, page, pages)
}

@Serializable
private data class ReadingEntry(
  @SerialName("i") val id: String,
  @SerialName("s") val sequence: Int,
  @SerialName("p") val page: Int,
  @SerialName("n") val pages: Int,
) {
  fun place() = BookPlace(sequence, page, pages)

  companion object {
    fun of(id: String, place: BookPlace) = ReadingEntry(id, place.sequence, place.page, place.pages)
  }
}

@Serializable
private data class ReadEntry(@SerialName("i") val id: String, @SerialName("s") val sequence: Int)
