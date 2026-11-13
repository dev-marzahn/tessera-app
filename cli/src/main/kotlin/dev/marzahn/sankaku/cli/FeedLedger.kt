package dev.marzahn.sankaku.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.attribute.PosixFilePermissions

/** A keyset cursor kept from an earlier run, replayed on later ones to see how long it stays valid. */
@Serializable
data class SavedCursor(
  val kind: String,
  val savedAt: Long,
  val tags: String,
  val flag: String?,
  val limit: Int,
  val page: Int,
  val cursor: String,
  /** [digestOf] the ids of the page it led to when saved, and how many posts that was. */
  val digest: String,
  val count: Int,
)

/**
 * The top of Following as an earlier run saw it: [digest] of each id, and the newest and oldest upload time; and the
 * [follows] then ([digest] of each followed tag's id), null when they weren't read, so a later run can tell posts that
 * arrived late from posts of a tag followed since (#539).
 */
@Serializable
data class HeadSnapshot(
  val savedAt: Long,
  val newest: Long,
  val oldest: Long,
  val seen: List<String>,
  val follows: List<String>? = null,
)

@Serializable
data class LedgerData(val cursors: List<SavedCursor> = emptyList(), val head: HeadSnapshot? = null)

/**
 * What discovery steps keep between runs, outside the repository: cursors saved to replay later, and a snapshot of
 * Following's top to see which posts turn up below it later. Holds cursors and hashed ids, so it never leaves the
 * machine: the default is `~/.sankaku-cli/feed-ledger.json`, or `SANKAKU_FEED_LEDGER`.
 */
class FeedLedger(private val file: File) {
  private val json = Json { ignoreUnknownKeys = true }
  var data: LedgerData = load()
    private set

  /** An unreadable file is kept as `.unreadable` rather than overwritten: days of history can be in it. */
  private fun load(): LedgerData {
    if (!file.exists()) return LedgerData()
    return runCatching { json.decodeFromString<LedgerData>(file.readText()) }.getOrElse {
      runCatching { file.copyTo(File(file.path + ".unreadable"), overwrite = true) }
      LedgerData()
    }
  }

  /** Whether [kind] has no cursor saved in the last [MIN_GAP_SECONDS]: a new one is worth keeping. */
  fun due(kind: String, now: Long): Boolean =
    data.cursors.none { it.kind == kind && now - it.savedAt < MIN_GAP_SECONDS }

  /**
   * Keeps the oldest cursor of [entry]'s kind and the newest after it, [MAX_PER_KIND] in all: keeping only the newest
   * dropped the 2026-11-01 cursors after a few days of runs, before they were a week old (#314).
   */
  fun add(entry: SavedCursor) {
    val young = (data.cursors.filter { it.kind == entry.kind } + entry)
      .filter { entry.savedAt - it.savedAt < MAX_AGE_SECONDS }
    val sameKind = if (young.size <= MAX_PER_KIND) young else listOf(young.first()) + young.takeLast(MAX_PER_KIND - 1)
    data = data.copy(cursors = data.cursors.filter { it.kind != entry.kind } + sameKind)
  }

  fun setHead(head: HeadSnapshot) {
    data = data.copy(head = head)
  }

  fun save() {
    file.parentFile?.mkdirs()
    // Owner-only before anything is written (best effort): a cursor is only good for reading Sankaku, but there's no
    // reason to share it.
    if (!file.exists()) {
      runCatching {
        java.nio.file.Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      }
    }
    file.writeText(json.encodeToString(LedgerData.serializer(), data) + "\n")
  }

  companion object {
    const val MIN_GAP_SECONDS = 90 * 60L
    const val MAX_AGE_SECONDS = 30 * 86400L
    private const val MAX_PER_KIND = 8

    fun default(env: Map<String, String>): FeedLedger =
      FeedLedger(File(env["SANKAKU_FEED_LEDGER"] ?: "${System.getProperty("user.home")}/.sankaku-cli/feed-ledger.json"))
  }
}
