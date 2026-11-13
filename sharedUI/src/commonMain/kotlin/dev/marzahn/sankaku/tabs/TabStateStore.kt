package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.queue.QueueKind
import dev.marzahn.sankaku.queue.SessionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * The open tabs as `tabs.json` holds them: each tab's back stack, bottom first, and which one is active. From
 * [VERSION] 2 (#174) the file says its [version] and the [session] the queue was in; a file without a version is v1.
 * The [faded] blocks (#215) sit apart from the tabs; the file stays v2, since the field is left out when empty and an
 * older app ignores it.
 */
@Serializable
data class SavedTabs(
  val activeIndex: Int,
  val tabs: List<SavedTab>,
  val version: Int? = null,
  val session: String? = null,
  val faded: List<SavedFaded> = emptyList(),
  /**
   * Where leaving a block lands, by the id of the block's head: the id of the item a search was opened "now" from, or
   * jumped to its open source from ([TabsController]'s returns). Left out when empty, so a file without it reads, and
   * writes back, as it was.
   */
  val returns: Map<String, String> = emptyMap(),
) {
  companion object {
    /** The version this app writes. */
    const val VERSION = 2
  }
}

/**
 * [block] is the id of the tab heading the block this one was opened into ([TabState.block]). [place] is where its top
 * grid was scrolled to ([TabState.place]).
 *
 * From v2, the queue item's facts (#174): its [kind], derived from the top route; when it was [touched] (UTC seconds);
 * the session it was [addedIn]; and for a kept post, when it was kept ([keptAt], UTC seconds). Each field is left out
 * when null (the default isn't encoded), so a file saved before it existed reads, and writes back, as it was.
 *
 * [closedSource] (#184) is, for a kept post whose source was closed, that source's search ([TabState.closedSource]); it
 * keeps the block "from … (closed)" known across runs. It is optional like the others, so the file stays v2.
 *
 * [title] (#156) is, for a post opened without a label (a parent link) that has loaded since, the name [PostTitle]
 * gave it ([TabState.loadedTitle]), so the tab isn't "Untitled post" after a restart. Left out otherwise, so a file
 * without it reads, and writes back, as it was.
 *
 * [mosaic] and [posts] (#232) are, for a grid on top, what the queue page shows for it ([TabState.mosaic]): up to
 * four thumbnails from where it was last scrolled to and how many posts it had loaded, so a restored source looks as it did
 * before the relaunch. Left out until the grid has loaded posts, so a file without them reads, and writes back, as it
 * was.
 *
 * [gridTouched] (#245) is true when the grid on top was scrolled or opened a post and has no [place]
 * ([TabState.savedTouched]), so a search on it after a relaunch still opens in front of it; a place says touched by
 * itself. Left out otherwise, so a file without it reads, and writes back, as it was.
 *
 * [openedFrom] (#349) is, for a grid a search opened "now" from another item, that item's id ([TabState.openedFrom]):
 * Back on the grid closes it and returns there, after a relaunch too. Left out otherwise, so a file without it reads,
 * and writes back, as it was, and its grids keep the Back they had.
 *
 * [refines] (#509) is, for a grid a search on another grid opened as a new item, that grid's search
 * ([TabState.refines]), which its queue row names ("from Landscape"). Left out otherwise, so a file without it
 * reads, and writes back, as it was.
 */
@Serializable
data class SavedTab(
  val id: String,
  val routes: List<Route>,
  val block: String? = null,
  val place: GridPlace? = null,
  val kind: QueueKind? = null,
  val touched: Long? = null,
  val addedIn: String? = null,
  val keptAt: Long? = null,
  val closedSource: List<String>? = null,
  val title: String? = null,
  val mosaic: List<QueueThumb>? = null,
  val posts: Int? = null,
  val gridTouched: Boolean? = null,
  val openedFrom: String? = null,
  val refines: List<String>? = null,
)

/**
 * A faded source and the unseen kept posts that faded with it (#215): [tabs] in queue order, the source first (the
 * others' [SavedTab.block] is its id). [at] is when it faded, in UTC seconds, and [offered] says Up next showed its
 * last chance already.
 */
@Serializable
data class SavedFaded(val at: Long, val tabs: List<SavedTab>, val offered: Boolean? = null)

/** Where the open tabs are kept between runs: `tabs.json` on both platforms. */
interface TabStateStore {
  /**
   * The saved tabs, as v2 (a v1 file is migrated). Null when nothing is saved, or it can't be read or parsed (a
   * corrupt file, routes from another version, or a newer version).
   */
  fun load(): SavedTabs?

  fun save(tabs: SavedTabs)

  /** Empties the saved tabs. */
  fun clear()

  /** Deletes the copies the store keeps aside (the v1 backup and a broken file): Clear everything. */
  fun clearBackups() = Unit

  object None : TabStateStore {
    override fun load(): SavedTabs? = null

    override fun save(tabs: SavedTabs) = Unit

    override fun clear() = Unit
  }
}

/**
 * [SavedTabs] as JSON in [file] (`tabs.json`). Clearing writes an empty object, which [load] reads as nothing saved.
 *
 * Nothing saved is ever lost to a format change (#174):
 * - **v1** (no `version`, every file before #174) is migrated as it's read ([migrate]); before the first v2 write, the
 *   v1 text is copied to [backup] (`tabs.v1.bak`), so a v1 file is never overwritten without a copy.
 * - **Unreadable** (corrupt, routes from another version, a newer version): its text is moved to [broken]
 *   (`tabs.json.broken`) rather than lost, and the app starts with one Browse tab.
 */
class FileTabStateStore(
  private val file: TextFile,
  private val backup: TextFile = InMemoryTextFile(),
  private val broken: TextFile = InMemoryTextFile(),
  private val clock: Clock = Clock.System,
) : TabStateStore {
  // A copy of the file read at launch still to be made before [file] is first written: the v1 text for [backup], or
  // an unreadable text for [broken] whose move failed at launch.
  private var copy: Pair<TextFile, String>? = null

  override fun load(): SavedTabs? {
    val text = file.read() ?: return null
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    if (root != null && root.isEmpty()) return null
    val saved = root?.let { runCatching { read(it, text) }.getOrNull() }
    if (saved == null) moveAside(text)
    return saved
  }

  private fun read(root: JsonObject, text: String): SavedTabs? = when (root["version"]?.jsonPrimitive?.int) {
    null -> migrate(json.decodeFromString(SavedTabs.serializer(), text), clock.now().epochSeconds).also { copy = backup to text }
    SavedTabs.VERSION -> json.decodeFromString(SavedTabs.serializer(), text)
    else -> null
  }

  override fun save(tabs: SavedTabs) = write(json.encodeToString(SavedTabs.serializer(), tabs.copy(version = SavedTabs.VERSION)))

  override fun clear() = write("{}")

  // Every write of [file] makes the pending copy first; a failed copy fails the write, so the file stays as it was.
  private fun write(text: String) {
    copy?.let { (to, copied) -> to.write(copied) }
    copy = null
    file.write(text)
  }

  override fun clearBackups() {
    copy = null
    backup.delete()
    broken.delete()
  }

  // The latest unreadable file wins: an older one in [broken] is replaced. If that fails, the next write tries again.
  private fun moveAside(text: String) {
    copy = broken to text
    runCatching { broken.write(text) }.onSuccess { copy = null }
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A v1 file's tabs as v2: the same order, active tab, routes, blocks and places. Each tab's kind comes from its top
     * route; every tab is touched [now] (the first launch after the update, so nothing fades at once in stage 3) and
     * joins one earlier session ([SessionId.EARLIER]), so the items opened "later" go after them. A kept post was
     * kept [now].
     */
    fun migrate(v1: SavedTabs, now: Long): SavedTabs = v1.copy(
      version = SavedTabs.VERSION,
      tabs = v1.tabs.map { tab ->
        val kind = queueKind(tab.routes.lastOrNull())
        tab.copy(
          kind = kind,
          touched = now,
          addedIn = SessionId.EARLIER.value,
          keptAt = now.takeIf { kind == QueueKind.KeptPost },
        )
      },
    )
  }
}
