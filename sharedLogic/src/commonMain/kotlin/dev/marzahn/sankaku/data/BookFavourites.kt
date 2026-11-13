package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the account has ♡ed a book, and the book's ♡ count. */
data class BookFavourite(val on: Boolean, val count: Int)

/** [book]'s ♡ as the user sees it: [overrides]' entry, else what the book said when it was loaded. */
fun BookDto.favourite(overrides: Map<String, BookFavourite>): BookFavourite =
  overrides[id] ?: BookFavourite(isFavorited, favCount)


/**
 * The books ♡ed or un-♡ed in this run (#383), over what a list or the reader loaded: a ♡ shows at once, and comes back
 * as the server last had it if the API fails, as a post's does ([PostsRepository.toggleFavourite]). Entries belong to
 * the account that made them, so another account (or none) never sees them; [overrides] is the current account's.
 */
class BookFavourites(private val api: BooksApi, private val session: AuthSession, scope: CoroutineScope) {
  private data class Key(val user: String, val bookId: String)

  /**
   * [shown] is what the user sees, [confirmed] what the server last answered, [generation] the toggle that owns it.
   * [settledAt] is when its last call answered, null while one is on its way: a book loaded after that replaces it.
   */
  private data class Entry(
    val shown: BookFavourite,
    val confirmed: BookFavourite,
    val generation: Int,
    val settledAt: Int? = null,
  )

  private val entries = MutableStateFlow<Map<Key, Entry>>(emptyMap())

  // Guards [generation] and each read-then-write of [entries]: toggles run on several threads. [generation] is also
  // the clock that orders a settle against a load ([fresh]).
  private val state = Mutex()
  private var generation = 0
  private val calls = mutableMapOf<String, Mutex>()

  /** The current account's ♡s by book id; empty signed out. */
  val overrides: StateFlow<Map<String, BookFavourite>> = combine(entries, session.state) { all, state -> mine(all, state) }
    .stateIn(scope, SharingStarted.Eagerly, emptyMap())

  private fun mine(all: Map<Key, Entry>, state: AuthState): Map<String, BookFavourite> {
    val user = userOf(state) ?: return emptyMap()
    return all.entries.filter { it.key.user == user }.associate { it.key.bookId to it.value.shown }
  }

  /** [book]'s ♡ now, read straight from the entries (not a dispatch behind, as [overrides] can be). */
  fun current(book: BookDto): BookFavourite =
    userOf(session.state.value)?.let { entries.value[Key(it, book.id)]?.shown } ?: book.loaded()

  /**
   * Flips [book]'s ♡ at once, then calls the API; the response's count wins when it has one. On failure the book
   * shows what the server last answered, unless a newer toggle owns it by then. The calls for one book run one at a
   * time, in the order of the taps. Signed out, it fails without asking. [onShown] hears each ♡ this toggle shows: the
   * flipped one at once, and the one it rolled back to. It runs under the lock, so it must not block.
   */
  suspend fun toggle(book: BookDto, onShown: (on: Boolean) -> Unit = {}): Result<BookFavourite> {
    val user = userOf(session.state.value) ?: return Result.failure(ApiError.Unauthorized())
    val key = Key(user, book.id)
    val (target, mine) = state.withLock {
      val entry = entries.value[key]
      val before = entry?.shown ?: book.loaded()
      val target = BookFavourite(!before.on, (before.count + if (before.on) -1 else 1).coerceAtLeast(0))
      val made = ++generation
      entries.update { it + (key to Entry(target, entry?.confirmed ?: book.loaded(), made)) }
      onShown(target.on)
      target to made
    }
    val call = state.withLock { calls.getOrPut(book.id) { Mutex() } }
    return try {
      val result = call.withLock { api.favourite(book.id, target.on) }
      val done = target.copy(count = result.favCount ?: target.count)
      settle(key) { entry, now -> if (entry.generation == mine) Entry(done, done, mine, now) else entry.copy(confirmed = done) }
      Result.success(done)
    } catch (e: ApiError) {
      settle(key) { entry, now ->
        if (entry.generation == mine) {
          if (entry.confirmed.on != entry.shown.on) onShown(entry.confirmed.on)
          entry.copy(shown = entry.confirmed, settledAt = now)
        } else {
          entry
        }
      }
      Result.failure(e)
    }
  }

  /**
   * Runs [load], a request for books ([books] picks them from its answer), and lets what it answers win over a ♡ that
   * settled before it was sent: a list or book loaded since says what the server has now, ♡ed on the website or
   * another device included. A ♡ still on its way, or one that settled while [load] ran, stays.
   */
  suspend fun <T> fresh(load: suspend () -> T, books: (T) -> List<BookDto>): T {
    val user = userOf(session.state.value)
    val sentAt = state.withLock { ++generation }
    val answer = load()
    if (user != null) {
      val ids = books(answer).mapTo(HashSet()) { it.id }
      state.withLock {
        entries.update { all ->
          all.filterNot { (key, entry) -> key.user == user && key.bookId in ids && (entry.settledAt ?: Int.MAX_VALUE) < sentAt }
        }
      }
    }
    return answer
  }

  /**
   * ♡s [book] unless the account has already: for a ♡ tapped signed out, once signed in. A book loaded signed out
   * can't say, so it's asked first; if that fails, it's ♡ed from what the user saw. [onShown] is [toggle]'s, and hears
   * nothing when the book was ♡ed already.
   */
  suspend fun favouriteUnlessAlready(book: BookDto, onShown: (on: Boolean) -> Unit = {}): Result<BookFavourite> {
    val fresh = try {
      api.pages(book.id)
    } catch (_: ApiError) {
      book
    }
    val now = current(fresh)
    return if (now.on) Result.success(now) else toggle(fresh, onShown)
  }

  private suspend fun settle(key: Key, change: (Entry, now: Int) -> Entry) = state.withLock {
    val now = ++generation
    entries.update { all -> all[key]?.let { all + (key to change(it, now)) } ?: all }
  }

  private fun BookDto.loaded() = BookFavourite(isFavorited, favCount)

  private fun userOf(state: AuthState) = (state as? AuthState.SignedIn)?.user?.id
}
